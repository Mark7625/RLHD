package rs117.hd.particles.effector;

import javax.annotation.Nullable;

public class EffectorEffectSlot {
	@Nullable
	public WindEffect wind;
	@Nullable
	public PushEffect push;
	@Nullable
	public AttractEffect attract;
	@Nullable
	public RepelEffect repel;
	@Nullable
	public WhirlpoolEffect whirlpool;

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

	public static EffectorEffectSlot from(EffectorEffect effect) {
		EffectorEffectSlot slot = new EffectorEffectSlot();
		if (effect instanceof WindEffect) {
			slot.wind = (WindEffect) effect;
		} else if (effect instanceof PushEffect) {
			slot.push = (PushEffect) effect;
		} else if (effect instanceof AttractEffect) {
			slot.attract = (AttractEffect) effect;
		} else if (effect instanceof RepelEffect) {
			slot.repel = (RepelEffect) effect;
		} else if (effect instanceof WhirlpoolEffect) {
			slot.whirlpool = (WhirlpoolEffect) effect;
		}
		return slot;
	}
}
