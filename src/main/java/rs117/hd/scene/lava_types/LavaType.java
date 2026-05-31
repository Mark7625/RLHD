package rs117.hd.scene.lava_types;

import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.opengl.uniforms.UBOLavaTypes;
import rs117.hd.scene.LavaTypeManager;
import rs117.hd.utils.ColorUtils;
import rs117.hd.utils.GsonUtils;

import static rs117.hd.utils.ColorUtils.linearToSrgb;
import static rs117.hd.utils.ColorUtils.rgb;

@NoArgsConstructor
@GsonUtils.ExcludeDefaults
@Setter(AccessLevel.PROTECTED)
public class LavaType {
	public String name;
	private float duration = 1;
	private float scale = 4;
	private float crustThreshold = 0.38f;
	private float crustAmount = 0.5f;
	private float crustSharpness = 0.55f;
	private float flowStrength = 2.5f;
	private float emissiveStrength = 1.2f;
	private float specularStrength = 0.3f;
	private float specularGloss = 70;
	private float waveStrength = 0.26f;
	private float surfaceGlow = 0.75f;
	private float irradianceStrength = 1f;
	private float animSpeed = 1f;
	@JsonAdapter(ColorUtils.SrgbToLinearAdapter.class)
	private float[] crustColor = rgb(26, 8, 4);
	@JsonAdapter(ColorUtils.SrgbToLinearAdapter.class)
	private float[] magmaColor = rgb(232, 90, 16);
	@JsonAdapter(ColorUtils.SrgbToLinearAdapter.class)
	private float[] hotColor = rgb(255, 208, 64);

	public transient int index;

	public static final LavaType NONE = new LavaType("NONE");

	private LavaType(String name) {
		this.name = name;
	}

	public void normalize(int index) {
		this.index = index;
		if (name == null)
			name = "UNNAMED_" + index;
		if (crustColor == null)
			crustColor = NONE.crustColor;
		if (magmaColor == null)
			magmaColor = NONE.magmaColor;
		if (hotColor == null)
			hotColor = NONE.hotColor;
	}

	@Override
	public String toString() {
		return name;
	}

	public void fillStruct(UBOLavaTypes.LavaTypeStruct struct) {
		struct.duration.set(duration);
		struct.scale.set(scale);
		struct.crustThreshold.set(crustThreshold);
		struct.crustAmount.set(crustAmount);
		struct.crustSharpness.set(crustSharpness);
		struct.flowStrength.set(flowStrength);
		struct.emissiveStrength.set(emissiveStrength);
		struct.specularStrength.set(specularStrength);
		struct.specularGloss.set(specularGloss);
		struct.waveStrength.set(waveStrength);
		struct.surfaceGlow.set(surfaceGlow);
		struct.irradianceStrength.set(irradianceStrength);
		struct.animSpeed.set(animSpeed);
		struct.crustColor.set(linearToSrgb(crustColor));
		struct.magmaColor.set(linearToSrgb(magmaColor));
		struct.hotColor.set(linearToSrgb(hotColor));
	}

	@Slf4j
	public static class Adapter extends TypeAdapter<LavaType> {
		@Override
		public LavaType read(JsonReader in) throws IOException {
			if (in.peek() == JsonToken.NULL)
				return null;

			if (in.peek() == JsonToken.STRING) {
				String name = in.nextString();
				for (var match : LavaTypeManager.LAVA_TYPES)
					if (name.equals(match.name))
						return match;

				log.warn("No lava type exists with the name '{}' at {}", name, GsonUtils.location(in), new Throwable());
			} else {
				log.warn("Unexpected type {} at {}", in.peek(), GsonUtils.location(in), new Throwable());
			}

			return null;
		}

		@Override
		public void write(JsonWriter out, LavaType lavaType) throws IOException {
			if (lavaType == null) {
				out.nullValue();
			} else {
				out.value(lavaType.name);
			}
		}
	}
}
