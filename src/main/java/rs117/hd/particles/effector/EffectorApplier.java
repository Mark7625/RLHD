package rs117.hd.particles.effector;

import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import rs117.hd.particles.Particle;
public final class EffectorApplier
{
	private static final int EFFECT_VELOCITY = 1;
	private static final int EFFECT_PATH_GUIDED = 2;
	private static final int EFFECT_DESPAWN = 4;
	private static final float DEFAULT_WIND_SPEED_UNITS = 95f;
	private static final float DEFAULT_WIND_RESPONSE = 3.2f;
	private static final float RADIAL_FORCE_SCALE = 50f;
	private static final float PUSH_FORCE_SCALE = 50f;

	private EffectorApplier()
	{
	}
	public static boolean apply(
		Particle p,
		float dt,
		@Nullable Map<String, List<ActiveEffectorState>> activeEffectorsById,
		@Nullable EffectorDefinitionManager effectorDefinitions
	)
	{
		if (dt <= 0f)
		{
			return false;
		}

		List<String> localFilter = p.getLocalEffectorFilter();
		List<String> globalIds = p.getGlobalEffectors();
		List<String> embeddedIds = p.getEmbeddedEffectors();
		boolean hasLists = (localFilter != null && !localFilter.isEmpty())
			|| (globalIds != null && !globalIds.isEmpty())
			|| (embeddedIds != null && !embeddedIds.isEmpty());
		if (!hasLists)
		{
			return false;
		}

		double[] v = { p.getVelX(), p.getVelY(), p.getVelZ() };
		boolean velocityUpdated = false;
		boolean pathGuided = false;
		boolean despawn = false;
		float tickDelta = dt * 50f;

		if (activeEffectorsById != null && !activeEffectorsById.isEmpty())
		{
			if (localFilter != null)
			{
				for (String effectorId : localFilter)
				{
					List<ActiveEffectorState> states = activeEffectorsById.get(effectorId);
					if (states == null)
					{
						continue;
					}
					for (ActiveEffectorState state : states)
					{
						EffectorDefinition def = state.getDef();
						if (def == null || def.scope == 1)
						{
							continue;
						}
						int flags = applyWorldEffector(p, tickDelta, state, def, v);
						velocityUpdated |= (flags & EFFECT_VELOCITY) != 0;
						pathGuided |= (flags & EFFECT_PATH_GUIDED) != 0;
						despawn |= (flags & EFFECT_DESPAWN) != 0;
					}
				}
			}

			if (globalIds != null)
			{
				for (String effectorId : globalIds)
				{
					List<ActiveEffectorState> states = activeEffectorsById.get(effectorId);
					if (states == null)
					{
						continue;
					}
					for (ActiveEffectorState state : states)
					{
						EffectorDefinition def = state.getDef();
						if (def == null)
						{
							continue;
						}
						int flags = applyWorldEffector(p, tickDelta, state, def, v);
						velocityUpdated |= (flags & EFFECT_VELOCITY) != 0;
						pathGuided |= (flags & EFFECT_PATH_GUIDED) != 0;
						despawn |= (flags & EFFECT_DESPAWN) != 0;
					}
				}
			}
		}

		if (embeddedIds != null && effectorDefinitions != null)
		{
			for (String embeddedId : embeddedIds)
			{
				EffectorDefinition def = effectorDefinitions.getDefinition(embeddedId);
				if (def == null)
				{
					continue;
				}
				for (EffectorEffect effect : def.effects)
				{
					velocityUpdated |= applyEmbeddedEffect(p, tickDelta, effect, v);
				}
			}
		}

		if (despawn)
		{
			return true;
		}

		if (velocityUpdated)
		{
			p.setVelocity((float) v[0], (float) v[1], (float) v[2]);
		}

		if (pathGuided)
		{
			p.markPathGuided();
		}
		return false;
	}

