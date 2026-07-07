package rs117.hd.particles;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.JagexColor;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.Perspective;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.ItemID;

/**
 * Renders particles as camera-facing soft discs, batched into one merged
 * scene model per occupied tile.
 *
 * The client draws renderables through per-tile slots, and a tile only holds
 * a few - hundreds of individual RuneLiteObjects stacked on the same tile
 * mostly don't draw. So, like the game's own area effects, all particles on a
 * tile are merged into a single model rendered by one pooled RuneLiteObject.
 *
 * To keep the hot path allocation-free, batches are pre-built "canvases":
 * a model merged and lit ONCE from K disc slots, whose live vertex,
 * transparency and color arrays are then overwritten in place every tick -
 * no clones, merges or relights at steady state. Vertices are billboarded
 * toward the camera during the write; transparency (fade x radial falloff)
 * and colors are array-copied from pre-baked per-style templates, only when
 * a slot's style, size or fade step changes. Both lit and unlit color arrays
 * are written since renderers differ in which they consume.
 *
 * The engine welds identical-position vertices when merging, which would
 * break slot slicing, so the canvas prototype gets tiny per-vertex epsilon
 * offsets and the build is verified; on failure the renderer falls back to
 * legacy per-tick merging.
 *
 * All methods must be called on the client thread.
 */
@Slf4j
class ParticleRenderer
{
	/**
	 * Thickness of the billboard lens relative to its radius. Nonzero so the
	 * two hemispheres of the flattened source mesh aren't perfectly coplanar.
	 */
	private static final float LENS_FLATTEN = 0.12f;
	/**
	 * High ambient and weak directional light so discs read as self-lit glow
	 * instead of shaded surfaces.
	 */
	private static final int LIGHT_AMBIENT = 90;
	private static final int LIGHT_CONTRAST = 2000;
	/**
	 * Caps per batch model, from the GPU plugin's FacePrioritySorter budgets
	 * (6500 vertices, 4000 faces per priority - our faces share one priority).
	 * A tile gets multiple batch objects if its particles exceed a canvas.
	 */
	private static final int MAX_FACES_PER_BATCH = 3500;
	private static final int MAX_VERTICES_PER_BATCH = 6000;
	private static final byte INVISIBLE = (byte) 254;

	/**
	 * Fixed volume around the tile center that canvas geometry must stay
	 * inside. Model bounds are computed ONCE over this volume at build time
	 * (the engine caches bounds; recomputing on mutated models is a no-op),
	 * so runtime writes are clamped into it - otherwise the GPU plugin's
	 * depth sort asserts on vertices outside the frozen radius.
	 */
	private static final float VOLUME_HORIZONTAL = 256f;
	private static final float VOLUME_UP = -1200f;
	private static final float VOLUME_DOWN = 200f;
	private static final float CLAMP_MARGIN = 56f;

	/**
	 * A pre-merged, pre-lit batch model whose arrays are rewritten in place.
	 */
	private class BatchCanvas
	{
		final ModelData modelData;
		final Model lit;
		final float[] vx, vy, vz;
		final byte[] transparencies;
		final short[] unlitColors;
		final int[] colors1, colors2, colors3;
		final RuneLiteObject object;
		final ParticleStyle[] slotStyle;
		final int[] slotVariant;

		boolean claimed;
		long tileKey;
		LocalPoint centerLp;
		int centerHeight;
		int usedSlots;
		/**
		 * Highest slot count written since slots were last cleared; slots in
		 * [usedSlots, highWater) hold stale geometry and need clearing.
		 */
		int highWater;

		BatchCanvas(ModelData merged)
		{
			modelData = merged;
			lit = merged.light(LIGHT_AMBIENT, LIGHT_CONTRAST,
				ModelData.DEFAULT_X, ModelData.DEFAULT_Y, ModelData.DEFAULT_Z);
			vx = merged.getVerticesX();
			vy = merged.getVerticesY();
			vz = merged.getVerticesZ();
			transparencies = merged.getFaceTransparencies();
			unlitColors = merged.getFaceColors();
			colors1 = lit.getFaceColors1();
			colors2 = lit.getFaceColors2();
			colors3 = lit.getFaceColors3();
			slotStyle = new ParticleStyle[slotsPerCanvas];
			slotVariant = new int[slotsPerCanvas];
			Arrays.fill(slotVariant, -1);

			// Lock in bounds NOW, while the geometry spans the whole clamp
			// volume: the engine computes bounds once and caches, so this is
			// the only computation the model ever gets
			lit.calculateBoundsCylinder();
			for (int slot = 0; slot < slotsPerCanvas; slot++)
			{
				clearSlot(this, slot);
			}

			object = client.createRuneLiteObject();
			object.setDrawFrontTilesFirst(true);
			object.setOrientation(0);
			object.setModel(lit);
		}
	}

	private final Client client;

