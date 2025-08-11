package rs117.hd.utils.tooling.props.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.scene.environments.Environment;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.tooling.environment.ToolUtils;
import rs117.hd.utils.tooling.props.PropertyData;
import rs117.hd.utils.tooling.props.SchemaBasedPropertyRegistry;

@Slf4j
public class SchemaBasedEnvironmentPropertyRegistry extends SchemaBasedPropertyRegistry<Environment> {
    
    private static SchemaBasedEnvironmentPropertyRegistry instance;
    
    public SchemaBasedEnvironmentPropertyRegistry() {
        super("schemas/environments.schema.json", 
              "src/main/resources/rs117/hd/scene/environments.json", 
              "area");
    }
    
    public static SchemaBasedEnvironmentPropertyRegistry getInstance() {
        if (instance == null) {
            instance = new SchemaBasedEnvironmentPropertyRegistry();
        }
        return instance;
    }

    @Override
    protected boolean isInternalProperty(String propertyName) {
        return propertyName.equals("key") || 
               propertyName.equals("area") || 
               propertyName.equals("description");
    }
    
    @Override
    protected boolean isSpecialProperty(JsonObject propertySchema) {
        return isSunAnglesProperty(propertySchema);
    }
    
    @Override
    protected Class<?> getSpecialPropertyType(JsonObject propertySchema) {
        if (isSunAnglesProperty(propertySchema)) {
            return int[].class;
        }
        return String.class;
    }
    
    private boolean isSunAnglesProperty(JsonObject propertySchema) {
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

    @Override
    protected String getIdentifier(Environment env) {
        return env.key;
    }
    
    @Override
    protected Object convertSpecialPropertyValue(String propertyName, Object value, Class<?> type) {
        if (propertyName.equals("sunAngles")) {
            return convertSunAnglesValue(value);
        }
        return null;
    }
    
    @Override
    protected String convertSpecialPropertyForDisplay(String propertyName, Object value, Class<?> type) {
        if (propertyName.equals("sunAngles")) {
            return convertSunAnglesForDisplay(value);
        }
        return null;
    }
    
    private Object convertSunAnglesValue(Object value) {
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
    
    private String convertSunAnglesForDisplay(Object value) {
        if (value instanceof float[]) {
            float[] angles = (float[]) value;
            if (angles.length == 2) {
                int[] degrees = ToolUtils.reverseSunAngles(angles);
                return degrees[0] + "," + degrees[1];
            }
        }
        return "52,235";
    }
    
    @Override
    public Map<String, PropertyData<Environment>> getProperties() {
        return super.getProperties();
    }

}