	private static int applyWorldEffector(
		Particle p,
		float tickDelta,
		ActiveEffectorState state,
		EffectorDefinition def,
		double[] v
	)
	{
		double dx = p.getX() - state.getX();
		double dy = p.getY() - state.getY();
		double dz = p.getZ() - state.getZ();
		double zoneRadius = def.maxRangeSq < Long.MAX_VALUE / 2L ? Math.sqrt(def.maxRangeSq) : 0.0;
		boolean whirlpoolZone = false;
		for (EffectorEffect effect : def.effects)
		{
			if (effect.whirlpool)
			{
				whirlpoolZone = true;
				break;
			}
		}

		if (whirlpoolZone)
		{
			if (zoneRadius <= 0.0)
			{
				return 0;
			}
			double horizSq = dx * dx + dy * dy;
			if (horizSq > def.maxRangeSq)
			{
				p.clearHelix();
				return 0;
			}
			double skyZ = state.getZ() - zoneRadius * 0.85;
			double groundZ = state.getZ() + zoneRadius * 0.85;
			if (p.getZ() < skyZ - zoneRadius * 0.35 || p.getZ() > groundZ + zoneRadius * 0.15)
			{
				p.clearHelix();
				return 0;
			}
		}
		else if (isWindOnly(def) && zoneRadius > 0.0)
		{
			double horizSq = dx * dx + dy * dy;
			if (horizSq > def.maxRangeSq)
			{
				return 0;
			}
		}
		else
		{
			double distSq = dx * dx + dy * dy + dz * dz;
			if (distSq > def.maxRangeSq)
			{
				return 0;
			}
		}

		double horizDist = Math.sqrt(dx * dx + dy * dy);
		double distSq = dx * dx + dy * dy + dz * dz;
		double dist = isWindOnly(def) ? Math.max(1.0, horizDist) : Math.sqrt(distSq);
		if (dist == 0.0)
		{
			dist = 1.0;
		}

		int flags = 0;
		EffectorEffect whirlpoolEffect = findWhirlpoolEffect(def);
		boolean whirlpoolGuides = whirlpoolEffect != null && !isImmuneToEffect(p.getEffectorSeed(), whirlpoolEffect);
		for (EffectorEffect effect : def.effects)
		{
			if (whirlpoolGuides && !effect.whirlpool)
			{
				continue;
			}
			flags |= applyEffect(p, tickDelta, dx, dy, dz, dist, zoneRadius, effect, v,
				state.getX(), state.getY(), state.getZ());
		}
		if (whirlpoolEffect != null && !whirlpoolGuides && zoneRadius > 0.0)
		{
			double coreRadius = zoneRadius * 0.12;
			if (distSq <= coreRadius * coreRadius)
			{
				flags |= EFFECT_DESPAWN;
			}
		}
		return flags;
	}

	@Nullable
	private static EffectorEffect findWhirlpoolEffect(EffectorDefinition def)
	{
		for (EffectorEffect effect : def.effects)
		{
			if (effect.whirlpool)
			{
				return effect;
			}
		}
		return null;
	}

	private static boolean isWindOnly(EffectorDefinition def)
	{
		if (def.effects == null || def.effects.isEmpty())
		{
			return false;
		}
		for (EffectorEffect effect : def.effects)
		{
			if (effect.type != EffectorEffect.EffectType.WIND)
			{
				return false;
			}
		}
		return true;
	}

	private static boolean applyEmbeddedEffect(
		Particle p,
		float tickDelta,
		EffectorEffect effect,
		double[] v
	)
	{
		if (!(effect instanceof PushEffect)
			|| effect.signedMagnitude == 0
			|| isImmuneToEffect(p.getEffectorSeed(), effect))
		{
			return false;
		}
		PushEffect push = (PushEffect) effect;
		double strengthScale = effectStrengthScale(push);
		if (strengthScale <= 0.0)
		{
			return false;
		}
		double intensity = push.intensity > 0f ? push.intensity : 1.0;
		double scale = intensity * strengthScale * tickDelta * PUSH_FORCE_SCALE / 4096.0;
		if (!push.positionMode)
		{
			v[0] += push.forceDirectionX * scale;
			v[1] += push.forceDirectionY * scale;
			v[2] += push.forceDirectionZ * scale;
		}
		else
		{
			p.addPosition(
				(float) (push.forceDirectionX * scale),
				(float) (push.forceDirectionY * scale),
				(float) (push.forceDirectionZ * scale));
		}
		return true;
	}

