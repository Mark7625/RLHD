package rs117.hd.utils.tooling;

import com.google.inject.Inject;
import java.awt.BorderLayout;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.components.colorpicker.ColorPickerManager;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.scene.TextureManager;
import rs117.hd.utils.tooling.environment.EnvironmentEditorPanel;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

@Slf4j
public class ToolsFrame extends JFrame {

	@Inject
	private ClientThread clientThread;
	@Inject
	private EnvironmentManager environmentManager;
	@Inject
	private Client client;

	@Inject
	private TextureManager textureManager;

	@Inject
	private ColorPickerManager colorPicker;

	@Inject
	public ToolsFrame() {
		// Call the JFrame constructor with a title
		super("Editor");
		setSize(760, 635);

		getContentPane().setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Add window listener to reset forcedEnvironment on close
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				environmentManager.forceEnvironment(null);
			}
			@Override
			public void windowClosed(WindowEvent e) {
				environmentManager.forceEnvironment(null);
			}
		});
	}

	public void setState(boolean state) {
		if (state) {
			open();
		} else {
			close();
		}
	}

	public void open() {
		SwingUtilities.invokeLater(() -> {
			if (!isVisible()) {
				JPanel display = new JPanel(new BorderLayout());
				MaterialTabGroup tabGroup = new MaterialTabGroup(display);

				JPanel comingSoonPanel = new JPanel(new BorderLayout());
				comingSoonPanel.add(new javax.swing.JLabel("Coming Soon", javax.swing.SwingConstants.CENTER), BorderLayout.CENTER);
				MaterialTab editorTab = new MaterialTab("Environment Editor", tabGroup, new EnvironmentEditorPanel(clientThread, client, environmentManager,colorPicker));

				tabGroup.setBorder(new EmptyBorder(5, 0, 0, 0));
				tabGroup.addTab(editorTab);
				tabGroup.select(editorTab);

				add(tabGroup, BorderLayout.NORTH);
				add(display, BorderLayout.CENTER);

				setVisible(true);
			}
		});
	}

	public void close() {
		SwingUtilities.invokeLater(() -> {
			setVisible(false);
		});
	}
}