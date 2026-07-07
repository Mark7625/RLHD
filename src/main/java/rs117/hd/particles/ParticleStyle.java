package rs117.hd.particles;

import java.util.Set;
import javax.annotation.Nullable;
import lombok.Getter;
import net.runelite.api.ModelData;

/**
 * Resolved runtime style for one emitter piece: pre-baked disc templates for
 * every (size variant, fade step) plus the emission parameters. Built by
 * {@link ParticleRenderer} from an {@link EmitterProfile}; immutable once built.
 */
@Getter
class ParticleStyle
{
	static final int FADE_STEPS = 12;
	/**
	 * Per-particle random size multipliers; this fixed spread makes the cloud
	 * read as organic instead of uniform beads. The profile's size jitter is a
	 * separate, per-particle scaling of the base that rides on top of these.
	 */
	static final float[] SIZE_MULTIPLIERS = {0.65f, 1.0f, 1.4f};
	/**
	 * The lowest particle size we allow; also the floor of the jitter range.
	 */
	static final int MIN_SIZE = 2;

	/**
	 * Unlit disc templates indexed [size variant][fade step], facing +z,
	 * centered on the origin.
	 */
	private final ModelData[][] templates;
	/**
	 * Pre-lit per-face colors indexed [fade step], copied into batch canvases
	 * in place of per-tick relighting. Colour is constant across a particle's
	 * life unless a colour gradient is set, so without one every fade step
	 * holds the same array and the batch copies it just once per style.
	 */
	private final int[][] litColors1;
	private final int[][] litColors2;
	private final int[][] litColors3;
	/**
	 * The colour changes over life (end colour differs from start), so the
	 * batch must recopy the lit colours each fade step, not once per style.
	 */
	private final boolean colorGradient;
	private final float lifetimeSec;
	private final float particlesPerSecond;
	/**
	 * Particles per tile of anchor movement; 0 = off. See EmitterProfile.
	 */
	private final float trailDensity;
	private final float riseSpeed;
	private final float spreadSpeed;
	/**
	 * Per-particle random size spread around the base, in local units; 0 = every
	 * particle the base size. Realized as a per-particle scale at spawn, on top
	 * of the fixed {@link #SIZE_MULTIPLIERS} auto-variation. See EmitterProfile.
	 */
	private final int sizeJitter;
	/**
	 * Downward acceleration in local units per second squared; see
	 * EmitterProfile.
	 */
	private final float gravity;
	/**
	 * Steady world-aligned wind drift in local units per second (X east,
	 * Y north, Z up); see EmitterProfile.
	 */
	private final float windX;
	private final float windY;
	private final float windZ;
	/**
	 * Fraction of a particle's own velocity shed per second (0 = none); see
	 * EmitterProfile.
	 */
	private final float dragPerSec;
	/**
	 * Radial spawn velocity from the emitter centroid, outward (+) / inward (-),
	 * local units per second; see EmitterProfile.
	 */
	private final float vortex;
	/**
	 * Emit-point scale about the centroid as a multiplier (1 = unchanged); see
	 * EmitterProfile.
	 */
	private final float emitScale;
	/**
	 * Billboard elongation factor along screen velocity (1 = round); see
	 * EmitterProfile.
	 */
	private final float stretchFactor;
	/**
	 * Life fraction at which stretch begins as a multiplier (1 = full stretch
	 * from spawn, 0 = starts round and ramps up over life). See EmitterProfile.
	 */
	private final float stretchRampStart;
	/**
	 * The profile's base diameter; the renderer needs it to cap the stretch
	 * so an elongated billboard stays inside the batch bounds volume.
	 */
	private final int baseSize;
	private final float spawnJitter;
	private final int featherStrength;
	/**
	 * Midpoints inserted between mesh-adjacent emitter vertices;
	 * see EmitterProfile.
	 */
	private final int interpolation;
	/**
	 * Camera-ward render nudge in local units; see EmitterProfile.
	 */
	private final float depthBias;
	/**
	 * Lifetime multiplier while the wearer walks or runs; see EmitterProfile.
	 */
	private final float movementLifetimeScale;
	/**
	 * Fixed emit offset from the vertex in model space, applied before the
	 * player's facing rotation so it turns with them. X sideways, Y
	 * forward/back, Z up (positive).
	 */
	private final float offsetX;
	private final float offsetY;
	private final float offsetZ;
	private final Set<Integer> animationIds;
	/**
	 * Flattened [start0, end0, start1, end1, ...] frame windows for action
	 * animation matches, or null for all frames.
	 */
	private final int[] animFrameRanges;
	private final int startArgb;
	private final int endArgb;
	private final float colorFadeStart;
	@Nullable
	private final String textureFile;
	private final int flipbookColumns;
	private final int flipbookRows;
	@Nullable
	private final String flipbookMode;
	private final float rotationSpeed;
	private final boolean useEnvironmentLight;
	private final boolean uniformColorVariation;
	private final float scaleStartMul;
	private final float scaleEndMul;