	private final List<BatchCanvas> canvases = new ArrayList<>();
	private boolean canvasModeFailed;
	private ModelData canvasProto;
	private int slotsPerCanvas;
	/**
	 * Per-tick scratch for O(1) canvas claims: tile -> the canvas currently
	 * filling for that tile, plus a cursor into the pool. Claims only ever
	 * advance the cursor, so canvases claimed this tick are exactly the pool
	 * prefix [0, idleCursor).
	 */
	private final Map<Long, BatchCanvas> tileCanvases = new HashMap<>();
	private int idleCursor;

	/**
	 * Legacy per-tick merge path, kept as fallback if canvas slicing fails.
	 */
	private final List<RuneLiteObject> legacyPool = new ArrayList<>();
	private int legacyUsed;

	/**
	 * Resolved styles by profile key, rebuilt when profiles or definitions change.
	 */
	private final Map<String, ParticleStyle> styles = new HashMap<>();
	private ModelData sourceMesh;
	private int templateVertexCount;
	private int templateFaceCount;

	/**
	 * Diagnostics.
	 */
	@Getter
	private int activeObjects;
	@Getter
	private int lastBatchedVertices;
	@Getter
	private int lastDrawnParticles;
	private int outOfSceneKills;

	ParticleRenderer(Client client)
	{
		this.client = client;
	}

	boolean isReady()
	{
		return sourceMesh != null && !styles.isEmpty();
	}

	/**
	 * @return the resolved style for a profile key, or null
	 */
	ParticleStyle getStyle(String profileKey)
	{
		return styles.get(profileKey);
	}

	/**
	 * (Re)build disc templates for every profile's style.
	 *
	 * @return true on success
	 */
	boolean rebuildStyles(Map<String, EmitterProfile> profiles, Map<String, ParticleDefinition> definitions)
	{
		if (sourceMesh == null)
		{
			sourceMesh = loadSourceMesh();
			if (sourceMesh == null)
			{
				log.debug("Particle base model not yet available");
				return false;
			}
			templateVertexCount = sourceMesh.getVerticesCount();
			templateFaceCount = sourceMesh.getFaceCount();
			slotsPerCanvas = Math.max(1, Math.min(
				MAX_FACES_PER_BATCH / Math.max(1, templateFaceCount),
				MAX_VERTICES_PER_BATCH / Math.max(1, templateVertexCount)));
			if (slotsPerCanvas < 8)
			{
				// Canvas bounds need the eight corner slots; a mesh this heavy
				// can't batch safely, so take the per-tick merge path instead
				log.warn("Particle mesh too heavy for canvas batching ({}v {}f); using per-tick merging",
					templateVertexCount, templateFaceCount);
				canvasModeFailed = true;
			}
			buildCanvasProto();
		}

		styles.clear();
		for (Map.Entry<String, EmitterProfile> entry : profiles.entrySet())
		{
			ParticleDefinition definition = resolveDefinition(entry.getValue(), definitions);
			styles.put(entry.getKey(), buildStyle(definition, entry.getValue()));
		}
		return true;
	}

	private static ParticleDefinition resolveDefinition(EmitterProfile profile,
		Map<String, ParticleDefinition> definitions)
	{
		String id = profile.getDefinitionId();
		if (id != null && !id.isEmpty())
		{
			ParticleDefinition definition = definitions.get(id);
			if (definition != null)
			{
				return definition;
			}
		}
		return ParticleDefinition.fromProfile(profile);
	}

