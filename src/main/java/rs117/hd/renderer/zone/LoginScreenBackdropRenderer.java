package rs117.hd.renderer.zone;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import org.lwjgl.BufferUtils;
import rs117.hd.HdPlugin;
import rs117.hd.opengl.shader.LoginPanoramaShaderProgram;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.ShaderIncludes;
import rs117.hd.utils.Camera;
import rs117.hd.utils.jobs.GenericJob;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Displays the panorama captured by {@link PanoramaCapture} as a slowly-rotating background behind the login
 * screen, Minecraft-menu-style - a fullscreen-triangle shader samples the captured horizon faces (held in a
 * texture array) based on view direction, with a plain sky-colour gradient above/below the captured range (see
 * login_panorama_frag.glsl).
 * <p>
 * This replaces an earlier approach that stitched together cached live-scene chunk geometry across regions into
 * a flyable 3D reconstruction - that turned out to be fragile (region placement bugs, caching gaps, LOD/alpha
 * mismatches) because 117HD's live render path can't be invoked standalone with a custom camera, so there was no
 * way to verify the reconstruction against full-quality rendering. A captured panorama sidesteps all of that: it's
 * just whatever the live renderer already drew, saved as an image.
 */
@Slf4j
@Singleton
public class LoginScreenBackdropRenderer {
	private static final int RESOLUTION_WIDTH = 1920;
	private static final int RESOLUTION_HEIGHT = 1080;

	// A full revolution roughly every 2 minutes - slow enough to read as "ambient background", not a spinning room.
	private static final float ROTATION_RADIANS_PER_SECOND = (float) (2 * Math.PI / 120);
	// A slight downward tilt, similar to Minecraft's menu panorama, rather than looking dead level.
	private static final float PANORAMA_PITCH = 0.15f;

	// Matches PanoramaCapture.CAPTURED_FOV_FILE - kept here too so this class doesn't need to depend on the
	// exact field layout of PanoramaCapture beyond its public CACHE_DIR/NUM_FACES constants.
	private static final String CAPTURED_FOV_FILE_NAME = "fov.txt";
	private static final float DEFAULT_CAPTURED_HALF_FOV_RADIANS = (float) Math.toRadians(20);

	@Inject
	private ClientThread clientThread;

	@Inject
	private HdPlugin plugin;

	@Inject
	private LoginPanoramaShaderProgram panoramaProgram;

	private int fbo, colorTex, depthRbo, emptyVao, faceTexArray;
	private boolean glResourcesReady;

	private boolean panoramaRequested;
	private boolean contentLoaded;
	private long lastFrameNanos;
	private float panoramaYaw;
	// Half the actual horizontal (and, since faces are cropped square, vertical) field of view each face was
	// captured with, read back from what PanoramaCapture recorded at capture time - using the real value here
	// instead of assuming each face fills its whole 90-degree slot is what keeps the display from stretching a
	// narrow-FOV capture across a much wider angle (which read as badly blurred/low quality).
	private float capturedHalfFovRadians = DEFAULT_CAPTURED_HALF_FOV_RADIANS;

	public boolean isReady() {
		return contentLoaded;
	}

	public int getTextureId() {
		return contentLoaded ? colorTex : 0;
	}

	public void startUp() {}

	public void initializeShaders(ShaderIncludes includes) throws ShaderException, IOException {
		panoramaProgram.compile(includes.define("PANORAMA_NUM_FACES", PanoramaCapture.NUM_FACES));
	}

	public void destroyShaders() {
		panoramaProgram.destroy();
	}

	/**
	 * Kicks off loading the panorama captured by {@link PanoramaCapture} the first time it's called; subsequent
	 * calls within the same client session are no-ops, since the result is cached for as long as the plugin is
	 * running. A no-op (repeatable on a later login screen visit) if nothing's been captured yet.
	 */
	public void requestBackdrop() {
		if (panoramaRequested)
			return;
		panoramaRequested = true;

		GenericJob.build("LoginScreenBackdropRenderer::loadPanorama", task -> {
			BufferedImage[] images = new BufferedImage[PanoramaCapture.NUM_FACES];
			for (int i = 0; i < PanoramaCapture.NUM_FACES; i++) {
				File file = PanoramaCapture.CACHE_DIR.resolve("face_" + i).setExtension("png").toFile();
				if (!file.exists()) {
					log.debug("No cached panorama available yet for the login screen");
					panoramaRequested = false;
					return;
				}
				try {
					images[i] = ImageIO.read(file);
				} catch (IOException ex) {
					log.warn("Failed to read panorama face {}:", i, ex);
					panoramaRequested = false;
					return;
				}
			}

			float halfFov = DEFAULT_CAPTURED_HALF_FOV_RADIANS;
			File fovFile = PanoramaCapture.CACHE_DIR.resolve(CAPTURED_FOV_FILE_NAME).toFile();
			if (fovFile.exists()) {
				try {
					halfFov = Float.parseFloat(Files.readString(fovFile.toPath(), StandardCharsets.UTF_8).trim());
				} catch (IOException | NumberFormatException ex) {
					log.warn("Failed to read captured panorama FOV, falling back to a default guess:", ex);
				}
			}

			float finalHalfFov = halfFov;
			clientThread.invoke(() -> uploadFaceTextures(images, finalHalfFov));
		}).queue(false);
	}