	private static int applyEffect(
		Particle p,
		float tickDelta,
		double dx,
		double dy,
		double dz,
		double dist,
		double zoneRadius,
		EffectorEffect effect,
		double[] v,
		float centerX,
		float centerY,
		float centerZ
	)
	{
		int seed = p.getEffectorSeed();
		if (isImmuneToEffect(seed, effect))
		{
			return 0;
		}
		if (effect instanceof WindEffect)
		{
			WindEffect wind = (WindEffect) effect;
			if (!wind.hasWindDirection)
			{
				return 0;
			}
			double strengthScale = effectStrengthScale(wind);
			if (strengthScale <= 0.0)
			{
				return 0;
			}
			double intensity = wind.intensity > 0f ? wind.intensity : 1.0;
			return applyWindEffect(p, tickDelta, dist, zoneRadius, wind, intensity, strengthScale, v);
		}
		if (effect.signedMagnitude == 0)
		{
			return 0;
		}

		double strengthScale = effectStrengthScale(effect);
		if (strengthScale <= 0.0)
		{
			return 0;
		}

		if (effect instanceof WhirlpoolEffect)
		{
			WhirlpoolEffect whirlpool = (WhirlpoolEffect) effect;
			boolean complete = applyWhirlpool(p, tickDelta, dx, dy, dz, zoneRadius, whirlpool, strengthScale, v,
				centerX, centerY, centerZ);
			if (whirlpool.hasTargetColor)
			{
				double pathT = p.isHelixLatched() ? p.getHelixT() : 0.0;
				applyEffectColor(p, whirlpool, pathT * strengthScale, tickDelta);
			}
			int flags = EFFECT_VELOCITY | EFFECT_PATH_GUIDED;
			if (complete)
			{
				flags |= EFFECT_DESPAWN;
			}
			return flags;
		}

		if (effect instanceof PushEffect)
		{
			PushEffect push = (PushEffect) effect;
			double intensity = push.intensity > 0f ? push.intensity : 1.0;
			double mag = Math.abs(push.signedMagnitude);
			if (mag > 0)
			{
				double dot = (dx * push.forceDirectionX + dy * push.forceDirectionY + dz * push.forceDirectionZ)
					* 65535.0 / (mag * dist);
				if (dot < push.coneAngleCosine)
				{
					return 0;
				}
			}
			double scale = intensity * strengthScale * tickDelta * PUSH_FORCE_SCALE / 4096.0;
			if (!push.positionMode)
			{
				v[0] += push.forceDirectionX * scale;
				v[1] += push.forceDirectionY * scale;
				v[2] += push.forceDirectionZ * scale;
			}
			else
			{
				p.addPosition(
					(float) (push.forceDirectionX * scale),
					(float) (push.forceDirectionY * scale),
					(float) (push.forceDirectionZ * scale));
			}
			applyEffectColor(p, push, strengthScale, tickDelta);
			return EFFECT_VELOCITY;
		}

		if (effect.radial)
		{
			boolean repel = effect.type == EffectorEffect.EffectType.REPEL;
			boolean attract = effect.type == EffectorEffect.EffectType.ATTRACT;
			boolean skyFunnel = attract && dz < 0;
			boolean horizontalRadial = repel || skyFunnel;

			double horizDist = Math.sqrt(dx * dx + dy * dy);
			double radialDist = horizontalRadial ? horizDist : dist;
			if (radialDist < 1.0)
			{
				radialDist = 1.0;
			}

			double forceScale = radialForceScale(effect, radialDist, zoneRadius);
			if (repel)
			{
				forceScale *= repelHeightFalloff(dz, zoneRadius);
				if (forceScale <= 0.0)
				{
					return 0;
				}
			}
			else if (skyFunnel)
			{
				forceScale *= skyFunnelStrength(horizDist, zoneRadius);
			}

			double magnitude = effect.signedMagnitude * forceScale * strengthScale
				* tickDelta * RADIAL_FORCE_SCALE / 4096.0;
			double rx;
			double ry;
			double rz;
			if (repel && horizDist < 64.0)
			{
				double blastAngle = stableParticleAngle(seed);
				rx = Math.cos(blastAngle) * magnitude;
				ry = Math.sin(blastAngle) * magnitude;
				rz = 0.0;
			}
			else if (horizontalRadial)
			{
				rx = dx / radialDist * magnitude;
				ry = dy / radialDist * magnitude;
				rz = 0.0;
			}
			else
			{
				rx = dx / dist * magnitude;
				ry = dy / dist * magnitude;
				rz = dz / dist * magnitude;
			}
			if (!effect.positionMode)
			{
				v[0] += rx;
				v[1] += ry;
				v[2] += rz;
			}
			else
			{
				p.addPosition((float) rx, (float) ry, (float) rz);
			}
			applyEffectColor(p, effect, forceScale * strengthScale, tickDelta);
			return EFFECT_VELOCITY;
		}
		return 0;
	}

