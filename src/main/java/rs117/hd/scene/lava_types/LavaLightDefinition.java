package rs117.hd.scene.lava_types;

import rs117.hd.scene.lights.LightDefinition;

import static net.runelite.api.Perspective.LOCAL_TILE_SIZE;
import static rs117.hd.utils.MathUtils.max;

public class LavaLightDefinition extends LightDefinition {
	/**
	 * Minimum distance between auto-placed lights in local units.
	 * Defaults to {@link #radius} when unset.
	 */
	public float spacing;

	public int getSpacing() {
		if (spacing > 0)
			return (int) spacing;
		return max(LOCAL_TILE_SIZE * 3, (int) (radius * 1.5f));
	}

	public LavaLightDefinition instantiate(String lavaTypeName) {
		LavaLightDefinition copy = new LavaLightDefinition();
		copy.description = "LIGHT_" + lavaTypeName;
		copy.alignment = alignment;
		copy.offset = offset != null ? offset.clone() : new float[3];
		copy.height = height;
		copy.radius = radius;
		copy.strength = strength;
		copy.color = color != null ? color.clone() : new float[3];
		copy.type = type;
		copy.duration = duration;
		copy.range = range;
		copy.fadeInDuration = fadeInDuration;
		copy.fadeOutDuration = fadeOutDuration;
		copy.spacing = spacing;
		return copy;
	}
}
