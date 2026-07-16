package rs117.hd.particles.effector;

import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

import static net.runelite.api.Perspective.LOCAL_TILE_SIZE;

public class EffectorDefinition {
	@Nullable
	public String id;

	@Nullable
	public int[][] placements;

	public float radiusTiles;

	public int heightOffset;

	public int scope;

	@SerializedName("effects")
	@Nullable
	public List<EffectorEffectSlot> effectSlots;

	public transient List<EffectorEffect> effects = Collections.emptyList();

	public transient long maxRangeSq;
	public transient float radiusLocal;

	static final int[] cosineTable = new int[16384];

	static {
		final double d = 3.834951969714103E-4D;
		for (int i = 0; i < 16384; i++) {
			cosineTable[i] = (int) (16384.0D * Math.cos(i * d));
		}
	}

	public void postDecode() {
		if (effectSlots != null && !effectSlots.isEmpty()) {
			List<EffectorEffect> resolved = new ArrayList<>(effectSlots.size());
			for (EffectorEffectSlot slot : effectSlots) {
				if (slot == null) {
					continue;
				}
				EffectorEffect effect = slot.resolve();
				if (effect == null) {
					continue;
				}
				effect.postDecode();
				resolved.add(effect);
			}
			effects = resolved;
		} else if (effects == null) {
			effects = Collections.emptyList();
		} else {
			for (EffectorEffect effect : effects) {
				effect.postDecode();
			}
		}

		if (radiusTiles > 0f) {
			radiusLocal = radiusTiles * LOCAL_TILE_SIZE;
			long range = (long) radiusLocal;
			maxRangeSq = range * range;
		} else {
			radiusLocal = 0f;
			maxRangeSq = Long.MAX_VALUE;
		}
	}

	public boolean isUnlimitedRange() {
		return maxRangeSq >= Long.MAX_VALUE / 2L;
	}
}
