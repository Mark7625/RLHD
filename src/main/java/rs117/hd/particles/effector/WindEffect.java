package rs117.hd.particles.effector;

import com.google.gson.annotations.SerializedName;
import javax.annotation.Nullable;

public class WindEffect extends EffectorEffect {
	@Nullable
	public float[] direction;

	public float speed = 1f;
	public float intensity = 1f;

	@SerializedName(value = "directionVariance", alternate = { "directionVariation" })
	public float directionVariance = 0f;

	public float turbulence = 0f;
	public float gust = 55f;
	public float gustSpeed = 1f;
	public float response = 3.2f;
	public float maxBlend = 0.65f;
	public float lift = 40f;
	public float speedUnits = 95f;

	public transient float windDirX;
	public transient float windDirY;
	public transient float windDirZ;
	public transient boolean hasWindDirection;
	public transient float directionVarianceScale;
	public transient float turbulenceScale;
	public transient float gustScale;
	public transient float liftScale;

	@Override
	public EffectType effectType() {
		return EffectType.WIND;
	}

	@Override
	protected void decodeType() {
		positionMode = false;
		speed = speed > 0f ? speed : 1f;
		intensity = intensity > 0f ? intensity : 1f;
		directionVarianceScale = Math.max(0f, directionVariance / 100f);
		turbulenceScale = Math.max(0f, turbulence / 100f);
		gustScale = Math.max(0f, gust / 100f);
		liftScale = Math.max(0f, lift / 100f);
		gustSpeed = gustSpeed > 0f ? gustSpeed : 1f;
		response = response > 0f ? response : 3.2f;
		maxBlend = Math.max(0.05f, Math.min(1f, maxBlend > 0f ? maxBlend : 0.65f));
		speedUnits = speedUnits > 0f ? speedUnits : 95f;
		decodeWindDirection();
	}

	private void decodeWindDirection() {
		float east = direction != null && direction.length > 0 ? direction[0] : 0f;
		float height = direction != null && direction.length > 1 ? direction[1] : 0f;
		float north = direction != null && direction.length > 2 ? direction[2] : 0f;
		float len = (float) Math.sqrt(east * east + height * height + north * north);
		if (len > 1e-6f) {
			windDirX = east / len;
			windDirY = north / len;
			windDirZ = height / len;
			hasWindDirection = true;
			signedMagnitude = 4096;
		} else {
			windDirX = 0f;
			windDirY = 1f;
			windDirZ = 0f;
			hasWindDirection = false;
			signedMagnitude = 0;
		}
	}
}
