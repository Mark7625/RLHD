package rs117.hd.utils.tooling.props.impl.render;

import java.awt.Color;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.components.ColorJButton;
import net.runelite.client.util.ColorUtil;
import rs117.hd.utils.tooling.props.ComponentData;
import rs117.hd.utils.tooling.props.PropertyContext;

public class ColorPicker<T> extends ComponentData<T> {

	private ColorJButton colorPickerBtn;

	public void create() {
		Color existing = null;
		if (value != null && !value.trim().isEmpty()) {
			try {
				existing = Color.decode(value);
			} catch (NumberFormatException e) {
				// Invalid color format, use default
				existing = null;
			}
		}

		boolean alphaHidden = true;

		if (existing == null) {
			colorPickerBtn = new ColorJButton("Pick a color", Color.BLACK);
		} else {
			String colorHex =
				"#" + (alphaHidden ? ColorUtil.colorToHexCode(existing) : ColorUtil.colorToAlphaHexCode(existing)).toUpperCase();
			colorPickerBtn = new ColorJButton(colorHex, existing);
		}

		colorPickerBtn.setFocusable(false);
		colorPickerBtn.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				Color currentColor = Color.BLACK;
				if (value != null && !value.trim().isEmpty()) {
					try {
						currentColor = Color.decode(value);
					} catch (NumberFormatException e1) {
						// Invalid color format, use default
						currentColor = Color.BLACK;
					}
				}
				
				net.runelite.client.ui.components.colorpicker.RuneliteColorPicker colorPicker = context.getColorPickerManager().create(
					context.getWindowComponent(),
					currentColor, context.getDisplayName(data) + " : " + key, false
				);
				colorPicker.setOnColorChange(c -> {
					// Convert Color to hex format instead of toString()
					value = String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
					colorPickerBtn.setColor(c);
					// Update button text to show the selected color
					colorPickerBtn.setText(value.toUpperCase());
					context.setValue(data, key, value);
				});
				colorPicker.setVisible(true);
			}
		});
		component = colorPickerBtn;
	}

}