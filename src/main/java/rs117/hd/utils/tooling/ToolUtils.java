package rs117.hd.utils.tooling;

import rs117.hd.utils.ColorUtils;

import static rs117.hd.utils.ColorUtils.rgb;

public class ToolUtils {

	public static String getColorValue(float[] color) {
		return ColorUtils.linearToSrgbHex(color != null ? color : ColorUtils.rgb("#FFFFFF"));
	}

	public static boolean castToBoolean(Object value) {
		if (value instanceof Boolean) {
			return (Boolean) value;
		}
		if (value instanceof String) {
			return Boolean.parseBoolean((String) value);
		}
		throw new IllegalArgumentException("Cannot cast to boolean: " + value);
	}

	public static float[] castToFloatArray(Object value) {
		if (value instanceof float[]) {
			return (float[]) value;
		}
		if (value instanceof String) {
			String floatString = (String) value;
			String[] floatStrings = floatString.split(",");
			if (floatStrings.length != 2) {
				throw new IllegalArgumentException("Expected two comma-separated values for float array: " + value);
			}
			try {
				return new float[] {
					Float.parseFloat(floatStrings[0].trim()),
					Float.parseFloat(floatStrings[1].trim())
				};
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("Invalid float array value: " + value, e);
			}
		}
		throw new IllegalArgumentException("Cannot cast to float array: " + value);
	}

	public static float castToFloat(Object value) {
		if (value instanceof Float) {
			return (Float) value;
		}
		if (value instanceof Number) {
			return ((Number) value).floatValue();
		}
		if (value instanceof String) {
			try {
				return Float.parseFloat((String) value);
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("Invalid float value: " + value, e);
			}
		}
		throw new IllegalArgumentException("Cannot cast to float: " + value);
	}

	public static int castToInt(Object value) {
		if (value instanceof Integer) {
			return (Integer) value;
		}
		if (value instanceof Number) {
			return ((Number) value).intValue();
		}
		if (value instanceof String) {
			try {
				return Integer.parseInt((String) value);
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("Invalid int value: " + value, e);
			}
		}
		throw new IllegalArgumentException("Cannot cast to int: " + value);
	}

	public static float[] castToColor(Object value) {
		if (value instanceof float[]) {
			return (float[]) value;
		}
		return rgb(value.toString());
	}

	public static int[] reverseSunAngles(float[] anglesInRadians) {
		if (anglesInRadians == null || anglesInRadians.length != 2) {
			throw new IllegalArgumentException("The input array must have exactly two elements.");
		}
		return new int[] {
			(int) Math.toDegrees(anglesInRadians[0]),
			(int) Math.toDegrees(anglesInRadians[1])
		};
	}
}
