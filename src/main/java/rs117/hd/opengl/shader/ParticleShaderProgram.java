package rs117.hd.opengl.shader;

import org.lwjgl.opengl.GL33C;

public class ParticleShaderProgram extends ShaderProgram {
	private UniformTexture uParticleTexture;

	public ParticleShaderProgram() {
		super(t -> t
			.add(GL33C.GL_VERTEX_SHADER, "particle_vert.glsl")
			.add(GL33C.GL_FRAGMENT_SHADER, "particle_frag.glsl"));
	}

	@Override
	protected void initialize() {
		uParticleTexture = addUniformTexture("uParticleTexture");
	}

	public void setParticleTextureUnit(int textureUnit) {
		if (uParticleTexture != null && isValid())
			uParticleTexture.set(textureUnit);
	}
}
