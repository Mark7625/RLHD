package rs117.hd.utils.tooling.props;

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
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.utils.ResourcePath;
import rs117.hd.utils.tooling.environment.ToolUtils;

@Slf4j
public abstract class SchemaBasedPropertyRegistry<T> {
    
    protected final Map<String, PropertyData<T>> properties = new LinkedHashMap<>();
    protected final String schemaPath;
    protected final String jsonDataPath;
    protected final String identifierField;
    protected JsonArray jsonData;
    
    protected SchemaBasedPropertyRegistry(String schemaPath, String jsonDataPath, String identifierField) {
        this.schemaPath = schemaPath;
        this.jsonDataPath = jsonDataPath;
        this.identifierField = identifierField;
        loadPropertiesFromSchema();
        loadJsonData();
    }

    public Map<String, PropertyData<T>> getProperties() {
        return properties;
    }
    
    public void saveAllData() {
        saveJsonData();
    }
    
    public void reloadJsonData() {
        loadJsonData();
    }

    protected void loadPropertiesFromSchema() {
        try (
            InputStream in = ResourcePath.path(schemaPath).toInputStream();
            Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {

            JsonObject schema = new JsonParser().parse(reader).getAsJsonObject();
            JsonObject schemaProperties = schema
                .getAsJsonObject("items")
                .getAsJsonObject("properties");

            for (Map.Entry<String, JsonElement> entry : schemaProperties.entrySet()) {
                String propertyName = entry.getKey();
                JsonObject propertySchema = entry.getValue().getAsJsonObject();

                if (isInternalProperty(propertyName)) {
                    continue;
                }

                PropertyData<T> propertyData = createPropertyData(propertyName, propertySchema);
                if (propertyData != null) {
                    this.properties.put(propertyName, propertyData);
                }
            }

            log.info("Loaded {} properties from schema {}", this.properties.size(), schemaPath);

        } catch (Exception e) {
            log.error("Failed to load properties from schema {}", schemaPath, e);
        }
    }
    
    protected abstract boolean isInternalProperty(String propertyName);
    
    protected PropertyData<T> createPropertyData(String propertyName, JsonObject propertySchema) {
        try {
            if (!shouldShowProperty(propertySchema)) {
                return null;
            }
            
            String description = propertySchema.has("description") ? 
                propertySchema.get("description").getAsString() : propertyName;
            String category = determineCategory(propertyName, propertySchema);
            if (category.isEmpty()) {
                category = "?";
            }
            Class<?> type = determineType(propertySchema);
            String editorType = extractEditorType(propertySchema);
            
            BiConsumer<T, Object> setter = createSetter(propertyName, type);
            Function<T, String> getter = createGetter(propertyName, type);
            
            return new PropertyData(description, type, setter, getter, category, editorType);
            
        } catch (Exception e) {
            log.warn("Failed to create property data for {}", propertyName, e);
            return null;
        }
    }

    protected boolean shouldShowProperty(JsonObject propertySchema) {
        if (propertySchema.has("editor") && propertySchema.get("editor").isJsonObject()) {
            JsonObject editor = propertySchema.getAsJsonObject("editor");
            return !(editor.has("noteditable") && editor.get("noteditable").getAsBoolean());
        }
        return true;
    }
    
    protected String determineCategory(String propertyName, JsonObject propertySchema) {
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

    protected Class<?> determineType(JsonObject propertySchema) {
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

        if (isSpecialProperty(propertySchema)) {
            return getSpecialPropertyType(propertySchema);
        }

        String editorType = extractEditorType(propertySchema);
        if (editorType != null) {
            return getClassFromEditorType(editorType);
        }
        
        return String.class;
    }
    
    protected Class<?> determineType(String propertyName, JsonObject propertySchema) {
        // Check for name-based special properties first
        if (isSpecialPropertyByName(propertyName, propertySchema)) {
            return getSpecialPropertyType(propertySchema);
        }
        
        return determineType(propertySchema);
    }
    
    protected boolean isColorProperty(JsonObject propertySchema) {
        // Check if it's a color property by either:
        // 1. Having the hex pattern with array constraints (legacy format)
        // 2. Having the color editor type (new format)
        boolean hasHexPattern = propertySchema.has("pattern") && 
                               propertySchema.has("minItems") && 
                               propertySchema.has("maxItems") &&
                               propertySchema.get("pattern").getAsString().equals("^#[A-Fa-f0-9]{6}$") &&
                               propertySchema.get("minItems").getAsInt() == 3 &&
                               propertySchema.get("maxItems").getAsInt() == 3;
        
        boolean hasColorEditor = propertySchema.has("editor") && 
                                propertySchema.get("editor").isJsonObject() &&
                                propertySchema.getAsJsonObject("editor").has("type") &&
                                propertySchema.getAsJsonObject("editor").get("type").getAsString().equals("color");
        
        return hasHexPattern || hasColorEditor;
    }
    
    protected boolean isSpecialProperty(JsonObject propertySchema) {
        return false; // Override in subclasses for special properties
    }
    
    protected Class<?> getSpecialPropertyType(JsonObject propertySchema) {
        return String.class; // Override in subclasses for special property types
    }
    
    protected boolean isSpecialPropertyByName(String propertyName, JsonObject propertySchema) {
        // Override in subclasses for property name-based special property detection
        return false;
    }
    
    protected Class<?> getClassFromUnionType(JsonArray typeArray) {
        for (JsonElement type : typeArray) {
            String typeStr = type.getAsString();
            if (!typeStr.equals("null")) {
                return getClassFromType(typeStr);
            }
        }
        return String.class;
    }
    
    protected Class<?> getClassFromType(String type) {
        switch (type) {
            case "boolean": return boolean.class;
            case "number": return float.class;
            case "integer": return int.class;
            case "string": return String.class;
            case "array": return Object.class;
            default: return String.class;
        }
    }
    
    protected Class<?> getClassFromEditorType(String editorType) {
        switch (editorType) {
            case "boolean": return boolean.class;
            case "float": return float.class;
            case "int": return int.class;
            case "int[]": return int[].class;
            case "color": return java.awt.Color.class;
            default: return String.class;
        }
    }
    
    protected String extractEditorType(JsonObject propertySchema) {
        if (propertySchema.has("editor") && propertySchema.get("editor").isJsonObject()) {
            JsonObject editor = propertySchema.getAsJsonObject("editor");
            if (editor.has("type")) {
                return editor.get("type").getAsString();
            }
        }
        return null;
    }

    protected Function<T, String> createGetter(String propertyName, Class<?> type) {
        return obj -> {
            try {
                String identifier = getIdentifier(obj);
                Object value = getJsonProperty(identifier, propertyName);
                return convertValueForDisplay(propertyName, value, type);
            } catch (Exception e) {
                log.error("Error getting property {}", propertyName, e);
                return "";
            }
        };
    }
    
    protected BiConsumer<T, Object> createSetter(String propertyName, Class<?> type) {
        return (obj, value) -> {
            try {
                String identifier = getIdentifier(obj);
                Object convertedValue = convertValueForField(propertyName, value, type);
                setJsonProperty(identifier, propertyName, convertedValue);
            } catch (Exception e) {
                log.error("Error setting property {} to value {}", propertyName, value, e);
            }
        };
    }
    
    protected abstract String getIdentifier(T obj);

    protected void loadJsonData() {
        try {
            File jsonFile = new File(jsonDataPath);
            if (jsonFile.exists()) {
                try (FileReader reader = new FileReader(jsonFile)) {
                    jsonData = new JsonParser().parse(reader).getAsJsonArray();
                    log.info("Loaded JSON data from file: {}", jsonDataPath);
                }
            } else {
                log.warn("JSON file not found at: {}", jsonDataPath);
                jsonData = new JsonArray();
            }
        } catch (Exception e) {
            log.error("Failed to load JSON data from {}", jsonDataPath, e);
            jsonData = new JsonArray();
        }
    }
    
    protected void saveJsonData() {
        try {
            File jsonFile = new File(jsonDataPath);
            try (FileWriter writer = new FileWriter(jsonFile)) {
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                writer.write(gson.toJson(jsonData));
                log.info("Saved JSON data to file: {}", jsonDataPath);
            }
        } catch (Exception e) {
            log.error("Failed to save JSON data to {}", jsonDataPath, e);
        }
    }
    
    protected Object getJsonProperty(String identifier, String propertyName) {
        try {
            if (jsonData == null) {
                loadJsonData();
            }

            for (int i = 0; i < jsonData.size(); i++) {
                JsonObject item = jsonData.get(i).getAsJsonObject();
                if (item.has(propertyName)) {
                    // Check if this is a color property and handle it specially
                    if (properties.containsKey(propertyName) && 
                        properties.get(propertyName).getType() == java.awt.Color.class) {
                        return convertJsonToColorValue(item.get(propertyName));
                    }
                    return convertJsonToValue(item.get(propertyName));
                }
            }
            log.warn("Property '{}' not found in item '{}'", propertyName, identifier);
        } catch (Exception e) {
            log.error("Error getting JSON property {} for item {}", propertyName, identifier, e);
        }
        return null;
    }
    
    protected void setJsonProperty(String identifier, String propertyName, Object value) {
        try {
            if (jsonData == null) {
                loadJsonData();
            }

            for (int i = 0; i < jsonData.size(); i++) {
                JsonObject item = jsonData.get(i).getAsJsonObject();
                if (item.has(identifierField) && item.get(identifierField).getAsString().equals(identifier)) {
                    Object processedValue = value;
                    if (properties.containsKey(propertyName) &&
						Objects.equals(properties.get(propertyName).getEditorType(), "color")) {
                        processedValue = convertValueForField(propertyName, value, java.awt.Color.class);
                    }
                    
                    JsonElement jsonValue = convertValueToJson(processedValue);
                    item.add(propertyName, jsonValue);
                    saveJsonData();
                    log.info("Updated property {} to {} for item {}", propertyName, value, identifier);
                    return;
                }
            }
            log.warn("Item with {} '{}' not found in JSON data", identifierField, identifier);
        } catch (Exception e) {
            log.error("Error setting JSON property {} to {} for item {}", propertyName, value, identifier, e);
        }
    }

    protected JsonElement convertValueToJson(Object value) {
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
    
    protected Object convertJsonToValue(JsonElement element) {
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
    
    protected Object convertJsonToColorValue(JsonElement element) {
        if (element.isJsonNull()) {
            return null;
        } else if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isString()) {
                String hexString = primitive.getAsString();
                try {
                    return java.awt.Color.decode(hexString);
                } catch (Exception e) {
                    log.warn("Failed to decode color hex string: {}", hexString, e);
                    return null;
                }
            } else if (primitive.isNumber()) {
                // Handle legacy decimal values (0-255 range)
                float value = primitive.getAsFloat();
                if (value <= 255) {
                    return new java.awt.Color(value / 255f, value / 255f, value / 255f);
                }
                return null;
            }
        } else if (element.isJsonArray()) {
            // Handle legacy array format [r, g, b] in 0-255 range
            JsonArray array = element.getAsJsonArray();
            if (array.size() >= 3) {
                try {
                    float r = array.get(0).getAsFloat() / 255f;
                    float g = array.get(1).getAsFloat() / 255f;
                    float b = array.get(2).getAsFloat() / 255f;
                    return new java.awt.Color(r, g, b);
                } catch (Exception e) {
                    log.warn("Failed to decode color array: {}", array, e);
                    return null;
                }
            }
        }
        return null;
    }
    
    protected Object convertValueForField(String propertyName, Object value, Class<?> type) {
        // Check for special property handling first
        Object specialValue = convertSpecialPropertyValue(propertyName, value, type);
        if (specialValue != null) {
            return specialValue;
        }
        
        if (type == java.awt.Color.class || propertyName.toLowerCase().contains("color")) {
            return convertToColor(value, type);
        }
        
        return convertPrimitiveValue(value, type);
    }
    
    protected Object convertSpecialPropertyValue(String propertyName, Object value, Class<?> type) {
        // Override in subclasses for special property handling
        return null;
    }
    
    protected Object convertToColor(Object value, Class<?> type) {
        Object colorValue = ToolUtils.castToColor(value);
        if (type == java.awt.Color.class && colorValue instanceof float[]) {
            float[] colorArray = (float[]) colorValue;
            return new java.awt.Color(colorArray[0], colorArray[1], colorArray[2]);
        }
        return colorValue;
    }
    
    protected Object convertPrimitiveValue(Object value, Class<?> type) {
        if (type == boolean.class) {
            return ToolUtils.castToBoolean(value);
        } else if (type == float.class) {
            return ToolUtils.castToFloat(value);
        } else if (type == int.class) {
            return ToolUtils.castToInt(value);
        }
        return value;
    }
    
    protected String convertValueForDisplay(String propertyName, Object value, Class<?> type) {
        if (value == null) {
            return "";
        }
        
        // Check for special property display handling first
        String specialDisplay = convertSpecialPropertyForDisplay(propertyName, value, type);
        if (specialDisplay != null) {
            return specialDisplay;
        }
        
        if (type == java.awt.Color.class || propertyName.toLowerCase().contains("color")) {
            return convertColorForDisplay(value);
        }
        
        return String.valueOf(value);
    }
    
    protected String convertSpecialPropertyForDisplay(String propertyName, Object value, Class<?> type) {
        // Override in subclasses for special property display handling
        return null;
    }
    
    protected String convertColorForDisplay(Object value) {
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