	private static void applyEffectColor(Particle p, EffectorEffect effect, double weight, float tickDelta)
	{
		if (!effect.hasTargetColor || weight <= 0.0)
		{
			return;
		}

		double blend;
		if (effect.resolvedColorBlend == EffectorEffect.ColorBlendMode.PATH)
		{
			blend = Math.min(1.0, weight);
		}
		else
		{
			blend = weight * (1.0 - Math.exp(-6.0 * tickDelta / 50.0));
			blend = Math.min(1.0, blend);
		}
		if (blend <= 0.0)
		{
			return;
		}

		int src = p.getSpawnColorArgb() != 0 ? p.getSpawnColorArgb() : p.getStyle().getStartArgb();
		int sr = (src >> 16) & 0xff;
		int sg = (src >> 8) & 0xff;
		int sb = src & 0xff;
		int sa = (src >>> 24) & 0xff;
		int tr = effect.targetRed16 >> 8;
		int tg = effect.targetGreen16 >> 8;
		int tb = effect.targetBlue16 >> 8;
		int ta = effect.targetAlpha16 >> 8;
		int r = sr + (int) ((tr - sr) * blend);
		int g = sg + (int) ((tg - sg) * blend);
		int b = sb + (int) ((tb - sb) * blend);
		int a = sa + (int) ((ta - sa) * blend);
		p.setSpawnColorArgb(((a & 0xff) << 24) | ((r & 0xff) << 16) | ((g & 0xff) << 8) | (b & 0xff));
	}

