package rs117.hd.renderer.zone;

import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import org.lwjgl.system.MemoryStack;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.opengl.uniforms.UBOGlobal;
import rs117.hd.opengl.uniforms.UBOLights;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.scene.environments.Environment;
import rs117.hd.utils.Camera;
import rs117.hd.utils.Mat4;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.renderer.zone.ZoneRenderer.TEXTURE_UNIT_TEXTURED_FACES;

/**
 * Renders a cached {@link BackdropZoneCache} region into an offscreen texture for display behind the login
 * screen, entirely outside the normal scene lifecycle - there is no live {@link rs117.hd.scene.SceneContext} or
 * player camera at the login screen, so this builds its own minimal standalone free-flying camera and reuses the
 * existing zone opaque geometry + the regular scene shader with a set of fixed, scene-independent lighting
 * defaults.
 * <p>
 * WASD moves, Space/Shift moves vertically, the arrow keys look around, and holding the middle mouse button
 * while moving the mouse also looks around, while the login screen is showing.
 */
@Slf4j
@Singleton
public class LoginScreenBackdropRenderer implements net.runelite.client.input.KeyListener, net.runelite.client.input.MouseListener {
	private static final int RESOLUTION_WIDTH = 1920;
	private static final int RESOLUTION_HEIGHT = 1080;
	private static final int REGION_SIZE_CHUNKS = 8;
	private static final int CHUNK_WORLD_UNITS = 1024;
	private static final float MOVE_UNITS_PER_SECOND = 900f;
	private static final float LOOK_RADIANS_PER_SECOND = 1.6f;
	private static final float MOUSE_LOOK_RADIANS_PER_PIXEL = 0.004f;
	private static final float MIN_HEIGHT = 50f;
	private static final float MAX_HEIGHT = 10000f;
	// Kept small (rather than 0) so the camera can pull back slightly from the exact edge of the cached area
	// without clipping into geometry, but small enough that flying around can't wander off into the empty void
	// past the edge of what's actually loaded.
	private static final float BOUNDS_MARGIN = 300f;

	@Inject
	private BackdropZoneCache backdropZoneCache;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ZoneRenderer zoneRenderer;

	@Inject
	private KeyManager keyManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	private EnvironmentManager environmentManager;

	private int fbo, colorTex, depthRbo;
	private boolean glResourcesReady;
	private boolean inputRegistered;

	private boolean regionRequested;
	private boolean contentLoaded;
	private long lastFrameNanos;

	private final List<Zone> zones = new ArrayList<>();
	private final List<int[]> zoneBases = new ArrayList<>();
	private final Camera camera = new Camera();

	private final Set<Integer> heldKeys = ConcurrentHashMap.newKeySet();

	// Guards the middle-mouse-drag look state, which is written from the AWT event thread and consumed once per
	// render frame on the client/GL thread.
	private final Object mouseLock = new Object();
	private boolean middleMouseDown;
	private int lastMouseX, lastMouseY;
	private float pendingMouseYawDelta, pendingMousePitchDelta;

	private float camX, camY, camZ, camYaw, camPitch;
	private float minBoundX, maxBoundX, minBoundZ, maxBoundZ;

	public boolean isReady() {
		return contentLoaded;
	}

	public int getTextureId() {
		return contentLoaded ? colorTex : 0;
	}

	public void startUp() {
		if (inputRegistered)
			return;
		keyManager.registerKeyListener(this);
		mouseManager.registerMouseListener(this);
		inputRegistered = true;
	}

	// 5x5 block of regions (40x40 chunks, ~40960x40960 world units) around whichever region was picked, so
	// there's enough room to actually fly around in rather than just one region's worth of terrain.
	private static final int REGION_LOAD_RADIUS = 2;

	/**
	 * Kicks off region discovery and loading the first time it's called; subsequent calls within the same
	 * client session are no-ops, since the result is cached for as long as the plugin is running.
	 */
	public void requestBackdrop() {
		if (regionRequested)
			return;
		regionRequested = true;

		int[] configuredRegion = parseConfiguredRegion();
		if (configuredRegion != null) {
			loadRegionBlock(configuredRegion[0], configuredRegion[1]);
			return;
		}

		backdropZoneCache.findRegionForLoginScreen((regionX, regionZ) -> {
			if (regionX == Integer.MIN_VALUE) {
				log.debug("No cached backdrop regions available yet for the login screen");
				return;
			}
			loadRegionBlock(regionX, regionZ);
		});
	}

