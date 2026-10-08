package rs117.hd.renderer.zone;

import java.awt.Image;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.ClientTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.Keybind;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.ui.DrawManager;
import rs117.hd.HdPlugin;
import rs117.hd.utils.ResourcePath;
import rs117.hd.utils.jobs.GenericJob;

/**
 * Captures a horizontal-ring panorama centered on wherever the player is standing when triggered, for use as the
 * login screen's background - the equivalent of Minecraft's menu panorama, but built from real steered-camera
 * screenshots rather than reconstructed geometry, since 117HD's live render path can't be invoked standalone with
 * a custom camera/FBO (see the login screen backdrop work this replaces: it pieced together cached chunk geometry
 * across regions, which turned out to be fragile - wrong placement, caching gaps, LOD mismatches).
 * <p>
 * {@link #NUM_FACES} directions are captured, not a full cube: RuneLite's camera API has no position or zoom
 * setter (only {@link Client#setCameraYawTarget}/{@link Client#setCameraPitchTarget}, which steer the normal
 * gameplay camera), and that camera's pitch is gameplay-clamped - it physically can't point straight up or down.
 * The login screen is expected to pair this horizon ring with 117HD's existing procedural sky for the upper
 * hemisphere instead of a captured top face.
 * <p>
 * NUM_FACES needs to be high enough that each real captured FOV (usually well under 90 degrees - RuneLite exposes
 * no zoom setter, so this depends entirely on whatever zoom the player happened to have) actually overlaps with
 * its neighbours, or there's a gap of real un-photographed space between every pair of faces that no amount of
 * blending can turn into continuous content - an earlier 4-face version (90 degrees apart) had exactly this
 * problem, showing two unrelated photos meeting at a hard seam. 12 faces (30 degrees apart) gives a comfortable
 * overlap margin against most normal play zoom levels.
 */
@Slf4j
@Singleton
public class PanoramaCapture implements KeyListener {
	private static final Keybind KEY_CAPTURE_PANORAMA =
		new Keybind(KeyEvent.VK_F9, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);

	// Shared with LoginScreenBackdropRenderer, which reads these same files back to display on the login screen.
	public static final ResourcePath CACHE_DIR = HdPlugin.PLUGIN_DIR.resolve("panorama-cache");
	public static final int NUM_FACES = 12;
	// Half the real captured horizontal (and vertical, since each face is cropped square) field of view, in
	// radians - read back by LoginScreenBackdropRenderer so it can display each face at its actual captured
	// angular size instead of guessing/stretching it across the full 90-degree slot between face directions.
	public static final String CAPTURED_FOV_FILE_NAME = "fov.txt";

	// Camera yaw uses a 16384-unit full circle, NOT the 2048-unit system entity/object orientation uses (confirmed
	// against the deobfuscated client: the int-to-radians conversion applied to camera yaw multiplies by
	// 3.834952E-4, which is exactly 2*PI/16384). Using 2048 here meant the 4 "90-degrees-apart" targets were
	// actually only spanning about 34 degrees total, clustered instead of spread around the full compass.
	private static final int YAW_FULL_CIRCLE = 16384;
	// Scaled up from the old 2048-based tolerance of 4 (which was ~0.7 degrees) to keep the same real-world
	// settle tolerance on the finer 16384 scale.
	private static final int YAW_SETTLE_TOLERANCE = 32;
	// Safety valve only - normal camera steering settles within a handful of ticks. Without this, a capture
	// started right as the client loses focus/the camera gets stuck would wait forever instead of giving up.
	private static final int MAX_SETTLE_TICKS = 50;
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private KeyManager keyManager;

	@Inject
	private DrawManager drawManager;

	@Inject
	private EventBus eventBus;

	@Inject
	private HdPlugin plugin;

	private boolean capturing;
	private boolean waitingForFrame;
	private int currentDirectionIndex;
	private int settleTicksWaited;
	private int capturePitchTarget;
	private float capturedHalfFovRadians;
	private List<BufferedImage> captured = new ArrayList<>();

	public void startUp() {
		keyManager.registerKeyListener(this);
		eventBus.register(this);
	}

	public void destroy() {
		keyManager.unregisterKeyListener(this);
		eventBus.unregister(this);
		// In case the plugin is stopped mid-capture, don't leave the UI permanently hidden.
		plugin.hideUiForCapture = false;
		capturing = false;
		waitingForFrame = false;
	}

	@Override
	public void keyPressed(KeyEvent e) {
		if (KEY_CAPTURE_PANORAMA.matches(e) && !capturing)
			clientThread.invoke(this::beginCapture);
	}

	@Override
	public void keyReleased(KeyEvent e) {}

	@Override
	public void keyTyped(KeyEvent e) {}

	private void beginCapture() {
		if (client.getGameState() != GameState.LOGGED_IN)
			return;

		log.info("Starting login screen panorama capture at the player's current position...");
		capturing = true;
		plugin.hideUiForCapture = true;
		waitingForFrame = false;
		captured = new ArrayList<>(NUM_FACES);
		currentDirectionIndex = 0;
		// Whatever the player's current pitch happens to be, kept consistent across all 4 faces so the horizon
		// lines up between them - there's no API to force an exact "horizontal" pitch value.
		capturePitchTarget = client.getCameraPitch();
		capturedHalfFovRadians = computeCapturedHalfFovRadians();
		steerToCurrentDirection();
	}

