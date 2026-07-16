package rs117.hd.particles.effector;

import javax.annotation.Nullable;

public class WhirlpoolEffect extends EffectorEffect {
	public int strength;
	public int sink;
	public boolean clockwise;
	public boolean inverted;
	public float pathVariation = 0f;

	@Nullable
	public String targetColor;

	@Nullable
	public ColorBlendMode colorBlend;

	public transient int sinkStrength;
	public transient float pathVariationScale;

	@Override
	public EffectType effectType() {
		return EffectType.WHIRLPOOL;
	}

	@Override
	protected void decodeType() {
		positionMode = false;
		if (applyTo == null) {
			applyTo = ApplicationMode.VELOCITY;
		}
		signedMagnitude = Math.abs(strength > 0 ? strength : 2000);
		sinkStrength = sink > 0 ? sink : Math.max(1, signedMagnitude / 2);
		pathVariationScale = Math.max(0f, Math.min(1f, pathVariation / 100f));
		resolvedColorBlend = colorBlend != null ? colorBlend : ColorBlendMode.PATH;
		decodeTargetColor(targetColor);
	}
}
