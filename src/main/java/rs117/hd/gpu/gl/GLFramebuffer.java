package rs117.hd.gpu.gl;

import lombok.extern.slf4j.Slf4j;
import rs117.hd.gpu.GpuFramebuffer;
import rs117.hd.gpu.GpuTexture;
import rs117.hd.utils.DestructibleHandler;

import static org.lwjgl.opengl.GL33C.*;

@Slf4j
class GLFramebuffer implements GpuFramebuffer {
	private int id;

	GLFramebuffer() {
		id = glGenFramebuffers();
	}

	@Override
	public int id() {
		return id;
	}

	@Override
	public void bind() {
		glBindFramebuffer(GL_FRAMEBUFFER, id);
	}

	@Override
	public void attachDepthOnly(GpuTexture depthTexture) {
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depthTexture.id(), 0);
		glDrawBuffer(GL_NONE);
		glReadBuffer(GL_NONE);
	}

	@Override
	public void attachColorLayered(GpuTexture colorTexture) {
		glFramebufferTexture(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, colorTexture.id(), 0);
	}

	@Override
	public void attachColorCubeFace(GpuTexture cubeMapTexture, int face) {
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, cubeMapTexture.id(), 0);
	}

	@Override
	public void attachColorRenderbuffer(int renderbufferId) {
		glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, renderbufferId);
	}

	@Override
	public void attachDepthRenderbuffer(int renderbufferId) {
		glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, renderbufferId);
	}

	@Override
	public void destroy() {
		if (id != 0)
			glDeleteFramebuffers(id);
		id = 0;
	}

	@Override
	@SuppressWarnings("deprecation")
	protected void finalize() {
		if (id != 0)
			DestructibleHandler.queueLeakedDestruction(this);
	}
}
