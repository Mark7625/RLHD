package rs117.hd.scene.environments;

import lombok.Getter;

@Getter
public class SkyboxPostProcessingConfig {
	private final float brightness = -1f;
	private final float contrast = -1f;
	private final float saturation = -1f;
	private final float hueShift = -1f;
	public float[] tintColor = {-1f, -1f, -1f};

}