	ParticleStyle(ModelData[][] templates, int[][] litColors1, int[][] litColors2, int[][] litColors3,
		ParticleDefinition definition, EmitterProfile emitter)
	{
		this.templates = templates;
		this.litColors1 = litColors1;
		this.litColors2 = litColors2;
		this.litColors3 = litColors3;
		this.colorGradient = definition.isColorFade() && definition.getColorEnd() != definition.getColor();
		this.startArgb = definition.getColor();
		this.endArgb = definition.getColorEnd();
		this.colorFadeStart = Math.max(0, Math.min(100, definition.getColorFadeStart())) / 100f;
		this.lifetimeSec = definition.getLifetimeMs() / 1000f;
		this.particlesPerSecond = definition.getParticlesPerSecond();
		this.trailDensity = definition.getTrailDensity();
		this.riseSpeed = definition.getRiseSpeed();
		this.spreadSpeed = definition.getSpreadSpeed();
		this.sizeJitter = Math.max(0, definition.getSizeJitter());
		this.gravity = definition.getGravity();
		this.windX = definition.getWindX();
		this.windY = definition.getWindY();
		this.windZ = definition.getWindZ();
		this.dragPerSec = Math.max(0, definition.getDrag()) / 100f;
		this.vortex = definition.getVortex();
		this.emitScale = Math.max(0, emitter.getEmitScale()) / 100f;
		this.stretchFactor = 1f + Math.max(0, definition.getStretch()) / 100f;
		this.stretchRampStart = 1f - Math.max(0, Math.min(100, definition.getStretchRamp())) / 100f;
		this.baseSize = definition.getSize();
		this.spawnJitter = definition.getSpawnJitter();
		this.featherStrength = emitter.getFeatherStrength();
		this.interpolation = Math.max(0, Math.min(4, emitter.getInterpolation()));
		this.depthBias = emitter.getDepthBias();
		this.movementLifetimeScale = Math.max(10, Math.min(100, definition.getMovementLifetime())) / 100f;
		this.offsetX = emitter.getOffsetX();
		this.offsetY = emitter.getOffsetY();
		this.offsetZ = emitter.getOffsetZ();
		this.animationIds = Set.copyOf(emitter.getAnimationIds());
		this.animFrameRanges = parseFrameRanges(emitter.getAnimFrames());
		String tex = definition.getTextureFile();
		this.textureFile = tex == null || tex.isEmpty() ? null : tex;
		this.flipbookColumns = Math.max(0, definition.getFlipbookColumns());
		this.flipbookRows = Math.max(0, definition.getFlipbookRows());
		String mode = definition.getFlipbookMode();
		this.flipbookMode = mode == null || mode.isEmpty() ? null : mode;
		this.rotationSpeed = definition.getRotationSpeed();
		this.useEnvironmentLight = definition.isUseEnvironmentLight();
		this.uniformColorVariation = definition.isUniformColorVariation();
		this.scaleStartMul = Math.max(0, definition.getScaleStartPercent()) / 100f;
		this.scaleEndMul = Math.max(0, definition.getScaleEndPercent()) / 100f;
	}

	boolean hasFlipbook()
	{
		return flipbookColumns > 0 && flipbookRows > 0 && flipbookMode != null;
	}

	int getFlipbookFrameCount()
	{
		return flipbookColumns * flipbookRows;
	}

	float getGpuFlipbookFrame(Particle p)
	{
		if (!hasFlipbook())
		{
			return 0f;
		}
		if ("random".equalsIgnoreCase(flipbookMode) && p.getFlipbookFrame() >= 0)
		{
			return 1f + p.getFlipbookFrame();
		}
		return 1f - p.lifeFraction();
	}

