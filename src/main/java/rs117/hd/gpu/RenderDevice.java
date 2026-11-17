package rs117.hd.gpu;

import rs117.hd.utils.Destructible;

/**
 * Factory for GPU resources, implemented once per graphics backend: {@link rs117.hd.gpu.gl.GLRenderDevice}
 * for the live plugin, and {@link rs117.hd.gpu.vulkan.VulkanRenderDevice} as an experimental,
 * unwired validation stub. The goal is to contain backend-specific calls behind this API as more
 * of the renderer is migrated onto it, so that an alternative backend becomes possible to
 * implement without having to touch every call site at once.
 */
public interface RenderDevice extends Destructible {
	/**
	 * Creates a 2D texture permanently bound to {@code textureUnit} (an absolute unit, e.g.
	 * {@code GL_TEXTURE0 + n} on the GL backend). If {@code desc} has a non-zero size, storage is
	 * allocated immediately; otherwise call {@link GpuTexture#resize} once the size is known.
	 */
	GpuTexture createTexture2D(int textureUnit, TextureDesc desc);

	/**
	 * Creates a 2D array texture (one allocation, {@code desc.depth} layers) permanently bound to
	 * {@code textureUnit}. Unlike {@link #createTexture2D}, array textures in this renderer are
	 * always fully recreated on size/layer-count changes rather than resized in place.
	 */
	GpuTexture createTexture2DArray(int textureUnit, TextureDesc desc);

	/**
	 * Creates a cube map texture (6 faces, each {@code desc.width}x{@code desc.height}) permanently
	 * bound to {@code textureUnit}.
	 */
	GpuTexture createTextureCubeMap(int textureUnit, TextureDesc desc);

	/**
	 * Creates a 3D texture permanently bound to {@code textureUnit}. Pass a zero-sized {@code desc}
	 * and upload data directly via {@link GpuTexture#bind()} plus a backend-specific call when the
	 * data needs to be supplied at allocation time (e.g. a one-shot integer volume texture) rather
	 * than written afterwards.
	 */
	GpuTexture createTexture3D(int textureUnit, TextureDesc desc);

	/** Creates an empty framebuffer with no attachments. */
	GpuFramebuffer createFramebuffer();
}