	private static int applyWindEffect(
		Particle p,
		float tickDelta,
		double dist,
		double zoneRadius,
		WindEffect effect,
		double intensity,
		double strengthScale,
		double[] v
	)
	{
		if (zoneRadius > 0.0 && dist > zoneRadius)
		{
			return 0;
		}

		double zoneWeight = 1.0;
		if (zoneRadius > 0.0 && effect.edgeFalloff)
		{
			double depth = 1.0 - dist / zoneRadius;
			if (depth <= 0.0)
			{
				return 0;
			}
			float power = effect.falloffPower > 0f ? effect.falloffPower : 1.15f;
			zoneWeight = Math.pow(depth, power);
			zoneWeight = zoneWeight * zoneWeight * (3.0 - 2.0 * zoneWeight);
		}

		double fx = effect.windDirX;
		double fy = effect.windDirY;
		double fz = effect.windDirZ;
		int seed = p.getEffectorSeed();
		double dt = tickDelta / 50.0;
		double time = System.nanoTime() * 1e-9;
		double sx = p.getX() * 0.0018;
		double sy = p.getY() * 0.0018;
		double sz = p.getZ() * 0.0012;

		double variance = effect.directionVarianceScale;
		if (variance > 0.0)
		{
			double meander = Math.sin(time * 0.55) * 0.55 + Math.sin(time * 0.23 + 1.7) * 0.4
				+ Math.sin(time * 1.1 + sx) * 0.25;
			double yaw = meander * variance
				+ (stableParticleUnit(seed, 7) - 0.5) * variance * 0.35;
			double cos = Math.cos(yaw);
			double sin = Math.sin(yaw);
			double rx = fx * cos - fy * sin;
			double ry = fx * sin + fy * cos;
			fx = rx;
			fy = ry;
			fz += Math.sin(time * 0.48 + seed * 0.01) * variance * 0.2;
		}

		double turb = effect.turbulenceScale;
		if (turb > 0.0)
		{
			double n1 = Math.sin(sx * 2.3 + time * 1.45) * Math.cos(sy * 1.9 - time * 1.05);
			double n2 = Math.sin(sy * 2.1 - time * 1.25) * Math.cos(sx * 1.7 + time * 1.15);
			double n3 = Math.sin((sx + sy) * 1.4 + time * 1.85 + sz);
			double n4 = Math.sin(sx * 4.1 - time * 3.2) * Math.cos(sy * 3.7 + time * 2.6);
			double n5 = Math.cos(sy * 3.4 + time * 3.4) * Math.sin(sx * 3.9 - time * 2.3);
			double n6 = Math.sin(sx * 6.2 + sy * 5.1 + time * 4.5);
			fx += (n1 * 0.7 + n4 * 0.65 + n6 * 0.4) * turb;
			fy += (n2 * 0.7 + n5 * 0.65 - n6 * 0.35) * turb;
			fz += (n3 * 0.35 + n4 * 0.15 + n5 * 0.12) * turb;
			double flake = (stableParticleUnit(seed, 19) - 0.5) * turb * 0.28;
			fx += flake + Math.sin(time * 5.5 + seed * 0.04) * turb * 0.25;
			fy -= flake * 0.85 + Math.cos(time * 4.8 + seed * 0.03) * turb * 0.25;
		}

		double horiz = Math.sqrt(fx * fx + fy * fy);
		if (horiz > 1e-6)
		{
			fx /= horiz;
			fy /= horiz;
			double liftCap = 0.25 + 0.45 * effect.liftScale;
			fz = Math.max(-liftCap, Math.min(liftCap, fz / Math.max(horiz, 1e-6)));
		}

		double gustAmt = effect.gustScale;
		double gustRate = effect.gustSpeed;
		double gust = 1.0;
		if (gustAmt > 0.0)
		{
			double pulse = 0.35 * Math.sin(time * 0.7 * gustRate)
				+ 0.25 * Math.sin(time * 1.55 * gustRate + sx * 0.6)
				+ 0.18 * Math.sin(time * 2.9 * gustRate + sy * 0.5)
				+ 0.14 * Math.sin(time * 5.2 * gustRate + sx + sy)
				+ 0.1 * Math.sin(time * 8.0 * gustRate);
			gust = 1.0 + pulse * gustAmt;
			double gustMin = Math.max(0.15, 1.0 - 0.75 * gustAmt);
			double gustMax = 1.0 + 0.95 * gustAmt;
			gust = Math.max(gustMin, Math.min(gustMax, gust));
		}

		double units = effect.speedUnits > 0f ? effect.speedUnits : DEFAULT_WIND_SPEED_UNITS;
		double windSpeed = units * effect.speed * intensity * strengthScale * zoneWeight * gust;
		double targetVx = fx * windSpeed;
		double targetVy = fy * windSpeed;

		double resp = (effect.response > 0f ? effect.response : DEFAULT_WIND_RESPONSE)
			* (0.75 + 0.45 * effect.speed) * intensity * strengthScale;
		double blend = 1.0 - Math.exp(-resp * dt);
		blend = Math.min(effect.maxBlend, blend) * zoneWeight;

		v[0] += (targetVx - v[0]) * blend;
		v[1] += (targetVy - v[1]) * blend;

		if (effect.liftScale > 0.0)
		{
			double buffet = (gust - 1.0) * windSpeed * 0.16 * effect.liftScale
				+ fz * windSpeed * 0.28 * effect.liftScale;
			double flakeBob = (Math.sin(time * 4.2 + seed * 0.023) * turb * 0.12
				+ Math.cos(time * 6.1 + seed * 0.017) * turb * 0.08) * windSpeed * effect.liftScale;
			v[2] += (buffet + flakeBob) * blend;
		}

		applyEffectColor(p, effect, zoneWeight * strengthScale, tickDelta);
		return EFFECT_VELOCITY;
	}

