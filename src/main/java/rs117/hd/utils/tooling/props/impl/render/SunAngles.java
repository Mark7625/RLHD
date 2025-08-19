package rs117.hd.utils.tooling.props.impl.render;

import javax.swing.*;
import java.awt.*;
import rs117.hd.utils.tooling.props.ComponentData;
import rs117.hd.utils.tooling.props.PropertyContext;

public class SunAngles<T> extends ComponentData<T> {
    private NumberSpinner<T> spinner1;
    private NumberSpinner<T> spinner2;

    @Override
    public void create() {
        // Fix: double escape brackets in regex
        String[] values = value.replaceAll("[\\[\\](){}]", "").split(", ?");
        String v1 = values.length > 0 ? values[0] : "0";
        String v2 = values.length > 1 ? values[1] : "0";
        spinner1 = new NumberSpinner<>(NumberSpinner.NumberType.INT, 0, Integer.MAX_VALUE, 1);
        spinner2 = new NumberSpinner<>(NumberSpinner.NumberType.INT, 0, Integer.MAX_VALUE, 1);
        spinner1.value = v1;
        spinner2.value = v2;
        spinner1.context = spinner2.context = context;
        spinner1.data = spinner2.data = data;
        spinner1.key = spinner2.key = key;
        spinner1.create();
        spinner2.create();
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        panel.add(new JLabel("Altitude: "));
        panel.add(spinner1.component);
        panel.add(new JLabel("Azimuth: "));
        panel.add(spinner2.component);
        component = panel;
    }
}