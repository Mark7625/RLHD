package rs117.hd.gpu.gl;

import rs117.hd.gpu.CompareFunc;
import rs117.hd.gpu.FilterMode;
import rs117.hd.gpu.PixelFormat;
import rs117.hd.gpu.WrapMode;

import static org.lwjgl.opengl.GL33C.*;

/** Translates the backend-agnostic enums in {@code rs117.hd.gpu} to their GL33 equivalents. */
final class GLFormats {
	private GLFormats() {}

	static int internalFormat(PixelFormat format) {
		switch (format) {
			case RGBA8:
				return GL_RGBA;
			case SRGB8_ALPHA8:
				return GL_SRGB8_ALPHA8;
			case RGBA16F:
				return GL_RGBA16F;
			case RGBA16UI:
				return GL_RGBA16UI;
			case DEPTH16:
				return GL_DEPTH_COMPONENT16;
			case DEPTH24:
				return GL_DEPTH_COMPONENT24;
			default:
				throw new IllegalArgumentException("Unsupported format: " + format);
		}
	}

	static int baseFormat(PixelFormat format) {
		switch (format) {
			case RGBA8:
				return GL_BGRA;
			case SRGB8_ALPHA8:
			case RGBA16F:
				return GL_RGBA;
			case RGBA16UI:
				return GL_RGBA_INTEGER;
			case DEPTH16:
			case DEPTH24:
				return GL_DEPTH_COMPONENT;
			default:
				throw new IllegalArgumentException("Unsupported format: " + format);
		}
	}

	static int dataType(PixelFormat format) {
		switch (format) {
			case RGBA8:
			case SRGB8_ALPHA8:
				return GL_UNSIGNED_BYTE;
			case RGBA16UI:
				return GL_UNSIGNED_SHORT;
			case RGBA16F:
			case DEPTH16:
			case DEPTH24:
				return GL_FLOAT;
			default:
				throw new IllegalArgumentException("Unsupported format: " + format);
		}
	}

	static int filter(FilterMode mode) {
		return mode == FilterMode.LINEAR ? GL_LINEAR : GL_NEAREST;
	}

	static int wrap(WrapMode mode) {
		switch (mode) {
			case CLAMP_TO_BORDER:
				return GL_CLAMP_TO_BORDER;
			case REPEAT:
				return GL_REPEAT;
			default:
				return GL_CLAMP_TO_EDGE;
		}
	}

	static int compareFunc(CompareFunc func) {
		switch (func) {
			case LESS:
				return GL_LESS;
			case LEQUAL:
				return GL_LEQUAL;
			case GREATER:
				return GL_GREATER;
			case GEQUAL:
				return GL_GEQUAL;
			default:
				throw new IllegalArgumentException("Unsupported compare func: " + func);
		}
	}
}