	private static boolean applyWhirlpool(
		Particle p,
		float tickDelta,
		double dx,
		double dy,
		double dz,
		double zoneRadius,
		WhirlpoolEffect effect,
		double strengthScale,
		double[] v,
		float centerX,
		float centerY,
		float centerZ
	)
	{
		if (zoneRadius <= 0.0)
		{
			return false;
		}

		double activity = strengthScale;
		double spinSign = effect.clockwise ? -1.0 : 1.0;
		boolean inverted = effect.inverted;
		int seed = p.getEffectorSeed();

		double topOffset = zoneRadius * 0.85;
		double skyZ = centerZ - topOffset;
		double groundZ = centerZ + topOffset;
		double helixSpan = groundZ - skyZ;

		int coilIndex = Math.floorMod(seed * 0x9E3779B9, 3);
		double phase = coilIndex * (Math.PI * 2.0 / 3.0);
		double entryGeomT = inverted ? 1.0 : 0.0;
		double entryAngle = phase + entryGeomT * Math.PI * 3.0 * spinSign;
		double entryRingR = zoneRadius * 0.65 * (1.0 - entryGeomT * 0.35);
		double[] entryHelix = applyHelixVariation(effect, seed, 0.0, entryAngle, entryRingR, entryGeomT);
		entryAngle = entryHelix[0];
		entryRingR = entryHelix[1];
		entryGeomT = entryHelix[2];

		double oldX = centerX + dx;
		double oldY = centerY + dy;
		double oldZ = centerZ + dz;

		double entryX = centerX + Math.cos(entryAngle) * entryRingR;
		double entryY = centerY + Math.sin(entryAngle) * entryRingR;
		double entryZ = skyZ + entryGeomT * helixSpan;

		double toEntryX = entryX - oldX;
		double toEntryY = entryY - oldY;
		double toEntryZ = entryZ - oldZ;
		double distToEntrySq = toEntryX * toEntryX + toEntryY * toEntryY + toEntryZ * toEntryZ;
		double latchRadius = zoneRadius * 0.18;
		double latchRadiusSq = latchRadius * latchRadius;

		boolean latched = p.isHelixLatched();
		double t = p.getHelixT();

		double newX;
		double newY;
		double newZ;

		if (!latched)
		{
			double pull = Math.min(1.0, 0.10 * activity * tickDelta + (effect.signedMagnitude / 24000.0) * activity);
			newX = oldX + toEntryX * pull;
			newY = oldY + toEntryY * pull;
			newZ = oldZ + toEntryZ * pull;
			if (distToEntrySq <= latchRadiusSq)
			{
				latched = true;
				t = 0.0;
				newX = entryX;
				newY = entryY;
				newZ = entryZ;
			}
		}
		else
		{
			if (t >= 1.0)
			{
				p.setHelix(latched, (float) t);
				return true;
			}
			double dt = (effect.sinkStrength / (70.0 * helixSpan)) * activity * tickDelta;
			double travelT = Math.min(1.0, t + dt);
			double geomT = inverted ? (1.0 - travelT) : travelT;
			double angle = phase + geomT * Math.PI * 3.0 * spinSign;
			double ringR = zoneRadius * 0.65 * (1.0 - geomT * 0.35);
			double[] pathHelix = applyHelixVariation(effect, seed, travelT, angle, ringR, geomT);
			angle = pathHelix[0];
			ringR = pathHelix[1];
			geomT = pathHelix[2];
			newX = centerX + Math.cos(angle) * ringR;
			newY = centerY + Math.sin(angle) * ringR;
			newZ = skyZ + geomT * helixSpan;
			t = travelT;
		}

		p.setHelix(latched, (float) t);
		p.setPosition((float) newX, (float) newY, (float) newZ);

		double invDt = tickDelta > 0 ? 50.0 / tickDelta : 50.0;
		v[0] = (newX - oldX) * invDt;
		v[1] = (newY - oldY) * invDt;
		v[2] = (newZ - oldZ) * invDt;
		return latched && t >= 1.0;
	}

