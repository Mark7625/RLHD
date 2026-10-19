package rs117.hd.opengl.shader;

import static org.lwjgl.opengl.GL33C.*;

public class ConstellationShaderProgram extends ShaderProgram {
	public ConstellationShaderProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "constellation_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "constellation_frag.glsl"));
	}
}
