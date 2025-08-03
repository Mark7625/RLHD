package rs117.hd.utils.tooling.props.impl.render;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Component;
import java.text.NumberFormat;
import rs117.hd.utils.tooling.props.ComponentData;

public class NumberSpinner extends ComponentData {
    public enum NumberType { INT, FLOAT }

    private JSpinner spinner;
    private NumberType type;
    private Number min;
    private Number max;
    private Number step;

    public NumberSpinner(NumberType type, Number min, Number max, Number step) {
        this.type = type;
        this.min = min;
        this.max = max;
        this.step = step;
    }

    @Override
    public void create() {
        SpinnerModel model;
        if (type == NumberType.INT) {
            // Accept both '0' and '0.0' as valid input
            int initialValue = (int) Math.round(Double.parseDouble(value));
            int minVal = min.intValue();
            int maxVal = max.intValue();
            // Clamp value
            if (initialValue < minVal) initialValue = minVal;
            if (initialValue > maxVal) initialValue = maxVal;
            model = new SpinnerNumberModel(initialValue, minVal, maxVal, step.intValue());
        } else {
            double initialValue = Double.parseDouble(value);
            double minVal = min.doubleValue();
            double maxVal = max.doubleValue();
            // Clamp value
            if (initialValue < minVal) initialValue = minVal;
            if (initialValue > maxVal) initialValue = maxVal;
            model = new SpinnerNumberModel(initialValue, minVal, maxVal, step.doubleValue());
        }
        spinner = new JSpinner(model);
        Component editor = spinner.getEditor();
        JFormattedTextField spinnerTextField = ((JSpinner.DefaultEditor) editor).getTextField();
        spinnerTextField.setColumns(6);
        if (type == NumberType.FLOAT) {
            NumberFormat format = NumberFormat.getNumberInstance();
            format.setMaximumFractionDigits(1);
            format.setMinimumFractionDigits(1);
        }
        spinnerTextField.getDocument().addDocumentListener(new DocumentListener() {
            public void changedUpdate(DocumentEvent e) { update(); }
            public void removeUpdate(DocumentEvent e) { update(); }
            public void insertUpdate(DocumentEvent e) { update(); }
            public void update() {
                Object val = spinnerTextField.getValue();
                environmentEditor.setValue(environment, key, val);
                if (onValueChanged != null) onValueChanged.run();
            }
        });
        component = spinner;
    }
} 