	/**
	 * Replicates 117HD's own zoom-to-FOV relationship (frustumWidthAtNear = viewportPixels / zoom, in
	 * {@code Mat4.perspective}) using whatever zoom the player had set at the moment of capture, since RuneLite
	 * exposes no way to force a specific zoom/FOV - this is what lets the login screen display the capture at its
	 * real angular size instead of guessing, which was stretching it across too wide an angle and looking blurry.
	 * <p>
	 * {@code Mat4.perspective(w, h, n, f)} sets matrix[0][0] = 2/w where w = viewportWidthPixels/zoom, and for a
	 * symmetric frustum matrix[0][0] = 1/tan(halfFovX) - so tan(halfFovX) = w/2 directly. The near plane distance
	 * only affects the matrix's depth terms, not this X/Y framing, so it must not appear in this formula at all
	 * (an earlier version incorrectly divided by it too, producing a near-zero FOV and a mostly-blank capture).
	 */
	private float computeCapturedHalfFovRadians() {
		int size = Math.min(client.getViewportWidth(), client.getViewportHeight());
		float zoom = client.get3dZoom();
		if (size <= 0 || zoom <= 0)
			return (float) Math.toRadians(20);
		return (float) Math.atan(size / zoom / 2.0);
	}

	private void steerToCurrentDirection() {
		int yawTarget = yawTargetForDirection(currentDirectionIndex);
		client.setCameraYawTarget(yawTarget);
		client.setCameraPitchTarget(capturePitchTarget);
		settleTicksWaited = 0;
	}

	private static int yawTargetForDirection(int index) {
		return (index * YAW_FULL_CIRCLE / NUM_FACES) % YAW_FULL_CIRCLE;
	}

	@Subscribe
	public void onClientTick(ClientTick event) {
		if (!capturing || waitingForFrame)
			return;

		int yawTarget = yawTargetForDirection(currentDirectionIndex);
		int yawDiff = angleDiff(client.getCameraYaw(), yawTarget);
		settleTicksWaited++;
		if (Math.abs(yawDiff) > YAW_SETTLE_TOLERANCE && settleTicksWaited < MAX_SETTLE_TICKS)
			return;

		waitingForFrame = true;
		drawManager.requestNextFrameListener(image -> {
			BufferedImage face = cropToGameViewportSquare(image);
			clientThread.invoke(() -> onFaceCaptured(face));
		});
	}

	private void onFaceCaptured(BufferedImage face) {
		if (face != null)
			captured.add(face);

		waitingForFrame = false;
		currentDirectionIndex++;
		if (currentDirectionIndex >= NUM_FACES) {
			capturing = false;
			plugin.hideUiForCapture = false;
			List<BufferedImage> faces = captured;
			captured = new ArrayList<>();
			log.info("Panorama capture complete, saving {} face(s)", faces.size());
			saveToDiskAsync(faces, capturedHalfFovRadians);
			return;
		}

		steerToCurrentDirection();
	}

	private static int angleDiff(int a, int b) {
		int diff = (a - b) % YAW_FULL_CIRCLE;
		if (diff < -YAW_FULL_CIRCLE / 2)
			diff += YAW_FULL_CIRCLE;
		if (diff > YAW_FULL_CIRCLE / 2)
			diff -= YAW_FULL_CIRCLE;
		return diff;
	}

	/**
	 * Crops to a square from the real 3D game viewport specifically (not the whole window), so side panels or
	 * other UI chrome docked next to the game view don't throw off the centering, and so the crop's pixel size
	 * matches exactly what {@link #computeCapturedHalfFovRadians} assumed it would.
	 */
	private BufferedImage cropToGameViewportSquare(Image image) {
		if (!(image instanceof BufferedImage))
			return null;
		BufferedImage src = (BufferedImage) image;

		int vx = client.getViewportXOffset();
		int vy = client.getViewportYOffset();
		int vw = client.getViewportWidth();
		int vh = client.getViewportHeight();
		if (vw <= 0 || vh <= 0)
			return null;

		int size = Math.min(vw, vh);
		int x = vx + (vw - size) / 2;
		int y = vy + (vh - size) / 2;
		x = Math.max(0, Math.min(x, src.getWidth() - size));
		y = Math.max(0, Math.min(y, src.getHeight() - size));
		return src.getSubimage(x, y, size, size);
	}

	private void saveToDiskAsync(List<BufferedImage> faces, float halfFovRadians) {
		GenericJob.build("PanoramaCapture::save", task -> {
			try {
				CACHE_DIR.mkdirs();
				for (int i = 0; i < faces.size(); i++) {
					File out = CACHE_DIR.resolve("face_" + i).setExtension("png").toFile();
					ImageIO.write(faces.get(i), "png", out);
				}
				Files.writeString(
					CACHE_DIR.resolve(CAPTURED_FOV_FILE_NAME).toFile().toPath(),
					Float.toString(halfFovRadians),
					StandardCharsets.UTF_8
				);
				log.info("Saved {} panorama face(s) to {}", faces.size(), CACHE_DIR);
			} catch (IOException ex) {
				log.warn("Failed to save panorama capture:", ex);
			}
		}).queue(false);
	}
}
