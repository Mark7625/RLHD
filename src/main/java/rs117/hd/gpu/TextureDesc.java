package rs117.hd.gpu;

/**
 * Backend-agnostic description of a 2D texture's storage and sampling parameters.
 * Fluent setters return {@code this}, so a descriptor can be built inline at the call site.
 */
public class TextureDesc {
	public int width;
	public int height;
	public int depth;
	public PixelFormat format = PixelFormat.RGBA8;
	public FilterMode minFilter = FilterMode.NEAREST;
	public FilterMode magFilter = FilterMode.NEAREST;
	public WrapMode wrapS = WrapMode.CLAMP_TO_EDGE;
	public WrapMode wrapT = WrapMode.CLAMP_TO_EDGE;
	public WrapMode wrapR = WrapMode.CLAMP_TO_EDGE;
	public CompareMode compareMode = CompareMode.NONE;
	public CompareFunc compareFunc = CompareFunc.LEQUAL;
	public float[] borderColor;
	public int mipLevels = 1;

	public TextureDesc size(int width, int height) {
		this.width = width;
		this.height = height;
		return this;
	}

	public TextureDesc size(int width, int height, int depth) {
		this.width = width;
		this.height = height;
		this.depth = depth;
		return this;
	}

	public TextureDesc format(PixelFormat format) {
		this.format = format;
		return this;
	}

	public TextureDesc filter(FilterMode min, FilterMode mag) {
		this.minFilter = min;
		this.magFilter = mag;
		return this;
	}

	public TextureDesc wrap(WrapMode s, WrapMode t) {
		this.wrapS = s;
		this.wrapT = t;
		return this;
	}

	public TextureDesc wrap(WrapMode s, WrapMode t, WrapMode r) {
		this.wrapS = s;
		this.wrapT = t;
		this.wrapR = r;
		return this;
	}

	public TextureDesc compare(CompareMode mode, CompareFunc func) {
		this.compareMode = mode;
		this.compareFunc = func;
		return this;
	}

	public TextureDesc borderColor(float... color) {
		this.borderColor = color;
		return this;
	}

	public TextureDesc mipmapped(int levels) {
		this.mipLevels = levels;
		return this;
	}
}