	private static double[] applyHelixVariation(
		WhirlpoolEffect effect,
		int particleIndex,
		double travelT,
		double angle,
		double ringR,
		double geomT
	)
	{
		double variation = effect.pathVariationScale;
		if (variation <= 0f)
		{
			return new double[] { angle, ringR, geomT };
		}

		double u1 = stableParticleUnit(particleIndex, 11) * 2.0 - 1.0;
		double u2 = stableParticleUnit(particleIndex, 29) * 2.0 - 1.0;
		double u3 = stableParticleUnit(particleIndex, 47) * 2.0 - 1.0;
		double wobble = Math.sin(travelT * Math.PI * 5.0 + stableParticleAngle(particleIndex)) * variation;

		double variedAngle = angle + u1 * variation * 0.35 + wobble * 0.18;
		double variedRingR = ringR * (1.0 + u2 * variation * 0.14 + wobble * 0.06);
		double variedGeomT = Math.max(0.0, Math.min(1.0, geomT + u3 * variation * 0.05 + wobble * 0.025));
		return new double[] { variedAngle, Math.max(0.0, variedRingR), variedGeomT };
	}

	private static double stableParticleUnit(int particleIndex, int salt)
	{
		return ((particleIndex * 1103515245 + salt * 12345) & 0x7fffffff) / (double) 0x7fffffff;
	}

	private static double repelHeightFalloff(double dz, double zoneRadius)
	{
		double band = zoneRadius * 0.32;
		double depth = 1.0 - Math.abs(dz) / band;
		if (depth <= 0.0)
		{
			return 0.0;
		}
		return depth * depth;
	}

	private static double skyFunnelStrength(double horizDist, double zoneRadius)
	{
		if (zoneRadius <= 0.0)
		{
			return 0.0;
		}
		double t = horizDist / (zoneRadius * 0.75);
		return Math.max(0.15, Math.min(1.0, t));
	}

	private static double stableParticleAngle(int particleIndex)
	{
		return ((particleIndex * 1103515245 + 12345) & 0x7fffffff) * (Math.PI * 2.0 / 0x80000000L);
	}

	private static double radialForceScale(EffectorEffect effect, double dist, double zoneRadius)
	{
		if (!effect.radial || !effect.edgeFalloff || zoneRadius <= 0.0)
		{
			return 1.0;
		}
		double depth = 1.0 - dist / zoneRadius;
		if (depth <= 0.0)
		{
			return 0.0;
		}
		double power = effect.falloffPower > 0f ? effect.falloffPower : 1f;
		return Math.pow(depth, power);
	}

	private static double effectStrengthScale(EffectorEffect effect)
	{
		return effect.effectPercent / 100.0;
	}

	private static boolean isImmuneToEffect(int particleIndex, EffectorEffect effect)
	{
		if (effect.immunePercent <= 0f)
		{
			return false;
		}
		int bucket = (particleIndex * 1103515245 + 12345) >>> 1;
		return (bucket % 100) < (int) effect.immunePercent;
	}
}

