package rs117.hd.gpu.gl;

import lombok.extern.slf4j.Slf4j;
import org.lwjgl.opengl.ARBTextureStorage;
import rs117.hd.gpu.CompareMode;
import rs117.hd.gpu.GpuTexture;
import rs117.hd.gpu.TextureDesc;
import rs117.hd.utils.DestructibleHandler;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.GL_CAPS;
import static rs117.hd.HdPlugin.checkGLErrors;
import static rs117.hd.utils.MathUtils.max;

@Slf4j
class GLTexture implements GpuTexture {
	private final int target;
	private final int textureUnit;
	private final TextureDesc desc;
	private int id;
	private int width, height, depth;

	GLTexture(int target, int textureUnit, TextureDesc desc) {
		this.target = target;
		this.textureUnit = textureUnit;
		this.desc = desc;

		glActiveTexture(textureUnit);
		id = glGenTextures();
		glBindTexture(target, id);

		glTexParameteri(target, GL_TEXTURE_MIN_FILTER, GLFormats.filter(desc.minFilter));
		glTexParameteri(target, GL_TEXTURE_MAG_FILTER, GLFormats.filter(desc.magFilter));
		glTexParameteri(target, GL_TEXTURE_WRAP_S, GLFormats.wrap(desc.wrapS));
		glTexParameteri(target, GL_TEXTURE_WRAP_T, GLFormats.wrap(desc.wrapT));
		if (target == GL_TEXTURE_CUBE_MAP)
			glTexParameteri(target, GL_TEXTURE_WRAP_R, GLFormats.wrap(desc.wrapR));
		if (desc.borderColor != null)
			glTexParameterfv(target, GL_TEXTURE_BORDER_COLOR, desc.borderColor);
		glTexParameteri(
			target, GL_TEXTURE_COMPARE_MODE,
			desc.compareMode == CompareMode.REF_TO_TEXTURE ? GL_COMPARE_REF_TO_TEXTURE : GL_NONE
		);
		if (desc.compareMode == CompareMode.REF_TO_TEXTURE)
			glTexParameteri(target, GL_TEXTURE_COMPARE_FUNC, GLFormats.compareFunc(desc.compareFunc));

		if (desc.width > 0 && desc.height > 0)
			allocate(desc.width, desc.height, desc.depth);

		checkGLErrors(() -> "Errors encountered while creating texture on unit " + (textureUnit - GL_TEXTURE0));
	}

	@Override
	public int id() {
		return id;
	}

	@Override
	public void bind() {
		glActiveTexture(textureUnit);
		glBindTexture(target, id);
	}

	@Override
	public void resize(int width, int height) {
		if (this.width == width && this.height == height)
			return;

		glActiveTexture(textureUnit);
		glBindTexture(target, id);
		allocate(width, height, desc.depth);
	}

	private void allocate(int width, int height, int depth) {
		this.width = width;
		this.height = height;
		this.depth = depth;
		if (target == GL_TEXTURE_2D_ARRAY) {
			int mipLevels = max(1, desc.mipLevels);
			if (mipLevels > 1 && GL_CAPS.glTexStorage3D != 0) {
				ARBTextureStorage.glTexStorage3D(target, mipLevels, GLFormats.internalFormat(desc.format), width, height, depth);
			} else {
				for (int level = 0; level < mipLevels; level++) {
					glTexImage3D(
						target, level,
						GLFormats.internalFormat(desc.format),
						max(1, width >> level), max(1, height >> level), depth, 0,
						GLFormats.baseFormat(desc.format),
						GLFormats.dataType(desc.format),
						0
					);
				}
			}
		} else if (target == GL_TEXTURE_CUBE_MAP) {
			for (int face = 0; face < 6; face++) {
				glTexImage2D(
					GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, 0,
					GLFormats.internalFormat(desc.format),
					width, height, 0,
					GLFormats.baseFormat(desc.format),
					GLFormats.dataType(desc.format),
					0
				);
			}
		} else if (target == GL_TEXTURE_3D) {
			glTexImage3D(
				target, 0,
				GLFormats.internalFormat(desc.format),
				width, height, depth, 0,
				GLFormats.baseFormat(desc.format),
				GLFormats.dataType(desc.format),
				0
			);
		} else {
			glTexImage2D(
				target, 0,
				GLFormats.internalFormat(desc.format),
				width, height, 0,
				GLFormats.baseFormat(desc.format),
				GLFormats.dataType(desc.format),
				0
			);
		}
	}

	@Override
	public void destroy() {
		if (id != 0)
			glDeleteTextures(id);
		id = 0;
	}

	@Override
	@SuppressWarnings("deprecation")
	protected void finalize() {
		if (id != 0)
			DestructibleHandler.queueLeakedDestruction(this);
	}
}
