package rs117.hd.utils.tooling.props.impl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.scene.environments.Environment;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.ResourcePath;
import rs117.hd.utils.tooling.environment.ToolUtils;
import rs117.hd.utils.tooling.props.PropertyData;

@Slf4j
public class SchemaBasedEnvironmentPropertyRegistry {
    
    private static final Map<String, PropertyData> PROPERTIES = new LinkedHashMap<>();
    private static final String ENVIRONMENTS_JSON_PATH = "src/main/resources/rs117/hd/scene/environments.json";
    private static JsonArray environmentsData;
    
    static {
        loadPropertiesFromSchema();
        loadEnvironmentsData();
    }

    public static Map<String, PropertyData> getProperties() {
        return PROPERTIES;
    }
    
    public static void saveAllEnvironments() {
        saveEnvironmentsData();
    }
    
    public static void reloadEnvironmentsData() {
        loadEnvironmentsData();
    }

    private static void loadPropertiesFromSchema() {
        try (
            InputStream in = ResourcePath.path("schemas/environments.schema.json").toInputStream();
            Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {

            JsonObject schema = new JsonParser().parse(reader).getAsJsonObject();
            JsonObject properties = schema
                .getAsJsonObject("items")
                .getAsJsonObject("properties");

            for (Map.Entry<String, JsonElement> entry : properties.entrySet()) {
                String propertyName = entry.getKey();
                JsonObject propertySchema = entry.getValue().getAsJsonObject();

                if (isInternalProperty(propertyName)) {
                    continue;
                }

                PropertyData propertyData = createPropertyData(propertyName, propertySchema);
                if (propertyData != null) {
                    PROPERTIES.put(propertyName, propertyData);
                }
            }

            log.info("Loaded {} properties from schema", PROPERTIES.size());

        } catch (Exception e) {
            log.error("Failed to load properties from schema", e);
        }
    }
    
    private static boolean isInternalProperty(String propertyName) {
        return propertyName.equals("key") || 
               propertyName.equals("area") || 
               propertyName.equals("description");
    }
    
    private static PropertyData createPropertyData(String propertyName, JsonObject propertySchema) {
        try {
            if (!shouldShowProperty(propertySchema)) {
                return null;
            }
            
            String description = propertySchema.has("description") ? 
                propertySchema.get("description").getAsString() : propertyName;
            String category = determineCategory(propertyName, propertySchema);
			if(category.isEmpty()) {
				category = "?";
			}
            Class<?> type = determineType(propertySchema);
            String editorType = extractEditorType(propertySchema);
            
            BiConsumer<Environment, Object> setter = createSetter(propertyName, type);
            Function<Environment, String> getter = createGetter(propertyName, type);
            
            return new PropertyData(description, type, setter, getter, category, editorType);
            
        } catch (Exception e) {
            log.warn("Failed to create property data for {}", propertyName, e);
            return null;
        }
    }

    private static boolean shouldShowProperty(JsonObject propertySchema) {
        if (propertySchema.has("editor") && propertySchema.get("editor").isJsonObject()) {
            JsonObject editor = propertySchema.getAsJsonObject("editor");
            return !(editor.has("noteditable") && editor.get("noteditable").getAsBoolean());
        }
        return true;
    }
    
    private static String determineCategory(String propertyName, JsonObject propertySchema) {
        if (propertySchema.has("editor") && propertySchema.get("editor").isJsonObject()) {
            JsonObject editor = propertySchema.getAsJsonObject("editor");
            if (editor.has("category")) {
                return editor.get("category").getAsString();
            }
        }

        if (propertySchema.has("category")) {
            return propertySchema.get("category").getAsString();
        }

        return "";
    }

    private static Class<?> determineType(JsonObject propertySchema) {
        if (propertySchema.has("type")) {
            JsonElement typeElement = propertySchema.get("type");
            if (typeElement.isJsonArray()) {
                return getClassFromUnionType(typeElement.getAsJsonArray());
            } else {
                return getClassFromType(typeElement.getAsString());
            }
        }

        if (isColorProperty(propertySchema)) {
            return java.awt.Color.class;
        }

        if (isSunAnglesProperty(propertySchema)) {
            return int[].class;
        }

        String editorType = extractEditorType(propertySchema);
        if (editorType != null) {
            return getClassFromEditorType(editorType);
        }
        
        return String.class;
    }
    
    private static boolean isColorProperty(JsonObject propertySchema) {
        return propertySchema.has("pattern") && 
               propertySchema.has("minItems") && 
               propertySchema.has("maxItems") &&
               propertySchema.get("pattern").getAsString().equals("^#[A-Fa-f0-9]{6}$") &&
               propertySchema.get("minItems").getAsInt() == 3 &&
               propertySchema.get("maxItems").getAsInt() == 3;
    }
    
    private static boolean isSunAnglesProperty(JsonObject propertySchema) {
        if (propertySchema.has("items") && propertySchema.get("items").isJsonArray()) {
            JsonArray items = propertySchema.getAsJsonArray("items");
            if (items.size() == 2) {
                JsonObject first = items.get(0).getAsJsonObject();
                JsonObject second = items.get(1).getAsJsonObject();
                return first.has("minimum") && first.has("maximum") &&
                       second.has("minimum") && second.has("maximum");
            }
        }
        
        if (propertySchema.has("description")) {
            String description = propertySchema.get("description").getAsString();
            return description.contains("sun's altitude and azimuth");
        }
        
        return false;
    }
    
    private static Class<?> getClassFromUnionType(JsonArray typeArray) {
        for (JsonElement type : typeArray) {
            String typeStr = type.getAsString();
            if (!typeStr.equals("null")) {
                return getClassFromType(typeStr);
            }
        }
        return String.class;
    }
    
    private static Class<?> getClassFromType(String type) {
        switch (type) {
            case "boolean": return boolean.class;
            case "number": return float.class;
            case "integer": return int.class;
            case "string": return String.class;
            case "array": return Object.class;
            default: return String.class;
        }
    }
    
    private static Class<?> getClassFromEditorType(String editorType) {
        switch (editorType) {
            case "boolean": return boolean.class;
            case "float": return float.class;
            case "int": return int.class;
            case "int[]": return int[].class;
            case "color": return java.awt.Color.class;
            default: return String.class;
        }
    }
    
    private static String extractEditorType(JsonObject propertySchema) {
        if (propertySchema.has("editor") && propertySchema.get("editor").isJsonObject()) {
            JsonObject editor = propertySchema.getAsJsonObject("editor");
            if (editor.has("type")) {
                return editor.get("type").getAsString();
            }
        }
        return null;
    }

    private static Function<Environment, String> createGetter(String propertyName, Class<?> type) {
        return env -> {
            try {
                Object value = getJsonProperty(env.key, propertyName);
                return convertValueForDisplay(propertyName, value, type);
            } catch (Exception e) {
                log.error("Error getting property {}", propertyName, e);
                return "";
            }
        };
    }
    
    private static BiConsumer<Environment, Object> createSetter(String propertyName, Class<?> type) {
        return (env, value) -> {
            try {
                Object convertedValue = convertValueForField(propertyName, value, type);
                setJsonProperty(env.key, propertyName, convertedValue);
            } catch (Exception e) {
                log.error("Error setting property {} to value {}", propertyName, value, e);
            }
        };
    }

    private static void loadEnvironmentsData() {
        try {
            File jsonFile = new File(ENVIRONMENTS_JSON_PATH);
            if (jsonFile.exists()) {
                try (FileReader reader = new FileReader(jsonFile)) {
                    environmentsData = new JsonParser().parse(reader).getAsJsonArray();
                    log.info("Loaded environments data from JSON file");
                }
            } else {
                log.warn("Environments JSON file not found at: {}", ENVIRONMENTS_JSON_PATH);
                environmentsData = new JsonArray();
            }
        } catch (Exception e) {
            log.error("Failed to load environments data from JSON", e);
            environmentsData = new JsonArray();
        }
    }
    
    private static void saveEnvironmentsData() {
        try {
            File jsonFile = new File(ENVIRONMENTS_JSON_PATH);
            try (FileWriter writer = new FileWriter(jsonFile)) {
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                writer.write(gson.toJson(environmentsData));
                log.info("Saved environments data to JSON file");
            }
        } catch (Exception e) {
            log.error("Failed to save environments data to JSON", e);
        }
    }
    
    private static Object getJsonProperty(String environmentKey, String propertyName) {
        try {
            if (environmentsData == null) {
                loadEnvironmentsData();
            }

            for (int i = 0; i < environmentsData.size(); i++) {
                JsonObject env = environmentsData.get(i).getAsJsonObject();
                if (env.has(propertyName)) {
                    return convertJsonToValue(env.get(propertyName));
                }
            }
            log.warn("Property '{}' not found in environment '{}'", propertyName, environmentKey);
        } catch (Exception e) {
            log.error("Error getting JSON property {} for environment {}", propertyName, environmentKey, e);
        }
        return null;
    }
    
    private static void setJsonProperty(String environmentKey, String propertyName, Object value) {
        try {
            if (environmentsData == null) {
                loadEnvironmentsData();
            }

            for (int i = 0; i < environmentsData.size(); i++) {
                JsonObject env = environmentsData.get(i).getAsJsonObject();
                if (env.has("area") && env.get("area").getAsString().equals(environmentKey)) {
                    JsonElement jsonValue = convertValueToJson(value);
                    env.add(propertyName, jsonValue);
                    saveEnvironmentsData();
                    log.info("Updated property {} to {} for environment {}", propertyName, value, environmentKey);
                    return;
                }
            }
            log.warn("Environment with area '{}' not found in JSON data", environmentKey);
        } catch (Exception e) {
            log.error("Error setting JSON property {} to {} for environment {}", propertyName, value, environmentKey, e);
        }
    }

    private static JsonElement convertValueToJson(Object value) {
        if (value == null) {
            return null;
        } else if (value instanceof Boolean) {
            return new JsonPrimitive((Boolean) value);
        } else if (value instanceof Number) {
            return new JsonPrimitive((Number) value);
        } else if (value instanceof String) {
            return new JsonPrimitive((String) value);
        } else if (value instanceof float[]) {
            JsonArray jsonArray = new JsonArray();
            for (float f : (float[]) value) {
                jsonArray.add(new JsonPrimitive(f));
            }
            return jsonArray;
        } else if (value instanceof java.awt.Color) {
            java.awt.Color color = (java.awt.Color) value;
            String hex = String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
            return new JsonPrimitive(hex);
        }
        return new JsonPrimitive(value.toString());
    }
    
    private static Object convertJsonToValue(JsonElement element) {
        if (element.isJsonNull()) {
            return null;
        } else if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                return primitive.getAsBoolean();
            } else if (primitive.isNumber()) {
                return primitive.getAsString().contains(".") ? 
                    primitive.getAsFloat() : primitive.getAsInt();
            } else {
                return primitive.getAsString();
            }
        } else if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            float[] floatArray = new float[array.size()];
            for (int i = 0; i < array.size(); i++) {
                floatArray[i] = array.get(i).getAsFloat();
            }
            return floatArray;
        }
        return null;
    }
    
    private static Object convertValueForField(String propertyName, Object value, Class<?> type) {
        if (propertyName.equals("sunAngles")) {
            return convertSunAnglesValue(value);
        }
        
        if (type == java.awt.Color.class || propertyName.toLowerCase().contains("color")) {
            return convertToColor(value, type);
        }
        
        return convertPrimitiveValue(value, type);
    }
    
    private static Object convertSunAnglesValue(Object value) {
        if (value instanceof String) {
            String[] parts = ((String) value).split(",");
            if (parts.length == 2) {
                try {
                    float angle1 = Float.parseFloat(parts[0].trim());
                    float angle2 = Float.parseFloat(parts[1].trim());
                    return HDUtils.sunAngles(angle1, angle2);
                } catch (NumberFormatException e) {
                    log.warn("Invalid sun angles format: {}", value);
                }
            }
        } else if (value instanceof float[]) {
            float[] angles = (float[]) value;
            if (angles.length == 2) {
                return HDUtils.sunAngles(angles[0], angles[1]);
            }
        }
        return HDUtils.sunAngles(52, 235);
    }
    
    private static Object convertToColor(Object value, Class<?> type) {
        Object colorValue = ToolUtils.castToColor(value);
        if (type == java.awt.Color.class && colorValue instanceof float[]) {
            float[] colorArray = (float[]) colorValue;
            return new java.awt.Color(colorArray[0], colorArray[1], colorArray[2]);
        }
        return colorValue;
    }
    
    private static Object convertPrimitiveValue(Object value, Class<?> type) {
        if (type == boolean.class) {
            return ToolUtils.castToBoolean(value);
        } else if (type == float.class) {
            return ToolUtils.castToFloat(value);
        } else if (type == int.class) {
            return ToolUtils.castToInt(value);
        }
        return value;
    }
    
    private static String convertValueForDisplay(String propertyName, Object value, Class<?> type) {
        if (value == null) {
            return "";
        }
        
        if (propertyName.equals("sunAngles")) {
            return convertSunAnglesForDisplay(value);
        }
        
        if (type == java.awt.Color.class || propertyName.toLowerCase().contains("color")) {
            return convertColorForDisplay(value);
        }
        
        return String.valueOf(value);
    }
    
    private static String convertSunAnglesForDisplay(Object value) {
        if (value instanceof float[]) {
            float[] angles = (float[]) value;
            if (angles.length == 2) {
                int[] degrees = ToolUtils.reverseSunAngles(angles);
                return degrees[0] + "," + degrees[1];
            }
        }
        return "52,235";
    }
    
    private static String convertColorForDisplay(Object value) {
        if (value instanceof float[]) {
            return ToolUtils.getColorValue((float[]) value);
        } else if (value instanceof java.awt.Color) {
            return ToolUtils.getColorValue((java.awt.Color) value);
        } else if (value instanceof String) {
            try {
                java.awt.Color color = java.awt.Color.decode((String) value);
                return ToolUtils.getColorValue(color);
            } catch (Exception e) {
                return (String) value;
            }
        }
        return String.valueOf(value);
    }
}
