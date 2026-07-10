package rs117.hd.scene.lights;

public enum MaskProjection {
	AUTO,
	FLAT,
	WALL;

	public int shaderIndex() {
		return ordinal();
	}
}
