package rs117.hd.particles.effector;

import java.util.ArrayList;
import java.util.List;
import rs117.hd.particles.effector.EffectorEffect.Attract;
import rs117.hd.particles.effector.EffectorEffect.Repel;
import rs117.hd.particles.effector.EffectorEffect.Whirlpool;
import rs117.hd.particles.effector.EffectorEffect.Wind;

public final class EffectorBuilder {

	private final EffectorDefinition def = new EffectorDefinition();
	private final List<EffectorEffect> effects = new ArrayList<>();

	private EffectorBuilder(String id) {
		def.id = id.toUpperCase();
	}

	public static EffectorBuilder create(String id) {
		return new EffectorBuilder(id);
	}

	public EffectorBuilder heightOffset(int heightOffset) {
		def.heightOffset = heightOffset;
		return this;
	}

	public EffectorBuilder radiusTiles(float radiusTiles) {
		def.radiusTiles = radiusTiles;
		return this;
	}

	public EffectorBuilder wind(float dirX, float dirY, float dirZ, float speed, float intensity) {
		return wind(dirX, dirY, dirZ, speed, intensity, 0f, 0f);
	}

	public EffectorBuilder wind(
		float dirX,
		float dirY,
		float dirZ,
		float speed,
		float intensity,
		float directionVariance,
		float turbulence
	) {
		Wind effect = new Wind();
		effect.direction = new float[] { dirX, dirY, dirZ };
		effect.speed = speed;
		effect.intensity = intensity;
		effect.directionVariance = directionVariance;
		effect.turbulence = turbulence;
		effects.add(effect);
		return this;
	}

	public EffectorBuilder attract(int strength) {
		Attract effect = new Attract();
		effect.strength = strength;
		effect.applyTo = EffectorEffect.ApplicationMode.VELOCITY;
		effects.add(effect);
		return this;
	}

	public EffectorBuilder repel(int strength) {
		Repel effect = new Repel();
		effect.strength = strength;
		effect.applyTo = EffectorEffect.ApplicationMode.VELOCITY;
		effects.add(effect);
		return this;
	}

	public EffectorBuilder whirlpool(int strength, int sink) {
		Whirlpool effect = new Whirlpool();
		effect.strength = strength;
		effect.sink = sink;
		effects.add(effect);
		return this;
	}

	public EffectorBuilder effect(EffectorEffect effect) {
		effects.add(effect);
		return this;
	}

	public EffectorBuilder edgeFalloff(boolean edgeFalloff) {
		EffectorEffect last = last();
		if (last != null) {
			last.edgeFalloff = edgeFalloff;
		}
		return this;
	}

	public EffectorBuilder falloffPower(float falloffPower) {
		EffectorEffect last = last();
		if (last != null) {
			last.falloffPower = falloffPower;
		}
		return this;
	}

	public EffectorBuilder effectPercent(float effectPercent) {
		EffectorEffect last = last();
		if (last != null) {
			last.effectPercent = effectPercent;
		}
		return this;
	}

	public EffectorBuilder immunePercent(float immunePercent) {
		EffectorEffect last = last();
		if (last != null) {
			last.immunePercent = immunePercent;
		}
		return this;
	}

	public EffectorBuilder inverted(boolean inverted) {
		Whirlpool last = lastWhirlpool();
		if (last != null) {
			last.inverted = inverted;
		}
		return this;
	}

	public EffectorBuilder clockwise(boolean clockwise) {
		Whirlpool last = lastWhirlpool();
		if (last != null) {
			last.clockwise = clockwise;
		}
		return this;
	}

	public EffectorBuilder pathVariation(float pathVariation) {
		Whirlpool last = lastWhirlpool();
		if (last != null) {
			last.pathVariation = pathVariation;
		}
		return this;
	}

	public EffectorBuilder targetColor(String hex) {
		Whirlpool last = lastWhirlpool();
		if (last != null) {
			last.targetColor = hex;
		}
		return this;
	}

	public EffectorBuilder colorBlend(EffectorEffect.ColorBlendMode mode) {
		Whirlpool last = lastWhirlpool();
		if (last != null) {
			last.colorBlend = mode;
		}
		return this;
	}

	public EffectorDefinition build() {
		def.effects = List.copyOf(effects);
		def.postDecode();
		return def;
	}

	public EffectorRef buildRef() {
		return EffectorRef.builtIn(build());
	}

	private EffectorEffect last() {
		return effects.isEmpty() ? null : effects.get(effects.size() - 1);
	}

	private Whirlpool lastWhirlpool() {
		EffectorEffect last = last();
		return last instanceof Whirlpool ? (Whirlpool) last : null;
	}
}
