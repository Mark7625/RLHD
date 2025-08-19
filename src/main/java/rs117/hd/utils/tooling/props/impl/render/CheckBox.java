package rs117.hd.utils.tooling.props.impl.render;

import java.util.Objects;
import javax.swing.JCheckBox;
import net.runelite.client.ui.ColorScheme;
import rs117.hd.utils.tooling.props.ComponentData;
import rs117.hd.utils.tooling.props.PropertyContext;

public class CheckBox<T> extends ComponentData<T> {

	private JCheckBox checkbox = new JCheckBox();

	public void create() {
		checkbox.setBackground(ColorScheme.LIGHT_GRAY_COLOR);
		checkbox.setSelected(Objects.equals(value, "true"));
		checkbox.addActionListener(ae -> {
			context.setValue(data, key, checkbox.isSelected());
		});
		component = checkbox;
	}

}