	private ParticleStyle buildStyle(ParticleDefinition definition, EmitterProfile profile)
	{
		int startArgb = definition.getColor();
		int endArgb = definition.getColorEnd();
		boolean gradient = definition.isColorFade() && endArgb != startArgb;
		float fadeStart = Math.max(0, Math.min(100, definition.getColorFadeStart())) / 100f;
		float fadeSpan = Math.max(0.001f, 1f - fadeStart);

		ModelData[][] templates = new ModelData[ParticleStyle.SIZE_MULTIPLIERS.length][ParticleStyle.FADE_STEPS];
		for (int s = 0; s < ParticleStyle.SIZE_MULTIPLIERS.length; s++)
		{
			float radius = definition.getSize() / 2f * ParticleStyle.SIZE_MULTIPLIERS[s];
			ModelData lens = sourceMesh.shallowCopy()
				.cloneVertices()
				.cloneColors();
			spherify(lens, radius);
			flatten(lens, LENS_FLATTEN);

			Shape shape = definition.getShape() == null ? Shape.DEFAULT : definition.getShape();
			shapeWarp(lens, radius, shape);

			Set<Short> originals = new HashSet<>();
			for (short faceColor : lens.getFaceColors())
			{
				originals.add(faceColor);
			}
			float[] radial = shapeFalloff(lens, radius, shape);

			for (int i = 0; i < ParticleStyle.FADE_STEPS; i++)
			{
				float life = (i + 0.5f) / ParticleStyle.FADE_STEPS;
				float fade = Math.max(0f, Math.min(1f, (life - fadeStart) / fadeSpan));
				int argb = gradient ? lerpArgb(startArgb, endArgb, fade) : startArgb;
				short target = JagexColor.rgbToHSL(argb, 1.0d);
				int fadeAlpha = (argb >>> 24) & 0xFF;
				float envelope = envelope(life);
				ModelData variant = lens.shallowCopy().cloneColors().cloneTransparencies(true);
				for (short original : originals)
				{
					variant.recolor(original, target);
				}
				byte[] transparencies = variant.getFaceTransparencies();
				for (int f = 0; f < transparencies.length; f++)
				{
					int alpha = (int) (fadeAlpha * envelope * radial[f]);
					transparencies[f] = (byte) Math.min(254, Math.max(1, 255 - alpha));
				}
				templates[s][i] = variant;
			}
		}

		int[][] lit1 = new int[ParticleStyle.FADE_STEPS][];
		int[][] lit2 = new int[ParticleStyle.FADE_STEPS][];
		int[][] lit3 = new int[ParticleStyle.FADE_STEPS][];
		for (int i = 0; i < ParticleStyle.FADE_STEPS; i++)
		{
			Model probe = templates[1][i].shallowCopy().light(LIGHT_AMBIENT, LIGHT_CONTRAST,
				ModelData.DEFAULT_X, ModelData.DEFAULT_Y, ModelData.DEFAULT_Z);
			lit1[i] = probe.getFaceColors1().clone();
			lit2[i] = probe.getFaceColors2().clone();
			lit3[i] = probe.getFaceColors3().clone();
		}

		return new ParticleStyle(templates, lit1, lit2, lit3, definition, profile);
	}

	/**
	 * The canvas prototype is the disc topology with every vertex nudged by a
	 * unique epsilon, so the engine's identical-position vertex welding can't
	 * fuse slots (or vertices within a slot) when canvases are merged.
	 * Positions are overwritten every tick, so the nudges never render.
	 */
	private void buildCanvasProto()
	{
		canvasProto = sourceMesh.shallowCopy().cloneVertices();
		float[] xs = canvasProto.getVerticesX();
		float[] ys = canvasProto.getVerticesY();
		float[] zs = canvasProto.getVerticesZ();
		for (int j = 0; j < templateVertexCount; j++)
		{
			xs[j] += j * 0.001953125f;
			ys[j] += j * 0.0009765625f;
			zs[j] += j * 0.00048828125f;
		}
	}

	/**
	 * Linear per-channel interpolation between two ARGB colours.
	 */
	private static int lerpArgb(int a, int b, float t)
	{
		int aa = (a >>> 24) & 0xFF, ar = (a >>> 16) & 0xFF, ag = (a >>> 8) & 0xFF, ab = a & 0xFF;
		int ba = (b >>> 24) & 0xFF, br = (b >>> 16) & 0xFF, bg = (b >>> 8) & 0xFF, bb = b & 0xFF;
		int oa = Math.round(aa + (ba - aa) * t);
		int or = Math.round(ar + (br - ar) * t);
		int og = Math.round(ag + (bg - ag) * t);
		int ob = Math.round(ab + (bb - ab) * t);
		return (oa << 24) | (or << 16) | (og << 8) | ob;
	}

	/**
	 * The disc topology every particle is molded from. Spherify normalizes
	 * the shape and size, so the pick trades vertex count (written per
	 * particle per tick) against how round the sphere projection comes out;
	 * the orb reads best. Slot capacity adapts to whatever this returns.
	 */
	private ModelData loadSourceMesh()
	{
		ItemComposition comp = client.getItemDefinition(ItemID.DS2_ORB_INERT);
		return client.loadModelData(comp.getInventoryModel());
	}

	/**
	 * Alpha over normalized age: smooth rise to a mid-life peak and gentle
	 * decay, instead of popping in at full brightness.
	 */
	private static float envelope(float ageFraction)
	{
		return (float) Math.pow(Math.sin(Math.PI * ageFraction), 0.8);
	}

	/**
	 * Push all live particles into batch models. Call once per client tick,
	 * after simulation.
	 */
	void sync(List<Particle> particles, int worldView, int level)
	{
		activeObjects = 0;
		lastBatchedVertices = 0;
		lastDrawnParticles = 0;
		WorldView wv = client.getWorldView(worldView);
		if (wv == null || !isReady())
		{
			reset();
			return;
		}

		// Billboard basis: face every disc toward the camera, exactly. On the
		// GPU the scene is rendered with the floating point camera, which can
		// deviate from the int JAU camera - use whichever the renderer uses,
		// mirroring Perspective.localToCanvas
		float yaw;
		float pitch;
		if (client.isGpu())
		{
			yaw = client.getCameraFpYaw();
			pitch = client.getCameraFpPitch();
		}
		else
		{
			yaw = client.getCameraYaw() * (float) (Math.PI / 1024);
			pitch = client.getCameraPitch() * (float) (Math.PI / 1024);
		}
		float sinYaw = (float) Math.sin(yaw);
		float cosYaw = (float) Math.cos(yaw);
		float sinPitch = (float) Math.sin(pitch);
		float cosPitch = (float) Math.cos(pitch);

		if (canvasModeFailed)
		{
			syncLegacy(particles, wv, worldView, level, sinYaw, cosYaw, sinPitch, cosPitch);
			return;
		}
		syncCanvases(particles, wv, worldView, level, sinYaw, cosYaw, sinPitch, cosPitch);
	}

