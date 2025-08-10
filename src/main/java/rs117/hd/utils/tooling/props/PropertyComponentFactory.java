package rs117.hd.utils.tooling.props;

import java.awt.Color;
import rs117.hd.utils.tooling.props.impl.render.NumberSpinner;
import rs117.hd.utils.tooling.props.impl.render.SunAngles;
import rs117.hd.utils.tooling.props.impl.render.CheckBox;
import rs117.hd.utils.tooling.props.impl.render.ColorPicker;

public class PropertyComponentFactory {
    public static ComponentData createComponent(PropertyData propertyData) {
        Class<?> type = propertyData.getType();
        String editorType = propertyData.getEditorType();
        String description = propertyData.getDescription();

        System.out.println("Creating component for: " + description);
        System.out.println("  Java type: " + type);
        System.out.println("  Editor type: " + editorType);

        // Check if this is a color property by looking at the editor type
        if (editorType != null && "color".equals(editorType)) {
            System.out.println("  -> Creating ColorPicker (detected by editor type)");
            return new ColorPicker();
        }
        
        // Check if this is a sun angles property by looking at the editor type
        if (editorType != null && "int[]".equals(editorType)) {
            System.out.println("  -> Creating SunAngles (detected by editor type)");
            return new SunAngles();
        }
        
        if (type == float.class) {
            System.out.println("  -> Creating NumberSpinner (float)");
            return new NumberSpinner(NumberSpinner.NumberType.FLOAT, -900f, Float.MAX_VALUE, 0.1f);
        } else if (type == int.class) {
            System.out.println("  -> Creating NumberSpinner (int)");
            return new NumberSpinner(NumberSpinner.NumberType.INT, 0, Integer.MAX_VALUE, 1);
        } else if (type == Color.class) {
            System.out.println("  -> Creating ColorPicker (Color.class)");
            return new ColorPicker();
        } else if (type == int[].class) {
            System.out.println("  -> Creating SunAngles (int[].class)");
            return new SunAngles();
        } else {
            System.out.println("  -> Creating CheckBox (fallback)");
            return new CheckBox();
        }
    }
} 