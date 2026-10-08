package rs117.hd.opengl.shader;

import static org.lwjgl.opengl.GL33C.*;

public class LoginPanoramaShaderProgram extends ShaderProgram {
	public final UniformTexture uniFaceTextures = addUniformTexture("faceTextures");
	public final Uniform1f uniCapturedHalfFovRadians = addUniform1f("capturedHalfFovRadians");

	public LoginPanoramaShaderProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "login_panorama_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "login_panorama_frag.glsl"));
	}

	@Override
	protected void initialize() {
		uniFaceTextures.set(GL_TEXTURE0);
	}
}
