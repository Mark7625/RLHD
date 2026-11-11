package rs117.hd.scene.model.debug;

import lombok.Value;

@Value
public class ParticleEmitter {
	int face;
	float bary0;
	float bary1;
	float bary2;
	int rate;
	int lifetimeMs;
	int rise;
	int spread;
	int size;
	int rgb;

	public boolean isValid() {
		return face >= 0 && bary0 >= 0 && bary1 >= 0 && bary2 >= 0 &&
			Math.abs(bary0 + bary1 + bary2 - 1) < 0.001 &&
			rate >= 1 && rate <= 60 && lifetimeMs >= 100 && lifetimeMs <= 3000 &&
			rise >= -100 && rise <= 300 && spread >= 0 && spread <= 150 &&
			size >= 1 && size <= 20 && rgb >= 0 && rgb <= 0xffffff;
	}
}