	@javax.annotation.Nullable
	private int[] parseConfiguredRegion() {
		String value = config.backdropLoginScreenRegion();
		if (value == null || value.isBlank())
			return null;
		String[] parts = value.split(",");
		if (parts.length != 2)
			return null;
		try {
			return new int[] { Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()) };
		} catch (NumberFormatException ex) {
			log.warn("Invalid login screen backdrop region '{}', expected \"regionX,regionZ\"", value);
			return null;
		}
	}

	private void loadRegionBlock(int regionX, int regionZ) {
		List<int[]> regionsToLoad = new ArrayList<>();
		for (int rx = regionX - REGION_LOAD_RADIUS; rx <= regionX + REGION_LOAD_RADIUS; rx++)
			for (int rz = regionZ - REGION_LOAD_RADIUS; rz <= regionZ + REGION_LOAD_RADIUS; rz++)
				regionsToLoad.add(new int[] { rx, rz });

		int[] remaining = { regionsToLoad.size() };
		for (int[] r : regionsToLoad) {
			backdropZoneCache.loadRegionForLoginScreen(r[0], r[1], () -> {
				if (--remaining[0] == 0)
					onRegionsLoaded(regionsToLoad);
			});
		}
	}

	/**
	 * Draws the current frame of the backdrop into its offscreen texture, using whatever camera position the
	 * player has flown to since the login screen was shown. A no-op until the cached region has finished loading.
	 */
	private long lastDebugLogNanos;
	private long lastPosLogNanos;
	private long lastEnvLogNanos;

	public void renderFrame() {
		if (!contentLoaded)
			return;

		long now = System.nanoTime();
		float dt = lastFrameNanos == 0 ? 0 : Math.min(0.1f, (now - lastFrameNanos) / 1_000_000_000f);
		lastFrameNanos = now;

		updateCameraFromInput(dt);
		renderBackdrop();

		if (now - lastPosLogNanos > 1_000_000_000L) {
			lastPosLogNanos = now;
			log.debug(
				"Login backdrop frame: pos=({}, {}, {}) yaw={} pitch={} heldKeys={}",
				camX, camY, camZ, camYaw, camPitch, heldKeys
			);
		}
	}

	private void onRegionsLoaded(List<int[]> regions) {
		zones.clear();
		zoneBases.clear();

		// Global (absolute) chunk coordinates throughout, rather than region-local 0-7 ones, so multiple
		// regions' chunks all place correctly relative to each other instead of overlapping at the origin.
		int minCx = Integer.MAX_VALUE, maxCx = Integer.MIN_VALUE;
		int minCz = Integer.MAX_VALUE, maxCz = Integer.MIN_VALUE;
		long sumCx = 0, sumCz = 0;
		for (int[] r : regions) {
			int regionZoneCount = 0, regionMinCx = Integer.MAX_VALUE, regionMaxCx = Integer.MIN_VALUE;
			int regionMinCz = Integer.MAX_VALUE, regionMaxCz = Integer.MIN_VALUE;
			boolean[] czRowPresent = new boolean[REGION_SIZE_CHUNKS];
			for (int localCx = 0; localCx < REGION_SIZE_CHUNKS; localCx++) {
				for (int localCz = 0; localCz < REGION_SIZE_CHUNKS; localCz++) {
					int cx = r[0] * REGION_SIZE_CHUNKS + localCx;
					int cz = r[1] * REGION_SIZE_CHUNKS + localCz;
					Zone zone = backdropZoneCache.get(cx, cz);
					if (zone == null || zone.levelOffsets == null)
						continue;
					zones.add(zone);
					zoneBases.add(new int[] { cx * CHUNK_WORLD_UNITS, cz * CHUNK_WORLD_UNITS });
					minCx = Math.min(minCx, cx);
					maxCx = Math.max(maxCx, cx);
					minCz = Math.min(minCz, cz);
					maxCz = Math.max(maxCz, cz);
					sumCx += cx;
					sumCz += cz;
					regionZoneCount++;
					regionMinCx = Math.min(regionMinCx, cx);
					regionMaxCx = Math.max(regionMaxCx, cx);
					regionMinCz = Math.min(regionMinCz, cz);
					regionMaxCz = Math.max(regionMaxCz, cz);
					czRowPresent[localCz] = true;
				}
			}
			// Definitive proof of which screen-space direction a given region's data ends up placed in: baseX is
			// literally cx * CHUNK_WORLD_UNITS, so a region's regionX directly and monotonically determines its
			// baseX - there is no sign flip or axis swap anywhere between here and the vertex shader. If a
			// region with a higher regionX (e.g. 50 vs the configured region's 49) doesn't show the highest
			// regionMinCx/regionMaxCx/baseX range of the batch, the bug is upstream of this file (most likely in
			// which world position a chunk's data was captured/tagged with inside BackdropZoneCache).
			// czRowPresent shows which of the 8 local chunk rows (south->north within this region) actually have
			// cached data at all, regardless of localCx - a "false" in the middle of an otherwise-cached region,
			// or at the edge bordering the next region's cached area, is a real hole in the walked/captured data
			// rather than a placement bug, since nothing else in this file treats rows differently.
			StringBuilder rows = new StringBuilder();
			for (int i = 0; i < REGION_SIZE_CHUNKS; i++)
				rows.append(czRowPresent[i] ? '#' : '.');
			log.debug(
				"Login backdrop region ({}, {}): {} zone(s), cx range [{}, {}], cz range [{}, {}], cz rows [{}]",
				r[0], r[1], regionZoneCount, regionMinCx, regionMaxCx, regionMinCz, regionMaxCz, rows
			);
		}

		if (zones.isEmpty()) {
			// Nothing usable in this particular region - allow a future login screen visit to try again,
			// in case more has been cached by then.
			regionRequested = false;
			return;
		}

		// The camera aims at the centroid of the actually-loaded chunks, not the midpoint of their bounding box -
		// cached chunks are whatever the player happened to walk through, often an uneven/lopsided cluster rather
		// than a filled rectangle, so the bounding-box midpoint can land over a gap with no geometry at all,
		// pushing all the real terrain up to one edge of the frame.
		float centerX = ((sumCx / (float) zones.size()) + 0.5f) * CHUNK_WORLD_UNITS;
		float centerZ = ((sumCz / (float) zones.size()) + 0.5f) * CHUNK_WORLD_UNITS;
		float spanX = (maxCx - minCx + 1) * CHUNK_WORLD_UNITS;
		float spanZ = (maxCz - minCz + 1) * CHUNK_WORLD_UNITS;

		// Based on the actual chunk bounding box, not the centroid above, so the clamp stays correct regardless
		// of where the centroid falls within it.
		minBoundX = minCx * CHUNK_WORLD_UNITS - BOUNDS_MARGIN;
		maxBoundX = (maxCx + 1) * CHUNK_WORLD_UNITS + BOUNDS_MARGIN;
		minBoundZ = minCz * CHUNK_WORLD_UNITS - BOUNDS_MARGIN;
		maxBoundZ = (maxCz + 1) * CHUNK_WORLD_UNITS + BOUNDS_MARGIN;

		// Start elevated and pulled back from the centre of the cached area, angled down towards it. Yaw 0 looks
		// toward -Z (see Camera's view matrix convention), so sit on the +Z side of the centre to look back at it.
		// Pitch is derived from the actual camera-to-target geometry (not a guessed constant) so the target is
		// guaranteed to land in frame instead of the frustum missing it by however far a fixed guess was off by.
		// Capped to the same bound the free camera is clamped to, so the clamp below never silently moves the
		// camera away from the position this pitch was actually computed for.
		float startDist = Math.min(Math.max(Math.max(spanX, spanZ) * 0.6f, 600f), spanZ / 2f + BOUNDS_MARGIN);
		float startHeight = 900f;
		camX = centerX;
		// OSRS's native height axis grows DOWNWARD (confirmed against the real client source: ground is a
		// *smaller* number than the space above it), and 117HD's vertex data carries that same convention
		// through unchanged from the game's own tile/model heights - so "elevated above the target" means a
		// *negative* camY here, not positive. This was the root cause behind "camera blocked going up" (moving
		// camY positive was driving the camera further down/into the terrain, not away from it) and the
		// persistent bad framing even after the gap-filler and UV fixes.
		camY = -startHeight;
		// Verified against the actual Mat4.rotateX/rotateY matrices (not assumed formulas): at yaw=0, the
		// camera's screen-right direction is world +X, which is east (regionX increases eastward - confirmed
		// against BackdropZoneCache's chunkX, which comes straight from scene.getBaseX() with no sign flip).
		// A previous attempt flipped this to yaw=PI believing the camera needed to face the opposite way, but
		// that puts +X/east on screen-*left* instead - confirmed wrong by two independent landmark checks
		// (region 50/east rendering on the left, and the region 49,54 circle - which has no east offset of its
		// own - rendering on the right because the camera's centroid-based aim point gets pulled east by how
		// much more data region 50 has cached, putting the circle slightly west of the aim point).
		camZ = centerZ + startDist;
		camYaw = 0f;
		// Reuses the same validated aim-at-target formula (tan(pitch) = camY / startDist) - it naturally picks
		// up the correct sign now that camY itself is signed correctly, no separate pitch-sign fix needed.
		camPitch = (float) Math.atan2(camY, startDist) + 0.25f;

		lastFrameNanos = 0;
		contentLoaded = true;
		log.debug(
			"Login backdrop: {} zone(s), center=({}, {}), span=({}, {}), cam start=({}, {}, {}) yaw={} pitch={}",
			zones.size(), centerX, centerZ, spanX, spanZ, camX, camY, camZ, camYaw, camPitch
		);
	}

	private void updateCameraFromInput(float dt) {
		if (dt <= 0)
			return;

		if (held(KeyEvent.VK_LEFT))
			camYaw -= LOOK_RADIANS_PER_SECOND * dt;
		if (held(KeyEvent.VK_RIGHT))
			camYaw += LOOK_RADIANS_PER_SECOND * dt;
		if (held(KeyEvent.VK_UP))
			camPitch = Math.max(-1.5f, camPitch - LOOK_RADIANS_PER_SECOND * dt);
		if (held(KeyEvent.VK_DOWN))
			camPitch = Math.min(1.5f, camPitch + LOOK_RADIANS_PER_SECOND * dt);

		float mouseYawDelta, mousePitchDelta;
		synchronized (mouseLock) {
			mouseYawDelta = pendingMouseYawDelta;
			mousePitchDelta = pendingMousePitchDelta;
			pendingMouseYawDelta = 0;
			pendingMousePitchDelta = 0;
		}
		camYaw += mouseYawDelta;
		camPitch = Math.max(-1.5f, Math.min(1.5f, camPitch + mousePitchDelta));

		camera.setOrientation(new float[] { camYaw, camPitch });
		float[] forward = camera.getForwardDirection();
		float flatLen = (float) Math.sqrt(forward[0] * forward[0] + forward[2] * forward[2]);
		float fx = flatLen > 0.0001f ? forward[0] / flatLen : 0;
		float fz = flatLen > 0.0001f ? forward[2] / flatLen : -1;
		// Right-hand perpendicular of the flattened forward vector, in the XZ plane.
		float rx = fz;
		float rz = -fx;

		float move = MOVE_UNITS_PER_SECOND * dt;
		if (held(KeyEvent.VK_W)) {
			camX += fx * move;
			camZ += fz * move;
		}
		if (held(KeyEvent.VK_S)) {
			camX -= fx * move;
			camZ -= fz * move;
		}
		if (held(KeyEvent.VK_D)) {
			camX += rx * move;
			camZ += rz * move;
		}
		if (held(KeyEvent.VK_A)) {
			camX -= rx * move;
			camZ -= rz * move;
		}
		// Height grows downward (see the comment in onRegionLoaded), so Space (up) moves camY negative.
		if (held(KeyEvent.VK_SPACE))
			camY -= move;
		if (held(KeyEvent.VK_SHIFT))
			camY += move;

		camX = Math.max(minBoundX, Math.min(maxBoundX, camX));
		camZ = Math.max(minBoundZ, Math.min(maxBoundZ, camZ));
		camY = Math.max(-MAX_HEIGHT, Math.min(-MIN_HEIGHT, camY));
	}

	private boolean held(int keyCode) {
		return heldKeys.contains(keyCode);
	}

	private void ensureGlResources() {
		if (glResourcesReady)
			return;

		fbo = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fbo);

		colorTex = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, colorTex);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, RESOLUTION_WIDTH, RESOLUTION_HEIGHT, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTex, 0);

		depthRbo = glGenRenderbuffers();
		glBindRenderbuffer(GL_RENDERBUFFER, depthRbo);
		glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, RESOLUTION_WIDTH, RESOLUTION_HEIGHT);
		glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depthRbo);

		glBindFramebuffer(GL_FRAMEBUFFER, 0);
		glResourcesReady = true;
	}

	private void renderBackdrop() {
		ensureGlResources();

		// Mat4.perspective()'s "viewport width/height" aren't pixel counts - they're the frustum's extent at the
		// near plane, with field of view coming entirely from how large they are relative to nearPlane. Passing
		// the raw 1920x1080 FBO resolution with zoom=1 made the frustum microscopically narrow (everything
		// outside a near-zero-width sliver got clipped away) - zoom must scale pixels down to that frustum size,
		// the same way the live game camera derives it from client.get3dZoom().
		float desiredFrustumWidth = 1.4f; // ~70 degree horizontal FOV at nearPlane below
		camera.setOrthographic(false);
		camera.setPosition(camX, camY, camZ);
		camera.setOrientation(new float[] { camYaw, camPitch });
		camera.setViewportWidth(RESOLUTION_WIDTH);
		camera.setViewportHeight(RESOLUTION_HEIGHT);
		camera.setZoom(RESOLUTION_WIDTH / desiredFrustumWidth);
		camera.setNearPlane(50f);
		camera.setFarPlane(20000f);

		int prevFbo = glGetInteger(GL_FRAMEBUFFER_BINDING);
		int[] prevViewport = new int[4];
		glGetIntegerv(GL_VIEWPORT, prevViewport);

		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		glViewport(0, 0, RESOLUTION_WIDTH, RESOLUTION_HEIGHT);
		glColorMask(true, true, true, true);
		glDepthMask(true);
		glClearDepth(1.0);
		glClearColor(0.53f, 0.70f, 0.85f, 1f);
		glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

		glEnable(GL_DEPTH_TEST);
		glDepthFunc(GL_LESS);
		glDisable(GL_BLEND);
		glDisable(GL_CULL_FACE);

		populateGlobalUniforms();

		if (System.nanoTime() - lastDebugLogNanos > 1_000_000_000L) {
			float[] vp = camera.getViewProjMatrix();
			float[] clip = new float[4];
			float testX = minBoundX + (maxBoundX - minBoundX) / 2f;
			float testZ = minBoundZ + (maxBoundZ - minBoundZ) / 2f;
			Mat4.mulVec(clip, vp, new float[] { testX, 0f, testZ, 1f });
			float groundNdcY = clip[3] != 0 ? clip[1] / clip[3] : Float.NaN;
			float[] clipHigh = new float[4];
			Mat4.mulVec(clipHigh, vp, new float[] { testX, 500f, testZ, 1f });
			float highNdcY = clipHigh[3] != 0 ? clipHigh[1] / clipHigh[3] : Float.NaN;
			log.debug(
				"Login backdrop height test: ground(y=0) ndc.y={}, elevated(y=500) ndc.y={} (elevated should be {} on screen than ground if height isn't inverted)",
				groundNdcY, highNdcY, "HIGHER (more positive / closer to top)"
			);
		}

		zoneRenderer.sceneProgram.use();

		int totalVerts = 0, drawnZones = 0;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			for (int i = 0; i < zones.size(); i++) {
				Zone zone = zones.get(i);
				int[] base = zoneBases.get(i);
				writeZoneMetadata(stack, zone, base[0], base[1]);

				int vertexCount = vertexCount(zone);
				if (vertexCount <= 0 || zone.glVao == 0 || zone.tboF == null)
					continue;

				glBindVertexArray(zone.glVao);
				glActiveTexture(TEXTURE_UNIT_TEXTURED_FACES);
				glBindTexture(GL_TEXTURE_BUFFER, zone.tboF.getTexId());
				glDrawArrays(GL_TRIANGLES, 0, vertexCount);
				totalVerts += vertexCount;
				drawnZones++;
			}
		}

		if (System.nanoTime() - lastDebugLogNanos > 1_000_000_000L) {
			lastDebugLogNanos = System.nanoTime();
			log.debug("Login backdrop draw: {}/{} zones drawn, {} total vertices", drawnZones, zones.size(), totalVerts);
		}

		glBindVertexArray(0);
		glDisable(GL_DEPTH_TEST);

		drawDebugHud();

		int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
		if (status != GL_FRAMEBUFFER_COMPLETE)
			log.warn("Login backdrop FBO incomplete: 0x{}", Integer.toHexString(status));
		HdPlugin.checkGLErrors();

		glBindFramebuffer(GL_FRAMEBUFFER, prevFbo);
		glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
	}

	/**
	 * A crude but robust camera position/orientation indicator drawn with plain glScissor + glClear rectangles -
	 * no shader or font rendering needed, just something guaranteed-visible that proves whether the camera is
	 * actually responding to input, independent of whatever is or isn't showing up in the 3D view itself.
	 */
	private void drawDebugHud() {
		int barHeight = 28;
		int barY = RESOLUTION_HEIGHT - barHeight;
		int segmentWidth = RESOLUTION_WIDTH / 3;

		glEnable(GL_SCISSOR_TEST);
		fillRect(0, barY, RESOLUTION_WIDTH, barHeight, 0.08f, 0.08f, 0.08f);
		drawHudMarker(camX, minBoundX, maxBoundX, 0, barY, segmentWidth, barHeight, 1f, 0.2f, 0.2f);
		drawHudMarker(camY, -MAX_HEIGHT, -MIN_HEIGHT, segmentWidth, barY, segmentWidth, barHeight, 0.2f, 1f, 0.2f);
		drawHudMarker(camZ, minBoundZ, maxBoundZ, segmentWidth * 2, barY, segmentWidth, barHeight, 0.2f, 0.5f, 1f);
		glDisable(GL_SCISSOR_TEST);
	}

	private static void drawHudMarker(float value, float min, float max, int x, int y, int w, int h, float r, float g, float b) {
		float t = max > min ? (value - min) / (max - min) : 0.5f;
		t = Math.max(0f, Math.min(1f, t));
		int markerWidth = 8;
		int markerX = x + (int) (t * (w - markerWidth));
		fillRect(markerX, y, markerWidth, h, r, g, b);
	}

	private static void fillRect(int x, int y, int w, int h, float r, float g, float b) {
		glScissor(x, y, w, h);
		glClearColor(r, g, b, 1f);
		glClear(GL_COLOR_BUFFER_BIT);
	}

	// 128 local units per tile (Perspective.LOCAL_TILE_SIZE) - converts this standalone camera's local-scale
	// position back to real world tile coordinates, so the actual Area/Environment definitions (which are
	// expressed in world tile coordinates) can be matched against it.
	private static final float LOCAL_TILE_SIZE = 128f;

	// Well under UBOLights.MAX_LIGHTS (1000) - caps the per-frame sort/upload cost and the fragment shader's
	// per-pixel light loop, since a wide 5x5-region flythrough can easily have far more cached lights in memory
	// than are useful (or affordable) to actually light every pixel with at once.
	private static final int MAX_RENDERED_LIGHTS = 256;

	/**
	 * Gathers the static point lights {@link BackdropZoneCache} captured for each currently-loaded zone, keeps
	 * only the ones nearest the camera (up to {@link #MAX_RENDERED_LIGHTS}), and uploads them to
	 * {@link HdPlugin#uboLights}. Returns the number of lights uploaded, for {@code uboGlobal.pointLightsCount}.
	 */
	private int populatePointLights() {
		List<float[]> candidates = new ArrayList<>();
		for (int i = 0; i < zones.size(); i++) {
			Zone zone = zones.get(i);
			if (zone.lights == null || zone.lights.length == 0)
				continue;
			int[] base = zoneBases.get(i);
			for (int o = 0; o + 8 <= zone.lights.length; o += 8) {
				float wx = base[0] + zone.lights[o];
				float wy = zone.lights[o + 1];
				float wz = base[1] + zone.lights[o + 2];
				float dx = wx - camX, dy = wy - camY, dz = wz - camZ;
				candidates.add(new float[] {
					wx, wy, wz,
					zone.lights[o + 3], zone.lights[o + 4], zone.lights[o + 5], // color
					zone.lights[o + 6], zone.lights[o + 7], // radius, strength
					dx * dx + dy * dy + dz * dz,
				});
			}
		}

		if (candidates.isEmpty())
			return 0;

		candidates.sort(Comparator.comparingDouble(c -> c[8]));

		int count = Math.min(candidates.size(), Math.min(MAX_RENDERED_LIGHTS, UBOLights.MAX_LIGHTS));
		float[] pos = new float[4];
		float[] col = new float[4];
		for (int i = 0; i < count; i++) {
			float[] c = candidates.get(i);
			float radius = c[6], strength = c[7];
			pos[0] = c[0];
			pos[1] = c[1];
			pos[2] = c[2];
			pos[3] = radius * radius;
			col[0] = c[3] * strength;
			col[1] = c[4] * strength;
			col[2] = c[5] * strength;
			col[3] = 0f;
			plugin.uboLights.setLight(i, pos, col);
		}
		plugin.uboLights.upload();
		return count;
	}

	private void populateGlobalUniforms() {
		UBOGlobal g = plugin.uboGlobal;

		int envWorldX = (int) (camX / LOCAL_TILE_SIZE), envWorldZ = (int) (camZ / LOCAL_TILE_SIZE);
		Environment environment = environmentManager.findEnvironmentAt(envWorldX, envWorldZ, 0);
		long envLogNow = System.nanoTime();
		if (envLogNow - lastEnvLogNanos > 1_000_000_000L) {
			lastEnvLogNanos = envLogNow;
			log.debug(
				"Login backdrop environment: key={} area={} at world tile ({}, {})",
				environment.key, environment.area, envWorldX, envWorldZ
			);
		}

		g.orthographicProjection.set(0);
		g.expandedMapLoadingChunks.set(0);
		g.drawDistance.set(10000f);
		g.colorBlindnessIntensity.set(0f);
		g.gammaCorrection.set(1f);
		g.saturation.set(1f);
		g.contrast.set(1f);
		g.colorFilterPrevious.set(0);
		g.colorFilter.set(0);
		g.colorFilterFade.set(1f);
		g.viewportSize.set(RESOLUTION_WIDTH, RESOLUTION_HEIGHT);
		g.sceneResolution.set(RESOLUTION_WIDTH, RESOLUTION_HEIGHT);
		g.ambientColor.set(environment.getAmbientColor());
		g.ambientStrength.set(environment.ambientStrength);
		g.lightColor.set(environment.getDirectionalColor());
		g.lightStrength.set(environment.directionalStrength);
		g.underglowColor.set(environment.getUnderglowColor());
		g.underglowStrength.set(environment.underglowStrength);
		g.useFog.set(1);
		g.fogDepth.set(environment.fogDepth);
		g.fogColor.set(environment.getFogColor());
		g.groundFogStart.set(environment.groundFogStart);
		g.groundFogEnd.set(environment.groundFogEnd);
		g.groundFogOpacity.set(environment.groundFogOpacity);

		// Approximated by simple brightness scaling rather than the live renderer's HSV-value-scaled derivation
		// (SkyRenderer#updateGlobalUbo) - visually close enough for a background, without the colour-space
		// round-tripping that derivation relies on.
		float[] waterColor = environment.getWaterColor();
		g.waterColorLight.set(waterColor[0] * 0.8f, waterColor[1] * 0.8f, waterColor[2] * 0.8f);
		g.waterColorMid.set(waterColor[0] * 0.45f, waterColor[1] * 0.45f, waterColor[2] * 0.45f);
		g.waterColorDark.set(waterColor[0] * 0.05f, waterColor[1] * 0.05f, waterColor[2] * 0.05f);

		g.sceneBase.set(0, 0);
		g.underwaterEnvironment.set(0);
		g.underwaterCaustics.set(0);
		g.underwaterCausticsColor.set(0f, 0f, 0f);
		g.underwaterCausticsStrength.set(0f);

		// Sun direction derived from the environment's shadowAngles (altitude, azimuth), the same conversion
		// SkyManager#anglesToSkyDirection uses, negated since lightDir is the direction light travels (away
		// from the sun) rather than the direction toward it.
		float[] shadowAngles = environment.getShadowAngles();
		float altitude = shadowAngles[0], azimuth = shadowAngles[1];
		float cosAltitude = (float) Math.cos(altitude);
		float sx = (float) Math.sin(azimuth) * cosAltitude;
		float sy = (float) Math.sin(altitude);
		float sz = (float) Math.cos(azimuth) * cosAltitude;
		g.lightDir.set(-sx, -sy, -sz);

		g.pointLightsCount.set(populatePointLights());
		g.cameraPos.set(camX, camY, camZ);
		g.viewMatrix.set(camera.getViewMatrix());
		// TEST: a rotation (yaw) alone can never produce a true mirror image - it can only face the wrong way.
		// Since a single region's own internal layout was reported as mirrored (not just regions in the wrong
		// relative spot), that points to an actual reflection somewhere in this standalone camera's pipeline
		// rather than a yaw sign issue. This forces a clip-space X flip to test that theory directly - revert
		// to `camera.getViewProjMatrix()` alone if this doesn't fix it, since then the cause is elsewhere.
		float[] viewProj = camera.getViewProjMatrix();
		float[] mirrored = Mat4.scale(-1, 1, 1);
		Mat4.mul(mirrored, viewProj);
		g.projectionMatrix.set(mirrored);
		g.invProjectionMatrix.set(Mat4.inverse(mirrored));
		g.lightProjectionMatrix.set(Mat4.identity());
		g.invLightProjectionMatrix.set(Mat4.identity());
		g.shadowBiasScale.set(0f);
		g.shadowDrawDistance.set(0f);
		g.castsShadows.set(0);
		g.lightningBrightness.set(0f);
		g.elapsedTime.set(0f);
		g.upload();
	}

	/**
	 * Floors 0-3 plus {@link Zone#LEVEL_TERRAIN} are contiguous at the start of the buffer, followed by
	 * {@link Zone#LEVEL_WATER_SURFACE} and {@link Zone#LEVEL_GAP_FILLER} - geometry meant to mask height gaps
	 * between zones from constrained normal-gameplay camera angles, which looks like inverted/duplicated
	 * buildings hanging in mid-air from the free-flying angles this camera allows. The existing live-gameplay
	 * backdrop renderer (ZoneRenderer#drawBackdropZones) excludes both the same way, for the same reason.
	 */
	private static int vertexCount(Zone zone) {
		if (zone.levelOffsets == null || zone.levelOffsets.length <= Zone.LEVEL_TERRAIN)
			return 0;
		return zone.levelOffsets[Zone.LEVEL_TERRAIN] * 4 / Zone.VERT_SIZE;
	}

	private static void writeZoneMetadata(MemoryStack stack, Zone zone, int baseX, int baseZ) {
		if (zone.vboM == null)
			return;
		IntBuffer buf = stack.mallocInt(5)
			.put(0)
			.put(baseX).put(baseZ)
			.put(1)
			.put(0);
		buf.flip();
		zone.vboM.upload(buf);
	}

	@Override
	public boolean isEnabledOnLoginScreen() {
		return true;
	}

	@Override
	public void keyTyped(KeyEvent e) {
	}

	@Override
	public void keyPressed(KeyEvent e) {
		heldKeys.add(e.getKeyCode());
	}

	@Override
	public void keyReleased(KeyEvent e) {
		heldKeys.remove(e.getKeyCode());
	}

	@Override
	public void focusLost() {
		heldKeys.clear();
		synchronized (mouseLock) {
			middleMouseDown = false;
		}
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent e) {
		return e;
	}

	@Override
	public MouseEvent mousePressed(MouseEvent e) {
		if (e.getButton() == MouseEvent.BUTTON2) {
			synchronized (mouseLock) {
				middleMouseDown = true;
				lastMouseX = e.getX();
				lastMouseY = e.getY();
			}
		}
		return e;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent e) {
		if (e.getButton() == MouseEvent.BUTTON2) {
			synchronized (mouseLock) {
				middleMouseDown = false;
			}
		}
		return e;
	}

	@Override
	public MouseEvent mouseEntered(MouseEvent e) {
		return e;
	}

	@Override
	public MouseEvent mouseExited(MouseEvent e) {
		synchronized (mouseLock) {
			middleMouseDown = false;
		}
		return e;
	}

	@Override
	public MouseEvent mouseDragged(MouseEvent e) {
		synchronized (mouseLock) {
			if (middleMouseDown) {
				int dx = e.getX() - lastMouseX;
				int dy = e.getY() - lastMouseY;
				lastMouseX = e.getX();
				lastMouseY = e.getY();
				pendingMouseYawDelta += dx * MOUSE_LOOK_RADIANS_PER_PIXEL;
				pendingMousePitchDelta += dy * MOUSE_LOOK_RADIANS_PER_PIXEL;
			}
		}
		return e;
	}

	@Override
	public MouseEvent mouseMoved(MouseEvent e) {
		return e;
	}

	public void destroy() {
		if (inputRegistered) {
			keyManager.unregisterKeyListener(this);
			mouseManager.unregisterMouseListener(this);
			inputRegistered = false;
		}
		heldKeys.clear();

		if (fbo != 0)
			glDeleteFramebuffers(fbo);
		if (colorTex != 0)
			glDeleteTextures(colorTex);
		if (depthRbo != 0)
			glDeleteRenderbuffers(depthRbo);
		fbo = colorTex = depthRbo = 0;
		glResourcesReady = false;
		contentLoaded = false;
		regionRequested = false;
		zones.clear();
		zoneBases.clear();
	}
}
