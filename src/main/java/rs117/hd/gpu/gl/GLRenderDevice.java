package rs117.hd.gpu.gl;

import rs117.hd.gpu.GpuFramebuffer;
import rs117.hd.gpu.GpuTexture;
import rs117.hd.gpu.RenderDevice;
import rs117.hd.gpu.TextureDesc;

import static org.lwjgl.opengl.GL33C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_2D_ARRAY;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_3D;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_CUBE_MAP;

/** LWJGL/OpenGL-backed implementation of {@link RenderDevice}. */
public class GLRenderDevice implements RenderDevice {
	@Override
	public GpuTexture createTexture2D(int textureUnit, TextureDesc desc) {
		return new GLTexture(GL_TEXTURE_2D, textureUnit, desc);
	}

	@Override
	public GpuTexture createTexture2DArray(int textureUnit, TextureDesc desc) {
		return new GLTexture(GL_TEXTURE_2D_ARRAY, textureUnit, desc);
	}

	@Override
	public GpuTexture createTextureCubeMap(int textureUnit, TextureDesc desc) {
		return new GLTexture(GL_TEXTURE_CUBE_MAP, textureUnit, desc);
	}

	@Override
	public GpuTexture createTexture3D(int textureUnit, TextureDesc desc) {
		return new GLTexture(GL_TEXTURE_3D, textureUnit, desc);
	}

	@Override
	public GpuFramebuffer createFramebuffer() {
		return new GLFramebuffer();
	}

	@Override
	public void destroy() {
		// No device-level state to tear down: every resource created through this factory owns
		// and destroys its own GL object(s) individually.
	}
}
