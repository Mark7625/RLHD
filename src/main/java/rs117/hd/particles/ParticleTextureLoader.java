package rs117.hd.particles;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.BufferUtils;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.renderer.zone.ZoneRenderer.TEXTURE_UNIT_PARTICLE;
import static rs117.hd.utils.ResourcePath.path;

/**
 * GPU particle texture array. Layer 0 is the procedural soft disc (default when
 * no texture file is set); layers 1..N are lazily loaded PNGs from the
 * particles textures folder.
 */
@Slf4j
@Singleton
class ParticleTextureLoader
{
	private static final ResourcePath PARTICLE_TEXTURES_PATH = Props.getFolder(
		"rlhd.particle-texture-path",
		() -> path(ParticleTextureLoader.class, "textures")
	);
	private static final int LAYER_SIZE = 256;
	private static final int MAX_LAYERS = 32;

	private final Map<String, Integer> nameToLayer = new HashMap<>();
	private int texArrayId;
	private int nextLayer = 1;

	static ResourcePath getParticleTexturesPath()
	{
		return PARTICLE_TEXTURES_PATH;
	}

	static List<String> listAvailableTextures()
	{
		TreeSet<String> names = new TreeSet<>();
		try
		{
			if (PARTICLE_TEXTURES_PATH.exists() && PARTICLE_TEXTURES_PATH.isFileSystemResource())
			{
				var dir = PARTICLE_TEXTURES_PATH.toFile();
				if (dir.isDirectory())
				{
					var files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".png"));
					if (files != null)
					{
						for (var file : files)
						{
							names.add(file.getName());
						}
					}
				}
			}
		}
		catch (Exception e)
		{
			log.debug("[Particles] Could not scan texture folder: {}", PARTICLE_TEXTURES_PATH, e);
		}
		return new ArrayList<>(names);
	}

	void initialize()
	{
		ensureArrayCreated();
	}

	int getTextureArrayId()
	{
		ensureArrayCreated();
		return texArrayId;
	}

	int getTextureLayer(@Nullable String textureName)
	{
		if (textureName == null || textureName.isEmpty())
		{
			return 0;
		}
		Integer layer = nameToLayer.get(textureName);
		if (layer != null)
		{
			return layer;
		}
		ensureArrayCreated();
		if (nextLayer >= MAX_LAYERS)
		{
			log.warn("[Particles] Texture array full, using default layer for: {}", textureName);
			return 0;
		}
		try
		{
			ResourcePath resPath = PARTICLE_TEXTURES_PATH.resolve(textureName);
			BufferedImage img = loadImageExact(resPath);
			if (img == null)
			{
				return 0;
			}
			int layerIdx = nextLayer++;
			uploadToLayer(layerIdx, img);
			nameToLayer.put(textureName, layerIdx);
			return layerIdx;
		}
		catch (IOException e)
		{
			log.warn("[Particles] Failed to load texture: {}", textureName, e);
			return 0;
		}
	}

	void destroy()
	{
		if (texArrayId != 0)
		{
			glActiveTexture(TEXTURE_UNIT_PARTICLE);
			glDeleteTextures(texArrayId);
			texArrayId = 0;
		}
		nameToLayer.clear();
		nextLayer = 1;
	}

	private void ensureArrayCreated()
	{
		if (texArrayId != 0)
		{
			return;
		}
		texArrayId = glGenTextures();
		glActiveTexture(TEXTURE_UNIT_PARTICLE);
		glBindTexture(GL_TEXTURE_2D_ARRAY, texArrayId);
		glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA, LAYER_SIZE, LAYER_SIZE, MAX_LAYERS, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
		uploadDefaultLayer0();
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
	}

	private void uploadDefaultLayer0()
	{
		ByteBuffer pixels = BufferUtils.createByteBuffer(LAYER_SIZE * LAYER_SIZE * 4);
		float center = (LAYER_SIZE - 1) * 0.5f;
		float radius = center;
		for (int y = 0; y < LAYER_SIZE; y++)
		{
			for (int x = 0; x < LAYER_SIZE; x++)
			{
				float dx = (x - center) / radius;
				float dy = (y - center) / radius;
				float t = dx * dx + dy * dy;
				float radial = (float) Math.pow(Math.max(0f, 1f - t), 2);
				float alpha = (float) Math.pow(Math.max(0f, Math.sin(Math.PI * 0.5f * (1f - Math.sqrt(t)))), 0.8);
				int a = (int) (alpha * radial * 255f);
				pixels.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) Math.min(255, a));
			}
		}
		pixels.flip();
		glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, 0, LAYER_SIZE, LAYER_SIZE, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
	}

	private void uploadToLayer(int layer, BufferedImage img)
	{
		BufferedImage scaled = img;
		if (img.getWidth() != LAYER_SIZE || img.getHeight() != LAYER_SIZE)
		{
			scaled = new BufferedImage(LAYER_SIZE, LAYER_SIZE, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = scaled.createGraphics();
			g.drawImage(img, 0, 0, LAYER_SIZE, LAYER_SIZE, null);
			g.dispose();
		}
		ByteBuffer pixels = BufferUtils.createByteBuffer(LAYER_SIZE * LAYER_SIZE * 4);
		for (int y = LAYER_SIZE - 1; y >= 0; y--)
		{
			for (int x = 0; x < LAYER_SIZE; x++)
			{
				int argb = scaled.getRGB(x, y);
				pixels.put((byte) ((argb >> 16) & 0xFF));
				pixels.put((byte) ((argb >> 8) & 0xFF));
				pixels.put((byte) (argb & 0xFF));
				pixels.put((byte) ((argb >> 24) & 0xFF));
			}
		}
		pixels.flip();
		glActiveTexture(TEXTURE_UNIT_PARTICLE);
		glBindTexture(GL_TEXTURE_2D_ARRAY, texArrayId);
		glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer, LAYER_SIZE, LAYER_SIZE, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
		glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
	}

	@Nullable
	private BufferedImage loadImageExact(ResourcePath resourcePath) throws IOException
	{
		try (InputStream is = resourcePath.toInputStream())
		{
			BufferedImage img = ImageIO.read(is);
			if (img == null)
			{
				log.warn("[Particles] ImageIO.read returned null for: {}", resourcePath);
				return null;
			}
			return img;
		}
	}
}