	private void uploadFaceTextures(BufferedImage[] images, float halfFovRadians) {
		ensureGlResources();
		capturedHalfFovRadians = halfFovRadians;

		int w = images[0].getWidth(), h = images[0].getHeight();
		glBindTexture(GL_TEXTURE_2D_ARRAY, faceTexArray);
		glTexImage3D(
			GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8, w, h, PanoramaCapture.NUM_FACES, 0, GL_RGBA, GL_UNSIGNED_BYTE,
			(ByteBuffer) null
		);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

		for (int i = 0; i < images.length; i++) {
			BufferedImage img = images[i];
			// All faces are cropped identically in PanoramaCapture, so this should always hold - skip any that
			// don't rather than letting a mismatched layer corrupt the texture array upload.
			if (img.getWidth() != w || img.getHeight() != h) {
				log.warn("Panorama face {} is {}x{}, expected {}x{} - skipping", i, img.getWidth(), img.getHeight(), w, h);
				continue;
			}

			int[] pixels = img.getRGB(0, 0, w, h, null, 0, w); // row 0 = top of the captured image
			ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
			for (int p : pixels) {
				buf.put((byte) ((p >> 16) & 0xFF));
				buf.put((byte) ((p >> 8) & 0xFF));
				buf.put((byte) (p & 0xFF));
				buf.put((byte) 0xFF);
			}
			buf.flip();

			glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, i, w, h, 1, GL_RGBA, GL_UNSIGNED_BYTE, buf);
		}
		glBindTexture(GL_TEXTURE_2D_ARRAY, 0);

		contentLoaded = true;
		log.debug(
			"Loaded {} panorama face(s) for the login screen, captured half-FOV {} degrees",
			images.length, Math.toDegrees(halfFovRadians)
		);
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

		faceTexArray = glGenTextures();

		// The fullscreen-triangle vertex shader only reads gl_VertexID, no vertex attributes - but a VAO still
		// needs to be bound for glDrawArrays to be valid.
		emptyVao = glGenVertexArrays();

		glResourcesReady = true;
	}

	/**
	 * Draws the current frame of the rotating panorama into its offscreen texture. A no-op until the capture has
	 * finished loading.
	 */
	public void renderFrame() {
		if (!contentLoaded)
			return;

		long now = System.nanoTime();
		float dt = lastFrameNanos == 0 ? 0 : Math.min(0.1f, (now - lastFrameNanos) / 1_000_000_000f);
		lastFrameNanos = now;
		panoramaYaw += dt * ROTATION_RADIANS_PER_SECOND;

		// Position doesn't matter for a skybox-style panorama sampled purely by direction - only orientation does.
		camera.setOrthographic(false);
		camera.setPosition(0, 0, 0);
		camera.setOrientation(new float[] { panoramaYaw, PANORAMA_PITCH });
		camera.setViewportWidth(RESOLUTION_WIDTH);
		camera.setViewportHeight(RESOLUTION_HEIGHT);
		// Matches the real captured FOV exactly (tan(halfFovX) = frustumWidth/2, same relationship
		// PanoramaCapture.computeCapturedHalfFovRadians derives from) rather than an arbitrary wide angle - with
		// FACE_ANGLE_STEP apart faces, a wider display FOV than this shows multiple adjacent faces stitched
		// across the screen at once (which read as "repeated" since nearby faces often capture similar-looking
		// nearby scenery), instead of a single direction at a time like actually turning around on the spot.
		float desiredFrustumWidth = 2f * (float) Math.tan(capturedHalfFovRadians);
		camera.setZoom(RESOLUTION_WIDTH / desiredFrustumWidth);
		camera.setNearPlane(0.5f);
		camera.setFarPlane(100f);

		int prevFbo = glGetInteger(GL_FRAMEBUFFER_BINDING);
		int[] prevViewport = new int[4];
		glGetIntegerv(GL_VIEWPORT, prevViewport);

		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		glViewport(0, 0, RESOLUTION_WIDTH, RESOLUTION_HEIGHT);
		glDisable(GL_DEPTH_TEST);
		glDisable(GL_BLEND);
		glDisable(GL_CULL_FACE);

		plugin.uboGlobal.invProjectionMatrix.set(camera.getInvViewProjMatrix());
		plugin.uboGlobal.upload();

		panoramaProgram.use();
		panoramaProgram.uniCapturedHalfFovRadians.set(capturedHalfFovRadians);
		glActiveTexture(GL_TEXTURE0);
		glBindTexture(GL_TEXTURE_2D_ARRAY, faceTexArray);

		glBindVertexArray(emptyVao);
		glDrawArrays(GL_TRIANGLES, 0, 3);
		glBindVertexArray(0);

		int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
		if (status != GL_FRAMEBUFFER_COMPLETE)
			log.warn("Login panorama FBO incomplete: 0x{}", Integer.toHexString(status));
		HdPlugin.checkGLErrors();

		glBindFramebuffer(GL_FRAMEBUFFER, prevFbo);
		glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
	}

	private final Camera camera = new Camera();

	public void destroy() {
		if (fbo != 0)
			glDeleteFramebuffers(fbo);
		if (colorTex != 0)
			glDeleteTextures(colorTex);
		if (depthRbo != 0)
			glDeleteRenderbuffers(depthRbo);
		if (faceTexArray != 0)
			glDeleteTextures(faceTexArray);
		if (emptyVao != 0)
			glDeleteVertexArrays(emptyVao);

		fbo = colorTex = depthRbo = emptyVao = faceTexArray = 0;
		glResourcesReady = false;
		contentLoaded = false;
		panoramaRequested = false;
	}
}
