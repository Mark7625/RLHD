package rs117.hd.utils.tooling.props.impl;

import java.awt.Color;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.tooling.props.PropertyData;

import static rs117.hd.utils.tooling.ToolUtils.castToBoolean;
import static rs117.hd.utils.tooling.ToolUtils.castToColor;
import static rs117.hd.utils.tooling.ToolUtils.castToFloat;
import static rs117.hd.utils.tooling.ToolUtils.castToFloatArray;
import static rs117.hd.utils.tooling.ToolUtils.castToInt;
import static rs117.hd.utils.tooling.ToolUtils.getColorValue;
import static rs117.hd.utils.tooling.ToolUtils.reverseSunAngles;

public class EnvironmentPropertyRegistry {
    public static final Map<String, PropertyData> PROPERTIES = new LinkedHashMap<>() {{
        put("isUnderwater", new PropertyData(
            "Indicates if the environment is underwater.",
            boolean.class,
            (env, val) -> env.isUnderwater = castToBoolean(val),
            env -> String.valueOf(env.isUnderwater),
            "General"
        ));
        put("allowSkyOverride", new PropertyData(
            "Allows overriding the sky.",
            boolean.class,
            (env, val) -> env.allowSkyOverride = castToBoolean(val),
            env -> String.valueOf(env.allowSkyOverride),
            "General"
        ));
        put("lightningEffects", new PropertyData(
            "Enables lightning effects.",
            boolean.class,
            (env, val) -> env.lightningEffects = castToBoolean(val),
            env -> String.valueOf(env.lightningEffects),
            "Lighting"
        ));
        put("instantTransition", new PropertyData(
            "Enables instant Transitions.",
            boolean.class,
            (env, val) -> env.instantTransition = castToBoolean(val),
            env -> String.valueOf(env.instantTransition),
            "General"
        ));
        put("ambientColor", new PropertyData(
            "Ambient light color in sRGB, specified as a hex color code or an array.",
            Color.class,
            (env, val) -> env.ambientColor = castToColor(val),
            env -> getColorValue(env.ambientColor),
            "Lighting"
        ));
        put("ambientStrength", new PropertyData(
            "Ambient light strength multiplier. Defaults to 1.",
            float.class,
            (env, val) -> env.ambientStrength = castToFloat(val),
            env -> String.valueOf(env.ambientStrength),
            "Lighting"
        ));
        put("directionalColor", new PropertyData(
            "Directional light color in sRGB, specified as a hex color code or an array.",
            Color.class,
            (env, val) -> env.directionalColor = castToColor(val),
            env -> getColorValue(env.directionalColor),
            "Lighting"
        ));
        put("directionalStrength", new PropertyData(
            "Directional light strength multiplier. Defaults to 0.25.",
            float.class,
            (env, val) -> env.directionalStrength = castToFloat(val),
            env -> String.valueOf(env.directionalStrength),
            "Lighting"
        ));
        put("waterColor", new PropertyData(
            "Water color in sRGB, specified as a hex color code or an array.",
            Color.class,
            (env, val) -> env.waterColor = castToColor(val),
            env -> getColorValue(env.waterColor),
            "Water"
        ));
        put("waterCausticsColor", new PropertyData(
            "Water caustics color in sRGB (as hex or array). Defaults to the environment's directional light color.",
            Color.class,
            (env, val) -> env.waterCausticsColor = castToColor(val),
            env -> getColorValue(env.waterCausticsColor),
            "Water"
        ));
        put("waterCausticsStrength", new PropertyData(
            "Water caustics strength. Defaults to the environment's directional light strength.",
            float.class,
            (env, val) -> env.waterCausticsStrength = castToFloat(val),
            env -> String.valueOf(env.waterCausticsStrength),
            "Water"
        ));
        put("underglowColor", new PropertyData(
            "Underglow color in sRGB (as hex or array). Acts as light emanating from the ground.",
            Color.class,
            (env, val) -> env.underglowColor = castToColor(val),
            env -> getColorValue(env.underglowColor),
            "Lighting"
        ));
        put("underglowStrength", new PropertyData(
            "Underglow strength multiplier. Acts as light emanating from the ground.",
            float.class,
            (env, val) -> env.underglowStrength = castToFloat(val),
            env -> String.valueOf(env.underglowStrength),
            "Lighting"
        ));
        put("sunAngles", new PropertyData(
            "The sun's altitude and azimuth specified in degrees in the horizontal coordinate system.",
            int[].class,
            (env, val) -> env.sunAngles = HDUtils.sunAngles(castToFloatArray(val)[0],castToFloatArray(val)[1]),
            env -> Arrays.toString(reverseSunAngles(env.sunAngles ==  null ? HDUtils.sunAngles(52, 235) : env.sunAngles)),
            "Lighting"
        ));
        put("fogColor", new PropertyData(
            "Sky/fog color in sRGB, specified as a hex color code or an array.",
            Color.class,
            (env, val) -> env.fogColor = castToColor(val),
            env -> getColorValue(env.fogColor),
            "Fog"
        ));
        put("fogDepth", new PropertyData(
            "Fog depth normally ranging from 0 to 100, which combined with draw distance decides the fog amount. Defaults to 25.",
            float.class,
            (env, val) -> env.fogDepth = castToFloat(val),
            env -> String.valueOf(env.fogDepth),
            "Fog"
        ));
        put("groundFogStart", new PropertyData(
            "Only matters with groundFogOpacity > 0. Specified in local units.",
            int.class,
            (env, val) -> env.groundFogStart = castToInt(val),
            env -> String.valueOf(env.groundFogStart),
            "Fog"
        ));
        put("groundFogEnd", new PropertyData(
            "Only matters with groundFogOpacity > 0. Specified in local units.",
            int.class,
            (env, val) -> env.groundFogEnd = castToInt(val),
            env -> String.valueOf(env.groundFogEnd),
            "Fog"
        ));
        put("groundFogOpacity", new PropertyData(
            "Ground fog opacity ranging from 0 to 1, meaning no ground fog and full ground fog respectively. Defaults to 0.",
            int.class,
            (env, val) -> env.groundFogOpacity = castToInt(val),
            env -> String.valueOf(env.groundFogOpacity),
            "Fog"
        ));
        put("windAngle", new PropertyData(
            "Wind angle in degrees (horizontal direction).",
            float.class,
            (env, val) -> env.windAngle = castToFloat(val),
            env -> String.valueOf(env.windAngle),
            "Wind"
        ));
        put("windSpeed", new PropertyData(
            "Wind speed (units per second).",
            float.class,
            (env, val) -> env.windSpeed = castToFloat(val),
            env -> String.valueOf(env.windSpeed),
            "Wind"
        ));
        put("windStrength", new PropertyData(
            "Wind strength (multiplier).",
            float.class,
            (env, val) -> env.windStrength = castToFloat(val),
            env -> String.valueOf(env.windStrength),
            "Wind"
        ));
        put("windCeiling", new PropertyData(
            "Wind ceiling (height limit for wind effects).",
            float.class,
            (env, val) -> env.windCeiling = castToFloat(val),
            env -> String.valueOf(env.windCeiling),
            "Wind"
        ));
    }};
} 