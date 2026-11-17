package rs117.hd.gpu;

import rs117.hd.utils.Destructible;

/**
 * A render target owned by a {@link RenderDevice}. Only the two attachment patterns this
 * renderer actually uses are supported so far: a sole depth attachment, or a layered color
 * attachment covering an entire 2D array texture (individual layers are still bound per-draw via
 * {@link rs117.hd.utils.RenderState} using {@link GpuTexture#id()}, not through this interface).
 */
public interface GpuFramebuffer extends Destructible {
	/**
	 * The backend's native handle for this framebuffer, exposed as an escape hatch for call sites
	 * that haven't been migrated onto the {@link RenderDevice} API yet.
	 */
	int id();

	/** Makes this the current read/draw framebuffer. */
	void bind();

	/** Attaches the given texture as the sole depth attachment, disabling color reads/writes. */
	void attachDepthOnly(GpuTexture depthTexture);

	/** Attaches every layer of the given array texture as a single layered color attachment. */
	void attachColorLayered(GpuTexture colorTexture);

	/** Attaches one face of the given cube map texture as the color attachment. */
	void attachColorCubeFace(GpuTexture cubeMapTexture, int face);

	/**
	 * Attaches a raw, backend-native renderbuffer handle as the color attachment. Renderbuffers
	 * aren't modeled as {@link GpuTexture} (they're not sampled, and this renderer's MSAA
	 * renderbuffers are allocated via a runtime format-fallback probe that doesn't fit a
	 * declarative descriptor), so callers create them directly and only hand the resulting id here.
	 */
	void attachColorRenderbuffer(int renderbufferId);

	/** Attaches a raw, backend-native renderbuffer handle as the depth attachment. */
	void attachDepthRenderbuffer(int renderbufferId);
}
