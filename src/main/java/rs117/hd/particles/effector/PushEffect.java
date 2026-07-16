package rs117.hd.particles.effector;

import javax.annotation.Nullable;

public class PushEffect extends EffectorEffect {
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
