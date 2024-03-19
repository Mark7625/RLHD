package rs117.hd.tooling.enviroment;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Objects;
import rs117.hd.data.environments.Area;
import rs117.hd.scene.environments.Environment;
import rs117.hd.utils.ColorUtils;

import static rs117.hd.utils.ColorUtils.rgb;
import static rs117.hd.utils.HDUtils.sunAngles;

public class EnvironmentSerializer implements JsonSerializer<Environment> {

	private static final float EPSILON = .005f;

	@Override
	public JsonElement serialize(Environment src, Type typeOfSrc, JsonSerializationContext context) {
		JsonObject jsonObject = new JsonObject();


			if (src.key != null) {
				jsonObject.addProperty("key", src.key);
			}

			if (src.area != null && src.area != Area.NONE) {
				jsonObject.addProperty("area", src.area.toString());
			}

			// Serialize each field only if it's not equal to its default value
			if (src.isUnderwater) {
				jsonObject.addProperty("isUnderwater", true);
			}
			if (!src.allowSkyOverride) {
				jsonObject.addProperty("allowSkyOverride", false);
			}
			if (src.lightningEffects) {
				jsonObject.addProperty("lightningEffects", true);
			}

			// Serialize array fields only if they are not null and not equal to default values
			if (src.ambientColor != null && !Arrays.equals(src.ambientColor, rgb("#ffffff"))) {
				jsonObject.add("ambientColor", serializeColor(src.ambientColor, context));
			}
			if (src.ambientStrength != 1) {
				jsonObject.add("ambientStrength", serializeFloat(src.ambientStrength));
			}
			if (src.directionalColor != null && !Arrays.equals(src.directionalColor, rgb("#ffffff"))) {
				jsonObject.add("directionalColor", serializeColor(src.directionalColor, context));
			}
			if (src.directionalStrength != .25f) {
				jsonObject.add("directionalStrength", serializeFloat(src.directionalStrength));
			}

			// Skip serialization of "waterCausticsColor" if it equals env.getDirectionalColor()
			if (!Arrays.equals(src.waterCausticsColor, src.directionalColor)) {
				jsonObject.add("waterCausticsColor", serializeColor(src.waterCausticsColor, context));
			}

			if (src.sunAngles != null && !Arrays.equals(src.sunAngles, sunAngles(52, 235))) {
				jsonObject.add("sunAngles", serializeSunAngles(src.sunAngles));
			}

			if (src.fogColor != null && !Arrays.equals(src.fogColor, new float[] { 0.0F, 0.0F, 0.0F })) {
				jsonObject.add("fogColor", serializeColor(src.fogColor, context));
			}

			if (src.fogDepth != 25) {
				jsonObject.add("fogDepth", serializeFloat(src.fogDepth));
			}

			if (src.waterColor != null && !Arrays.equals(src.waterColor, rgb("#66eaff"))) {
				jsonObject.add("waterColor", serializeColor(src.waterColor, context));
			}
			
			if (src.underglowColor != null && !Arrays.equals(src.underglowColor, rgb("#000000"))) {
				jsonObject.add("underglowColor", serializeColor(src.underglowColor, context));
			}
			if (src.underglowStrength != 0) {
				jsonObject.add("underglowStrength", serializeFloat(src.underglowStrength));
			}

			// Skip serialization of "waterCausticsStrength" if it equals env.getDirectionalStrength()
			if (Math.abs(src.waterCausticsStrength - src.directionalStrength) >= EPSILON) {
				jsonObject.add("waterCausticsStrength", serializeFloat(src.waterCausticsStrength));
			}

			if (src.groundFogStart != -200) {
				jsonObject.addProperty("groundFogStart", src.groundFogStart);
			}
			if (src.groundFogEnd != -500) {
				jsonObject.addProperty("groundFogEnd", src.groundFogEnd);
			}
			if (src.groundFogOpacity != 0) {
				jsonObject.add("groundFogOpacity", serializeFloat(src.groundFogOpacity));
			}


		return jsonObject;
	}

	private JsonElement serializeColor(float[] color, JsonSerializationContext context) {
		// Implementation for color serialization
		if (color == null) {
			return null;
		}

		// Convert to sRGB and then to a hex string if possible
		float[] srgb = ColorUtils.linearToSrgb(color);
		for (int i = 0; i < 3; i++) {
			srgb[i] *= 255;
		}

		boolean canFitInHex = true;
		int[] c = new int[3];
		for (int i = 0; i < 3; i++) {
			float f = srgb[i];
			c[i] = Math.round(f);
			if (Math.abs(f - c[i]) > EPSILON) {
				canFitInHex = false;
				break;
			}
		}

		if (canFitInHex) {
			return new JsonPrimitive(String.format("#%02x%02x%02x", c[0], c[1], c[2]));
		} else {
			return context.serialize(srgb);
		}
	}

	private JsonArray serializeSunAngles(float[] sunAngles) {
		JsonArray jsonArray = new JsonArray();
		for (float angle : sunAngles) {
			// Convert radians to degrees and round
			float degrees = (float) Math.toDegrees(angle);
			if (Math.abs(Math.round(degrees) - degrees) <= EPSILON) {
				jsonArray.add(new JsonPrimitive(Math.round(degrees))); // Add as integer if close enough
			} else {
				jsonArray.add(new JsonPrimitive(degrees)); // Otherwise, add as float
			}
		}
		return jsonArray;
	}

	private JsonPrimitive serializeFloat(float value) {
		if (Math.abs(Math.round(value) - value) <= EPSILON) {
			return new JsonPrimitive((int) value);
		} else {
			return new JsonPrimitive(value);
		}
	}

}