	private void syncCanvases(List<Particle> particles, WorldView wv, int worldView, int level,
		float sinYaw, float cosYaw, float sinPitch, float cosPitch)
	{
		for (BatchCanvas canvas : canvases)
		{
			canvas.claimed = false;
		}
		tileCanvases.clear();
		idleCursor = 0;
		// Consecutive particles usually share a tile; the memo skips the map
		long lastKey = Long.MIN_VALUE;
		BatchCanvas lastCanvas = null;

		for (Particle p : particles)
		{
			int x = (int) p.getX();
			int y = (int) p.getY();
			int sceneX = x >> 7;
			int sceneY = y >> 7;
			if (sceneX < 0 || sceneX >= wv.getSizeX() || sceneY < 0 || sceneY >= wv.getSizeY())
			{
				if (!p.isGroundClip())
				{
					outOfSceneKills++;
					p.kill();
				}
				continue;
			}
			long tileKey = ((long) sceneX << 32) | (sceneY & 0xffffffffL);

			BatchCanvas canvas;
			if (tileKey == lastKey && lastCanvas.usedSlots < slotsPerCanvas)
			{
				canvas = lastCanvas;
			}
			else
			{
				canvas = claimCanvas(tileKey, worldView, level);
				if (canvas == null)
				{
					// Canvas build failed; legacy path takes over next tick
					return;
				}
				lastKey = tileKey;
				lastCanvas = canvas;
			}
			writeSlot(canvas, canvas.usedSlots++, p, sinYaw, cosYaw, sinPitch, cosPitch);
			lastDrawnParticles++;
			lastBatchedVertices += templateVertexCount;
		}

		for (BatchCanvas canvas : canvases)
		{
			if (!canvas.claimed)
			{
				if (canvas.object.isActive())
				{
					canvas.object.setActive(false);
				}
				continue;
			}

			// Collapse slots that were written last tick but not this one
			for (int slot = canvas.usedSlots; slot < canvas.highWater; slot++)
			{
				clearSlot(canvas, slot);
			}
			canvas.highWater = canvas.usedSlots;

			canvas.object.setLocation(canvas.centerLp, level);
			if (!canvas.object.isActive())
			{
				canvas.object.setActive(true);
			}
			activeObjects++;
		}
	}

	/**
	 * Find this tick's canvas for a tile (one with free slots), claiming an
	 * idle canvas or building a new one as needed. O(1): a map resolves the
	 * tile's current canvas and the cursor hands out idle ones in pool order.
	 * Canvas count grows to the peak concurrent demand and is reused
	 * thereafter.
	 */
	private BatchCanvas claimCanvas(long tileKey, int worldView, int level)
	{
		BatchCanvas current = tileCanvases.get(tileKey);
		if (current != null && current.usedSlots < slotsPerCanvas)
		{
			return current;
		}
		BatchCanvas idle;
		if (idleCursor < canvases.size())
		{
			idle = canvases.get(idleCursor++);
		}
		else
		{
			idle = buildCanvas();
			if (idle == null)
			{
				return null;
			}
			canvases.add(idle);
			idleCursor = canvases.size();
		}

		idle.claimed = true;
		idle.tileKey = tileKey;
		idle.usedSlots = 0;
		tileCanvases.put(tileKey, idle);
		int centerX = ((int) (tileKey >> 32) << 7) + 64;
		int centerY = (((int) tileKey) << 7) + 64;
		idle.centerLp = new LocalPoint(centerX, centerY, worldView);
		idle.centerHeight = Perspective.getTileHeight(client, idle.centerLp, level);
		return idle;
	}

