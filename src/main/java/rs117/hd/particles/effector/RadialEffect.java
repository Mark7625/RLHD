package rs117.hd.particles.effector;
public abstract class RadialEffect extends EffectorEffect {
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
