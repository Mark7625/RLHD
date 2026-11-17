package rs117.hd.gpu;

import rs117.hd.utils.Destructible;

/**
 * A 2D texture owned by a {@link RenderDevice}. Each texture is bound to a single, fixed texture
 * unit for its lifetime, matching how this renderer samples textures (one dedicated unit per
 * logical texture, bound once and left sticky across frames).
 */
public interface GpuTexture extends Destructible {
	/**
	 * The backend's native handle for this texture, exposed as an escape hatch for call sites that
	 * haven't been migrated onto the {@link RenderDevice} API yet (e.g. code still using
	 * {@link rs117.hd.utils.RenderState} or raw LWJGL calls).
	 */
	int id();

	/** Makes this texture the current binding on its assigned texture unit. */
	void bind();

	/** Reallocates storage for the given dimensions, discarding any existing contents. */
	void resize(int width, int height);
}