	private BatchCanvas buildCanvas()
	{
		ModelData[] parts = new ModelData[slotsPerCanvas];
		for (int i = 0; i < slotsPerCanvas; i++)
		{
			// Distinct whole-unit offsets so welding can't fuse slots (the
			// epsilons are sub-unit). The first eight slots pin the corners of
			// the clamp volume, so the bounds computed ONCE over this geometry
			// cover everything runtime writes are clamped to no matter how few
			// slots the mesh's budget allows - the old interior-only spread
			// silently needed 216+ slots for full coverage and asserted the
			// GPU depth sort when a heavier mesh shrank the slot count. The
			// rest spread through the interior, shifted off the corner lattice
			// so no offset ever repeats.
			int x;
			int y;
			int z;
			if (i < 8)
			{
				x = (i & 1) == 0 ? -(int) VOLUME_HORIZONTAL : (int) VOLUME_HORIZONTAL;
				y = (i & 2) == 0 ? (int) VOLUME_UP : (int) VOLUME_DOWN;
				z = (i & 4) == 0 ? -(int) VOLUME_HORIZONTAL : (int) VOLUME_HORIZONTAL;
			}
			else
			{
				int k = i + 1;
				x = -(int) VOLUME_HORIZONTAL + (k % 9) * 64;
				y = (int) VOLUME_DOWN - ((k / 9) % 24) * 60;
				z = -(int) VOLUME_HORIZONTAL + ((k / 216) % 9) * 64;
			}
			parts[i] = canvasProto.shallowCopy().cloneVertices().translate(x, y, z);
		}
		ModelData merged = client.mergeModels(parts).cloneTransparencies(true);

		if (merged.getVerticesCount() != slotsPerCanvas * templateVertexCount
			|| merged.getFaceCount() != slotsPerCanvas * templateFaceCount)
		{
			log.warn("Canvas slicing failed ({}v {}f, expected {}x{}v {}f); falling back to per-tick merging",
				merged.getVerticesCount(), merged.getFaceCount(),
				slotsPerCanvas, templateVertexCount, templateFaceCount);
			canvasModeFailed = true;
			return null;
		}
		return new BatchCanvas(merged);
	}

