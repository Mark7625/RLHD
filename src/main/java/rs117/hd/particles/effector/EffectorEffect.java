package rs117.hd.particles.effector;

import com.google.gson.annotations.SerializedName;
import javax.annotation.Nullable;
import rs117.hd.particles.ParticleColors;
public abstract class EffectorEffect {
	public boolean edgeFalloff = true;
	public float falloffPower = 2f;
	public float effectPercent = 100f;
	public float immunePercent = 0f;

	@SerializedName("applyTo")
	public ApplicationMode applyTo = ApplicationMode.VELOCITY;

	public transient EffectType type;
	public transient int signedMagnitude;
	public transient boolean radial;
	public transient boolean whirlpool;
	public transient boolean positionMode;
	public transient boolean hasTargetColor;
	public transient int targetRed16;
	public transient int targetGreen16;
	public transient int targetBlue16;
	public transient int targetAlpha16;
	public transient ColorBlendMode resolvedColorBlend;

	public abstract EffectType effectType();

	public final void postDecode() {
		type = effectType();
		radial = type.radial;
		whirlpool = type == EffectType.WHIRLPOOL;
		effectPercent = Math.max(0f, Math.min(100f, effectPercent));
		immunePercent = Math.max(0f, Math.min(100f, immunePercent));
		decodeType();
		if (resolvedColorBlend == null) {
			resolvedColorBlend = whirlpool ? ColorBlendMode.PATH : ColorBlendMode.ZONE;
		}
	}

	protected abstract void decodeType();

	protected void decodeTargetColor(@Nullable String targetColor) {
		hasTargetColor = false;
		if (targetColor == null || targetColor.isEmpty()) {
			return;
		}
		int argb = ParticleColors.hexToArgb(targetColor);
		if (argb == 0) {
			return;
		}
		hasTargetColor = true;
		targetRed16 = ((argb >> 16) & 0xff) << 8;
		targetGreen16 = ((argb >> 8) & 0xff) << 8;
		targetBlue16 = (argb & 0xff) << 8;
		targetAlpha16 = ((argb >> 24) & 0xff) << 8;
	}

	public enum EffectType {
		WIND(false),
		PUSH(false),
		ATTRACT(true),
		REPEL(true),
		WHIRLPOOL(false);

		public final boolean radial;

		EffectType(boolean radial) {
			this.radial = radial;
		}
	}

	public enum ApplicationMode {
		@SerializedName("velocity")
		VELOCITY,
		@SerializedName("position")
		POSITION
	}

	public enum ColorBlendMode {
		@SerializedName("path")
		PATH,
		@SerializedName("zone")
		ZONE
	}
}
