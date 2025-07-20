package rs117.hd.utils.tooling.props;

import java.awt.Color;
import rs117.hd.utils.tooling.props.impl.render.NumberSpinner;
import rs117.hd.utils.tooling.props.impl.render.SunAngles;
import rs117.hd.utils.tooling.props.impl.render.CheckBox;
import rs117.hd.utils.tooling.props.impl.render.ColorPicker;

public class PropertyComponentFactory {
    public static ComponentData createComponent(PropertyData propertyData) {
        Object type = propertyData.getObject();
        if (type == float.class) {
            return new NumberSpinner(NumberSpinner.NumberType.FLOAT, -900f, Float.MAX_VALUE, 0.1f);
        } else if (type == int.class) {
            return new NumberSpinner(NumberSpinner.NumberType.INT, 0, Integer.MAX_VALUE, 1);
        } else if (type == Color.class) {
            return new ColorPicker();
        } else if (type == int[].class) {
            return new SunAngles();
        } else {
            return new CheckBox();
        }
    }
} 