	/**
	 * Overwrite one slot of a canvas with a particle: billboarded vertices
	 * always; transparencies only when the slot's size or fade step changed;
	 * colors only when its style changed - colors are fade-independent, the
	 * life envelope rides entirely in transparency.
	 */
	private void writeSlot(BatchCanvas canvas, int slot, Particle p,
		float sinYaw, float cosYaw, float sinPitch, float cosPitch)
	{
		ParticleStyle style = p.getStyle();
		int size = p.getSizeVariant();
		int fade = fadeStepForParticle(p);

		// Per-particle base-size jitter: a uniform scale on the disc that rides
		// on top of the pre-baked auto-variation. Capped so growth can't push
		// the scaled disc out of the bounds volume (the un-jittered variant
		// already fits); shrinking is always safe.
		float sizeScale = p.getSizeScale();
		float variantRadius = style.getBaseSize() * 0.5f * ParticleStyle.SIZE_MULTIPLIERS[size];
		if (sizeScale > 1f && variantRadius > 1f)
		{
			sizeScale = Math.min(sizeScale, Math.max(1f, (CLAMP_MARGIN - 1f) / variantRadius));
		}

		int vertexBase = slot * templateVertexCount;
		float dx = p.getX() - canvas.centerLp.getX();
		float dy = p.getZ() - canvas.centerHeight;
		float dz = p.getY() - canvas.centerLp.getY();

		// Nudge toward the camera so garment faces that beat their neighbors
		// by render priority (a cape over a skirt) don't swallow particles
		float bias = style.getDepthBias();
		if (bias > 0)
		{
			dx += bias * sinYaw * cosPitch;
			dy -= bias * sinPitch;
			dz -= bias * cosYaw * cosPitch;
		}

		// Clamp into the bounds volume the canvas was built over; vertices
		// outside the once-computed model radius break the GPU depth sort
		dx = clamp(dx, -VOLUME_HORIZONTAL + CLAMP_MARGIN, VOLUME_HORIZONTAL - CLAMP_MARGIN);
		dy = clamp(dy, VOLUME_UP + CLAMP_MARGIN, VOLUME_DOWN - CLAMP_MARGIN);
		dz = clamp(dz, -VOLUME_HORIZONTAL + CLAMP_MARGIN, VOLUME_HORIZONTAL - CLAMP_MARGIN);

		// Velocity-aligned stretch: elongate the disc along the particle's
		// screen-projected velocity (drips read as falling streaks). Capped
		// so the stretched radius stays within CLAMP_MARGIN and thus in the
		// bounds volume. du/dv is the velocity's direction in the billboard's
		// local (x,y) plane; the model frame is X=east, Y=height, Z=north.
		float stretch = style.getStretchFactor();
		float du = 0f;
		float dv = 0f;
		if (stretch > 1f)
		{
			float discRadius = variantRadius * sizeScale * style.lifeScaleMul(p);
			if (discRadius > 1f)
			{
				stretch = Math.min(stretch, Math.max(1f, (CLAMP_MARGIN - 1f) / discRadius));
			}
			// Ramp toward the (already bounds-capped) peak over the particle's
			// life: a droplet holds its round shape while falling, then
			// elongates late as it "lets go". Late-biased so the stretch stays
			// subtle until the end. rampStart 1 = full stretch from spawn.
			float rampStart = style.getStretchRampStart();
			if (rampStart < 1f)
			{
				float ageFrac = 1f - p.displayLifeFraction();
				float r = rampStart + (1f - rampStart) * ageFrac * ageFrac;
				stretch = 1f + (stretch - 1f) * r;
			}
			if (stretch > 1f)
			{
				float vE = p.getVelX();
				float vH = p.getVelZ();
				float vN = p.getVelY();
				float u = vE * cosYaw + vN * sinYaw;
				float v = vE * sinPitch * sinYaw + vH * cosPitch - vN * sinPitch * cosYaw;
				float mag = (float) Math.sqrt(u * u + v * v);
				if (mag > 0.001f)
				{
					du = u / mag;
					dv = v / mag;
				}
			}
		}
		boolean stretched = stretch > 1f && (du != 0f || dv != 0f);

		ModelData sizeTemplate = style.getTemplates()[size][0];
		float[] sx = sizeTemplate.getVerticesX();
		float[] sy = sizeTemplate.getVerticesY();
		float[] sz = sizeTemplate.getVerticesZ();
		for (int j = 0; j < templateVertexCount; j++)
		{
			float x = sx[j] * sizeScale;
			float y = sy[j] * sizeScale;
			float z = sz[j] * sizeScale;

			if (stretched)
			{
				// Scale the component along the velocity direction, leaving
				// the perpendicular unchanged - an elongated streak
				float along = (stretch - 1f) * (x * du + y * dv);
				x += along * du;
				y += along * dv;
			}

			// Pitch: (0,0,1) -> (0, sinPitch, cosPitch)
			float y1 = y * cosPitch + z * sinPitch;
			float z1 = -y * sinPitch + z * cosPitch;

			// Yaw: (0, sp, cp) -> (-sinYaw*cp, sp, cosYaw*cp) = camera forward
			float x1 = -z1 * sinYaw + x * cosYaw;
			float z2 = z1 * cosYaw + x * sinYaw;

			canvas.vx[vertexBase + j] = x1 + dx;
			canvas.vy[vertexBase + j] = y1 + dy;
			canvas.vz[vertexBase + j] = z2 + dz;
		}

		int variant = size * ParticleStyle.FADE_STEPS + fade;
		int faceBase = slot * templateFaceCount;
		boolean gradient = style.isColorGradient();
		if (canvas.slotStyle[slot] != style)
		{
			canvas.slotStyle[slot] = style;
			canvas.slotVariant[slot] = -1;
			// Without a gradient the colour is constant over life, so copy it
			// once when the slot changes hands between styles. Unlit colors are
			// for renderers that relight, lit for the others.
			if (!gradient)
			{
				System.arraycopy(style.getTemplates()[size][0].getFaceColors(), 0,
					canvas.unlitColors, faceBase, templateFaceCount);
				System.arraycopy(style.getLitColors1()[0], 0, canvas.colors1, faceBase, templateFaceCount);
				System.arraycopy(style.getLitColors2()[0], 0, canvas.colors2, faceBase, templateFaceCount);
				System.arraycopy(style.getLitColors3()[0], 0, canvas.colors3, faceBase, templateFaceCount);
			}
		}
		if (canvas.slotVariant[slot] != variant)
		{
			canvas.slotVariant[slot] = variant;
			System.arraycopy(style.getTemplates()[size][fade].getFaceTransparencies(), 0,
				canvas.transparencies, faceBase, templateFaceCount);
			// With a gradient the colour moves each fade step, so recopy it here
			if (gradient)
			{
				System.arraycopy(style.getTemplates()[size][fade].getFaceColors(), 0,
					canvas.unlitColors, faceBase, templateFaceCount);
				System.arraycopy(style.getLitColors1()[fade], 0, canvas.colors1, faceBase, templateFaceCount);
				System.arraycopy(style.getLitColors2()[fade], 0, canvas.colors2, faceBase, templateFaceCount);
				System.arraycopy(style.getLitColors3()[fade], 0, canvas.colors3, faceBase, templateFaceCount);
			}
		}
	}

	private static float clamp(float v, float min, float max)
	{
		return v < min ? min : Math.min(v, max);
	}

	private void clearSlot(BatchCanvas canvas, int slot)
	{
		int vertexBase = slot * templateVertexCount;
		Arrays.fill(canvas.vx, vertexBase, vertexBase + templateVertexCount, 0f);
		Arrays.fill(canvas.vy, vertexBase, vertexBase + templateVertexCount, 0f);
		Arrays.fill(canvas.vz, vertexBase, vertexBase + templateVertexCount, 0f);
		int faceBase = slot * templateFaceCount;
		Arrays.fill(canvas.transparencies, faceBase, faceBase + templateFaceCount, INVISIBLE);
		canvas.slotStyle[slot] = null;
		canvas.slotVariant[slot] = -1;
	}

