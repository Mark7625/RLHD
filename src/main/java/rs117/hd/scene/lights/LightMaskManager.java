package rs117.hd.scene.lights;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.callback.ClientThread;
import rs117.hd.scene.TextureManager;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_LIGHT_MASKS;
import static rs117.hd.utils.MathUtils.clamp;
import static rs117.hd.utils.ResourcePath.path;

@Slf4j
@Singleton
public class LightMaskManager {
	public static final int MASK_SIZE = 256;
	private static final String LIGHT_MASK_PREFIX = "light_masks/";
	private static final ResourcePath TEXTURE_PATH = Props
		.getFolder("rlhd.texture-path", () -> path(TextureManager.class, "textures"));

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private TextureManager textureManager;

	private int texLightMaskArray;
	private final Map<String, Integer> lightMaskLayers = new LinkedHashMap<>();
	private ScheduledFuture<?> debounce;

	public void startUp() {
		TEXTURE_PATH.watch((path, first) -> {
			if (first || !path.toPosixPath().contains(LIGHT_MASK_PREFIX))
				return;
			String maskName = path.setExtension(null).getFilename();
			if (maskName.isEmpty() || !lightMaskLayers.containsKey(maskName))
				return;
			log.debug("Light mask changed: {}", path);
			if (debounce == null || debounce.cancel(false) || debounce.isDone())
				debounce = executor.schedule(() -> clientThread.invoke(() -> reloadMask(maskName)), 100, TimeUnit.MILLISECONDS);
		});
	}

	public void shutDown() {
		if (texLightMaskArray != 0)
			glDeleteTextures(texLightMaskArray);
		texLightMaskArray = 0;
		lightMaskLayers.clear();
	}

	public void rebuildFromDefinitions(Iterable<LightDefinition> definitions) {
		var maskNames = new LinkedHashSet<String>();
		for (LightDefinition def : definitions) {
			if (def.mask != null && !def.mask.isBlank())
				maskNames.add(def.mask);
		}
		rebuildLightMasks(maskNames);
		for (LightDefinition def : definitions)
			resolveMask(def);
	}

	public void rebuildLightMasks(Set<String> maskNames) {
		assert client.isClientThread();
		lightMaskLayers.clear();
		if (texLightMaskArray != 0) {
			glDeleteTextures(texLightMaskArray);
			texLightMaskArray = 0;
		}

		var ordered = new ArrayList<>(maskNames);
		int layerCount = Math.max(1, ordered.size());
		int[] size = { MASK_SIZE, MASK_SIZE };
		texLightMaskArray = glGenTextures();
		glActiveTexture(TEXTURE_UNIT_LIGHT_MASKS);
		glBindTexture(GL_TEXTURE_2D_ARRAY, texLightMaskArray);
		glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8, size[0], size[1], layerCount, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

		int layer = 0;
		for (String maskName : ordered) {
			lightMaskLayers.put(maskName, layer);
			var image = loadMaskImage(maskName);
			if (image != null)
				uploadLayer(layer++, image, size);
			else
				lightMaskLayers.remove(maskName);
		}

		if (ordered.isEmpty())
			uploadLayer(0, new BufferedImage(size[0], size[1], BufferedImage.TYPE_INT_ARGB), size);

		log.debug("Loaded {} light masks", lightMaskLayers.size());
	}

	public int getLightMaskLayer(@Nullable String maskName) {
		if (maskName == null || maskName.isBlank())
			return -1;
		Integer layer = lightMaskLayers.get(maskName);
		if (layer == null) {
			log.warn("Unknown light mask '{}'", maskName);
			return -1;
		}
		return layer;
	}

	public void resolveMask(LightDefinition def) {
		def.maskLayer = getLightMaskLayer(def.mask);
		if (def.maskScale <= 0)
			def.maskScale = 1;
	}

	public void bind() {
		if (texLightMaskArray == 0)
			return;
		glActiveTexture(TEXTURE_UNIT_LIGHT_MASKS);
		glBindTexture(GL_TEXTURE_2D_ARRAY, texLightMaskArray);
	}

	private void reloadMask(String maskName) {
		Integer layer = lightMaskLayers.get(maskName);
		if (layer == null)
			return;
		var image = loadMaskImage(maskName);
		if (image == null)
			return;
		glActiveTexture(TEXTURE_UNIT_LIGHT_MASKS);
		glBindTexture(GL_TEXTURE_2D_ARRAY, texLightMaskArray);
		uploadLayer(layer, image, new int[] { MASK_SIZE, MASK_SIZE });
	}

	@Nullable
	private BufferedImage loadMaskImage(String maskName) {
		if ("circle".equals(maskName))
			return createCircleMask();
		return textureManager.loadTexture(LIGHT_MASK_PREFIX + maskName);
	}

	private void uploadLayer(int layer, BufferedImage image, int[] size) {
		textureManager.uploadTexture(GL_TEXTURE_2D_ARRAY, layer, size, image, false);
	}

	private static BufferedImage createCircleMask() {
		var image = new BufferedImage(MASK_SIZE, MASK_SIZE, BufferedImage.TYPE_INT_ARGB);
		float center = (MASK_SIZE - 1) * 0.5f;
		float radius = center;
		for (int y = 0; y < MASK_SIZE; y++) {
			for (int x = 0; x < MASK_SIZE; x++) {
				float dist = (float) Math.hypot(x - center, y - center) / radius;
				float value = 1f - smoothstep(0.88f, 1f, dist);
				int channel = Math.round(value * 255f);
				image.setRGB(x, y, 0xFF000000 | (channel << 16) | (channel << 8) | channel);
			}
		}
		return image;
	}

	private static float smoothstep(float edge0, float edge1, float x) {
		float t = clamp((x - edge0) / (edge1 - edge0), 0f, 1f);
		return t * t * (3f - 2f * t);
	}
}
