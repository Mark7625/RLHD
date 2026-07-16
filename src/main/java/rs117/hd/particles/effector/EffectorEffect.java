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

	public static class Slot {
		@Nullable
		public Wind wind;
		@Nullable
		public Push push;
		@Nullable
		public Attract attract;
		@Nullable
		public Repel repel;
		@Nullable
		public Whirlpool whirlpool;

		@Nullable
		public EffectorEffect resolve() {
			if (wind != null) {
				return wind;
			}
			if (push != null) {
				return push;
			}
			if (attract != null) {
				return attract;
			}
			if (repel != null) {
				return repel;
			}
			if (whirlpool != null) {
				return whirlpool;
			}
			return null;
		}

		public static Slot from(EffectorEffect effect) {
			Slot slot = new Slot();
			if (effect instanceof Wind) {
				slot.wind = (Wind) effect;
			} else if (effect instanceof Push) {
				slot.push = (Push) effect;
			} else if (effect instanceof Attract) {
				slot.attract = (Attract) effect;
			} else if (effect instanceof Repel) {
				slot.repel = (Repel) effect;
			} else if (effect instanceof Whirlpool) {
				slot.whirlpool = (Whirlpool) effect;
			}
			return slot;
		}
	}

	public static class Wind extends EffectorEffect {
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

	public static class Push extends EffectorEffect {
		@Nullable
		public float[] direction;

		public int spreadAngle;
		public int strength;
		public float speed = 1f;
		public float intensity = 1f;

		public transient int forceDirectionX;
		public transient int forceDirectionY;
		public transient int forceDirectionZ;
		public transient int coneAngleCosine;

		@Override
		public EffectType effectType() {
			return EffectType.PUSH;
		}

		@Override
		protected void decodeType() {
			positionMode = applyTo == ApplicationMode.POSITION || applyTo == null;
			if (applyTo == null) {
				applyTo = ApplicationMode.POSITION;
			}
			speed = speed > 0f ? speed : 1f;
			intensity = intensity > 0f ? intensity : 1f;

			int angleIndex = (spreadAngle << 3) & 0x3fff;
			if (spreadAngle >= 1024) {
				angleIndex = 8192;
			}
			coneAngleCosine = EffectorDefinition.cosineTable[angleIndex];
			float east = direction != null && direction.length > 0 ? direction[0] : 0f;
			float height = direction != null && direction.length > 1 ? direction[1] : 0f;
			float north = direction != null && direction.length > 2 ? direction[2] : 0f;
			forceDirectionX = Math.round(east);
			forceDirectionY = Math.round(north);
			forceDirectionZ = Math.round(height);
			long fx = forceDirectionX;
			long fy = forceDirectionY;
			long fz = forceDirectionZ;
			signedMagnitude = strength > 0
				? strength
				: (int) Math.sqrt(fx * fx + fy * fy + fz * fz);
		}
	}

	public static abstract class Radial extends EffectorEffect {
		public int strength;

		@Override
		protected void decodeType() {
			positionMode = applyTo == ApplicationMode.POSITION;
			if (applyTo == null) {
				applyTo = ApplicationMode.VELOCITY;
			}
			signedMagnitude = effectType() == EffectType.ATTRACT
				? -Math.abs(strength)
				: Math.abs(strength);
		}
	}

	public static class Attract extends Radial {
		@Override
		public EffectType effectType() {
			return EffectType.ATTRACT;
		}
	}

	public static class Repel extends Radial {
		@Override
		public EffectType effectType() {
			return EffectType.REPEL;
		}
	}

	public static class Whirlpool extends EffectorEffect {
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
}