	/**
	 * Legacy fallback: merge and light fresh batch models every tick.
	 */
	private void syncLegacy(List<Particle> particles, WorldView wv, int worldView, int level,
		float sinYaw, float cosYaw, float sinPitch, float cosPitch)
	{
		legacyUsed = 0;

		Map<Long, List<Particle>> byTile = new HashMap<>();
		for (Particle p : particles)
		{
			int x = (int) p.getX();
			int y = (int) p.getY();
			int sceneX = x >> 7;
			int sceneY = y >> 7;
			if (sceneX < 0 || sceneX >= wv.getSizeX() || sceneY < 0 || sceneY >= wv.getSizeY())
			{
				if (!p.isGroundClip())
				{
					outOfSceneKills++;
					p.kill();
				}
				continue;
			}
			long key = ((long) sceneX << 32) | (sceneY & 0xffffffffL);
			byTile.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
			lastDrawnParticles++;
		}

		int batchSize = Math.max(1, MAX_FACES_PER_BATCH / Math.max(1, templateFaceCount));
		ModelData[] parts = new ModelData[batchSize];

		for (Map.Entry<Long, List<Particle>> entry : byTile.entrySet())
		{
			int sceneX = (int) (entry.getKey() >> 32);
			int sceneY = entry.getKey().intValue();
			int centerX = (sceneX << 7) + 64;
			int centerY = (sceneY << 7) + 64;
			LocalPoint centerLp = new LocalPoint(centerX, centerY, worldView);
			int centerHeight = Perspective.getTileHeight(client, centerLp, level);

			List<Particle> group = entry.getValue();
			for (int start = 0; start < group.size(); start += batchSize)
			{
				int n = Math.min(batchSize, group.size() - start);
				for (int i = 0; i < n; i++)
				{
					Particle p = group.get(start + i);
					ModelData md = p.getStyle().getTemplates()
						[p.getSizeVariant()][fadeStepForParticle(p)]
						.shallowCopy()
						.cloneVertices();
					float bias = p.getStyle().getDepthBias();
					transformLegacy(md, sinYaw, cosYaw, sinPitch, cosPitch,
						p.getX() - centerX + bias * sinYaw * cosPitch,
						p.getZ() - centerHeight - bias * sinPitch,
						p.getY() - centerY - bias * cosYaw * cosPitch);
					parts[i] = md;
					lastBatchedVertices += templateVertexCount;
				}

				ModelData merged = n == batchSize
					? client.mergeModels(parts)
					: client.mergeModels(Arrays.copyOf(parts, n));
				Model lit = merged.light(LIGHT_AMBIENT, LIGHT_CONTRAST,
					ModelData.DEFAULT_X, ModelData.DEFAULT_Y, ModelData.DEFAULT_Z);

				RuneLiteObject obj = nextLegacyObject();
				obj.setModel(lit);
				obj.setLocation(centerLp, level);
				obj.setOrientation(0);
			}
		}

		for (int i = legacyUsed; i < legacyPool.size(); i++)
		{
			RuneLiteObject obj = legacyPool.get(i);
			if (obj.isActive())
			{
				obj.setActive(false);
			}
		}
		activeObjects = legacyUsed;
	}

	private static void transformLegacy(ModelData md, float sinYaw, float cosYaw, float sinPitch, float cosPitch,
		float dx, float dy, float dz)
	{
		float[] xs = md.getVerticesX();
		float[] ys = md.getVerticesY();
		float[] zs = md.getVerticesZ();
		for (int i = 0; i < md.getVerticesCount(); i++)
		{
			float x = xs[i];
			float y = ys[i];
			float z = zs[i];
			float y1 = y * cosPitch + z * sinPitch;
			float z1 = -y * sinPitch + z * cosPitch;
			float x1 = -z1 * sinYaw + x * cosYaw;
			float z2 = z1 * cosYaw + x * sinYaw;
			xs[i] = x1 + dx;
			ys[i] = y1 + dy;
			zs[i] = z2 + dz;
		}
	}

	private RuneLiteObject nextLegacyObject()
	{
		RuneLiteObject obj;
		if (legacyUsed < legacyPool.size())
		{
			obj = legacyPool.get(legacyUsed);
		}
		else
		{
			obj = client.createRuneLiteObject();
			obj.setDrawFrontTilesFirst(true);
			legacyPool.add(obj);
		}
		legacyUsed++;
		if (!obj.isActive())
		{
			obj.setActive(true);
		}
		return obj;
	}

	/**
	 * Deactivate all batch objects, e.g. on logout or shutdown.
	 */
	void reset()
	{
		tileCanvases.clear();
		for (BatchCanvas canvas : canvases)
		{
			canvas.claimed = false;
			if (canvas.object.isActive())
			{
				canvas.object.setActive(false);
			}
		}
		for (RuneLiteObject obj : legacyPool)
		{
			if (obj.isActive())
			{
				obj.setActive(false);
			}
		}
		activeObjects = 0;
	}

	/**
	 * @return particles killed for leaving the scene since the last call
	 */
	int drainOutOfSceneKills()
	{
		int kills = outOfSceneKills;
		outOfSceneKills = 0;
		return kills;
	}