	/**
	 * Parse "9-13, 15-19, 25" into flattened start/end pairs; single numbers
	 * become one-frame windows, junk tokens are ignored.
	 *
	 * @return null when no valid ranges (= all frames pass)
	 */
	private static int[] parseFrameRanges(String text)
	{
		if (text == null || text.isEmpty())
		{
			return null;
		}
		java.util.List<Integer> bounds = new java.util.ArrayList<>();
		for (String token : text.split(","))
		{
			try
			{
				String[] parts = token.trim().split("-", 2);
				if (parts[0].isEmpty())
				{
					continue;
				}
				int start = Integer.parseInt(parts[0].trim());
				int end = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : start;
				bounds.add(Math.min(start, end));
				bounds.add(Math.max(start, end));
			}
			catch (NumberFormatException ignored)
			{
			}
		}
		if (bounds.isEmpty())
		{
			return null;
		}
		int[] ranges = new int[bounds.size()];
		for (int i = 0; i < ranges.length; i++)
		{
			ranges[i] = bounds.get(i);
		}
		return ranges;
	}

	/**
	 * The frame-window check alone, for spot anim gating where the graphic
	 * ID itself already gates. No windows = every frame passes.
	 */
	boolean frameMatches(int frame)
	{
		if (animFrameRanges == null)
		{
			return true;
		}
		for (int i = 0; i < animFrameRanges.length; i += 2)
		{
			if (frame >= animFrameRanges[i] && frame <= animFrameRanges[i + 1])
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Animation gate: emit only while the player's action or pose animation
	 * matches, with optional frame windows on action matches. An empty gate
	 * always passes; live particles are unaffected and fade out naturally.
	 */
	boolean animationMatches(int actionAnimation, int actionFrame, int poseAnimation)
	{
		if (animationIds.isEmpty())
		{
			return true;
		}
		if (animationIds.contains(actionAnimation))
		{
			if (animFrameRanges == null)
			{
				return true;
			}
			for (int i = 0; i < animFrameRanges.length; i += 2)
			{
				if (actionFrame >= animFrameRanges[i] && actionFrame <= animFrameRanges[i + 1])
				{
					return true;
				}
			}
			return false;
		}
		return animationIds.contains(poseAnimation);
	}

	float getGpuRadius(Particle p) {
		float sizeScale = p.getSizeScale();
		float variantRadius = baseSize * 0.5f * SIZE_MULTIPLIERS[p.getSizeVariant()];
		if (sizeScale > 1f && variantRadius > 1f) {
			sizeScale = Math.min(sizeScale, Math.max(1f, 55f / variantRadius));
		}
		return variantRadius * sizeScale * lifeScaleMul(p);
	}

	float lifeScaleMul(Particle p) {
		float ageFrac = p.renderAgeFraction();
		return scaleStartMul + (scaleEndMul - scaleStartMul) * ageFrac;
	}

	void writeGpuColor(Particle p, float[] out) {
		float ageFrac = p.renderAgeFraction();
		float envelope = (float) Math.pow(Math.sin(Math.PI * ageFrac), 0.8);
		int argb;
		if (p.getSpawnColorArgb() != 0)
		{
			argb = p.getSpawnColorArgb();
		}
		else
		{
			float fadeSpan = Math.max(0.001f, 1f - colorFadeStart);
			float fade = colorGradient ? Math.max(0f, Math.min(1f, (ageFrac - colorFadeStart) / fadeSpan)) : 0f;
			argb = colorGradient ? lerpArgb(startArgb, endArgb, fade) : startArgb;
		}
		out[0] = ((argb >> 16) & 0xFF) / 255f;
		out[1] = ((argb >> 8) & 0xFF) / 255f;
		out[2] = (argb & 0xFF) / 255f;
		out[3] = (((argb >>> 24) & 0xFF) / 255f) * envelope;
		if (p.isGroundClip())
		{
			out[3] *= p.getGroundProximityFade();
		}
	}

	private static int lerpArgb(int a, int b, float t) {
		int aa = (a >>> 24) & 0xFF, ar = (a >>> 16) & 0xFF, ag = (a >>> 8) & 0xFF, ab = a & 0xFF;
		int ba = (b >>> 24) & 0xFF, br = (b >>> 16) & 0xFF, bg = (b >>> 8) & 0xFF, bb = b & 0xFF;
		int oa = Math.round(aa + (ba - aa) * t);
		int or = Math.round(ar + (br - ar) * t);
		int og = Math.round(ag + (bg - ag) * t);
		int ob = Math.round(ab + (bb - ab) * t);
		return (oa << 24) | (or << 16) | (og << 8) | ob;
	}
}
