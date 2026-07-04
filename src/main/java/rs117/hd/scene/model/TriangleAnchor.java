package rs117.hd.scene.model;

import lombok.Getter;
import lombok.Setter;
import rs117.hd.utils.GsonUtils;

@Getter
@Setter
@GsonUtils.ExcludeDefaults
public class TriangleAnchor {
	private String light;
	private float bary0 = 1f / 3f;
	private float bary1 = 1f / 3f;
	private float bary2 = 1f / 3f;

	public TriangleAnchor() {}

	public TriangleAnchor(String light, float bary0, float bary1, float bary2) {
		this.light = light;
		this.bary0 = bary0;
		this.bary1 = bary1;
		this.bary2 = bary2;
	}

	public TriangleAnchor copy() {
		return new TriangleAnchor(light, bary0, bary1, bary2);
	}

	public void normalizeBarycentric() {
		float sum = bary0 + bary1 + bary2;
		if (sum < 1e-6f) {
			bary0 = bary1 = bary2 = 1f / 3f;
			return;
		}
		bary0 /= sum;
		bary1 /= sum;
		bary2 /= sum;
	}
}