	private static int fadeStep(float lifeFraction)
	{
		int step = (int) ((1f - lifeFraction) * ParticleStyle.FADE_STEPS);
		return Math.max(0, Math.min(ParticleStyle.FADE_STEPS - 1, step));
	}

	private static int fadeStepForParticle(Particle p)
	{
		int fade = fadeStep(p.displayLifeFraction());
		if (p.isGroundClip())
		{
			int groundFade = (int) ((1f - p.getGroundProximityFade()) * (ParticleStyle.FADE_STEPS - 1));
			fade = Math.max(fade, groundFade);
		}
		return fade;
	}

	/**
	 * Reshape a mesh into a sphere by projecting every vertex onto a sphere of
	 * the given radius around the mesh centroid. Topology is unchanged, so any
	 * reasonably round source model becomes a clean ball. The result is centered
	 * on the model origin so particles sit exactly on their emit position.
	 */
	private static void spherify(ModelData model, float radius)
	{
		float[] xs = model.getVerticesX();
		float[] ys = model.getVerticesY();
		float[] zs = model.getVerticesZ();
		int count = model.getVerticesCount();
		if (count == 0)
		{
			return;
		}

		float cx = 0, cy = 0, cz = 0;
		for (int i = 0; i < count; i++)
		{
			cx += xs[i];
			cy += ys[i];
			cz += zs[i];
		}
		cx /= count;
		cy /= count;
		cz /= count;

		for (int i = 0; i < count; i++)
		{
			float dx = xs[i] - cx;
			float dy = ys[i] - cy;
			float dz = zs[i] - cz;
			float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (len < 0.001f)
			{
				// Degenerate vertex at the centroid; push it to the pole
				xs[i] = 0;
				ys[i] = -radius;
				zs[i] = 0;
				continue;
			}
			xs[i] = dx / len * radius;
			ys[i] = dy / len * radius;
			zs[i] = dz / len * radius;
		}
	}

	/**
	 * Squash the sphere along z into a thin lens: a billboard "quad" with the
	 * source mesh's topology. The disc spans x (horizontal) and y (vertical),
	 * facing along z.
	 */
	private static void flatten(ModelData model, float factor)
	{
		float[] zs = model.getVerticesZ();
		for (int i = 0; i < model.getVerticesCount(); i++)
		{
			zs[i] *= factor;
		}
	}

	/**
	 * Per-face alpha multiplier realizing the profile's shape as a soft mask
	 * over the disc, evaluated at each face centroid in the disc plane (x
	 * horizontal, y vertical). Default is the (1 - t^2)^2 radial glow; the
	 * others carve a silhouette while staying soft. Baked once per style.
	 */
	private static float[] shapeFalloff(ModelData model, float radius, Shape shape)
	{
		float[] xs = model.getVerticesX();
		float[] ys = model.getVerticesY();
		int[] f1 = model.getFaceIndices1();
		int[] f2 = model.getFaceIndices2();
		int[] f3 = model.getFaceIndices3();

		float[] falloff = new float[model.getFaceCount()];
		for (int f = 0; f < falloff.length; f++)
		{
			float cx = (xs[f1[f]] + xs[f2[f]] + xs[f3[f]]) / 3f;
			float cy = (ys[f1[f]] + ys[f2[f]] + ys[f3[f]]) / 3f;
			float nx = cx / radius;
			float ny = cy / radius;
			float t = Math.min(1f, (float) Math.sqrt(nx * nx + ny * ny));
			falloff[f] = maskValue(shape, nx, ny, t);
		}
		return falloff;
	}

	private static float maskValue(Shape shape, float nx, float ny, float t)
	{
		if (shape == Shape.DIAMOND)
		{
			// Geometry carries the silhouette; keep the interior a soft glow
			// (bright core, dimmer points) that never fully vanishes so the
			// warped points stay visible
			return 0.35f + 0.65f * (1f - t * t);
		}
		float a = 1f - t * t;
		return a * a;
	}

	/**
	 * Reposition the flattened disc's vertices into a four-point diamond by
	 * scaling each vertex's radius by a function of its angle (peaks on the
	 * axes). Topology is unchanged so the batch canvas is unaffected; Default
	 * is left round. x is horizontal, y vertical.
	 */
	private static void shapeWarp(ModelData model, float radius, Shape shape)
	{
		if (shape != Shape.DIAMOND)
		{
			return;
		}
		float[] xs = model.getVerticesX();
		float[] ys = model.getVerticesY();
		int count = model.getVerticesCount();
		for (int i = 0; i < count; i++)
		{
			float x = xs[i];
			float y = ys[i];
			if (x * x + y * y < 0.000001f)
			{
				continue;
			}
			// Four peaks on the axes; square sharpens the points
			float p = 0.5f + 0.5f * (float) Math.cos(4.0 * Math.atan2(y, x));
			float f = 0.3f + 0.7f * p * p;
			xs[i] = x * f;
			ys[i] = y * f;
		}
	}
}
