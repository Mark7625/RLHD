package rs117.hd.scene.lights.debug;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;
import rs117.hd.scene.model.ModelSnapshot;
import rs117.hd.scene.model.debug.ParticleEmitter;
import rs117.hd.scene.model.debug.ViewportPanel;

final class ParticlePropertiesPanel extends JPanel {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private final Map<String, ParticleEmitter> emitters = new LinkedHashMap<>();
	private final ViewportPanel viewport;
	private final Timer previewTimer;
	private final JLabel anchorLabel = DevUi.mutedLabel("Click a model face to place an emitter.");
	private final JSpinner rate = spinner(18, 1, 60, 1);
	private final JSpinner lifetime = spinner(850, 100, 3000, 50);
	private final JSpinner rise = spinner(65, -100, 300, 5);
	private final JSpinner spread = spinner(24, 0, 150, 2);
	private final JSpinner size = spinner(2, 1, 20, 1);
	private final JButton colorButton = new JButton("Color");
	private final JButton removeButton = new JButton("Remove emitter");
	private boolean populating;
	private boolean active;
	private int rgb = 0xffa655;
	@Nullable
	private ModelSnapshot.Piece piece;

	ParticlePropertiesPanel(ViewportPanel viewport) {
		this.viewport = viewport;
		previewTimer = new Timer(33, e -> viewport.repaint());
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		DevUi.themePanel(this);
		setAlignmentX(Component.LEFT_ALIGNMENT);
		anchorLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		anchorLabel.setForeground(new Color(175, 181, 192));
		var importButton = new JButton("Import");
		var exportButton = new JButton("Export");
		importButton.addActionListener(e -> chooseFile(false));
		exportButton.addActionListener(e -> chooseFile(true));
		colorButton.addActionListener(e -> {
			Color color = JColorChooser.showDialog(this, "Particle color", new Color(rgb));
			if (color != null) {
				rgb = color.getRGB() & 0xffffff;
				colorButton.setForeground(color);
				updateEmitter();
			}
		});
		removeButton.addActionListener(e -> removeEmitter());
		for (JSpinner spinner : new JSpinner[] { rate, lifetime, rise, spread, size })
			spinner.addChangeListener(e -> updateEmitter());
		add(DevUi.section("Emitter", true, null, anchorLabel,
			DevUi.formRow("Rate / second", rate),
			DevUi.formRow("Lifetime (ms)", lifetime),
			DevUi.formRow("Rise speed", rise),
			DevUi.formRow("Spread", spread),
			DevUi.formRow("Size", size),
			buttonRow(colorButton, removeButton)).root);
		add(DevUi.section("Preview files", true, null,
			buttonRow(importButton, exportButton)).root);
		showEmitter();
	}

	void setActive(boolean active) {
		this.active = active;
		refreshPreview();
	}

	void select(@Nullable ModelSnapshot.Piece piece) {
		this.piece = piece;
		showEmitter();
	}

	void pick(@Nullable ModelSnapshot snapshot, int preferredPiece, ViewportPanel.Pick pick, ViewportPanel.PickAction action) {
		if (snapshot == null || pick.target != ViewportPanel.PickTarget.FACE)
			return;
		var target = snapshot.pieceForFace(pick.globalIndex, preferredPiece);
		if (target == null)
			return;

		piece = target;
		if (action == ViewportPanel.PickAction.REMOVE) {
			removeEmitter();
		} else if (action == ViewportPanel.PickAction.ADD) {
			emitters.put(target.getMeshKey(), currentSettings(target.localFaceIndex(pick.globalIndex), pick.bary0, pick.bary1, pick.bary2));
			showEmitter();
		}
	}

	private static JPanel buttonRow(JButton first, JButton second) {
		JPanel row = DevUi.segmentBar(first, second);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	private static JSpinner spinner(int value, int min, int max, int step) {
		return new JSpinner(new SpinnerNumberModel(value, min, max, step));
	}

	private ParticleEmitter currentSettings(int face, float b0, float b1, float b2) {
		return new ParticleEmitter(face, b0, b1, b2, (int) rate.getValue(), (int) lifetime.getValue(),
			(int) rise.getValue(), (int) spread.getValue(), (int) size.getValue(), rgb);
	}

	private void updateEmitter() {
		if (populating || piece == null)
			return;
		var emitter = emitters.get(piece.getMeshKey());
		if (emitter != null) {
			emitters.put(piece.getMeshKey(), currentSettings(emitter.getFace(), emitter.getBary0(), emitter.getBary1(), emitter.getBary2()));
			refreshPreview();
		}
	}

	private void removeEmitter() {
		if (piece != null)
			emitters.remove(piece.getMeshKey());
		showEmitter();
	}

	private void showEmitter() {
		var emitter = piece == null ? null : emitters.get(piece.getMeshKey());
		removeButton.setEnabled(emitter != null);
		populating = true;
		if (emitter != null) {
			rate.setValue(emitter.getRate());
			lifetime.setValue(emitter.getLifetimeMs());
			rise.setValue(emitter.getRise());
			spread.setValue(emitter.getSpread());
			size.setValue(emitter.getSize());
			rgb = emitter.getRgb();
		}
		colorButton.setForeground(new Color(rgb));
		populating = false;
		anchorLabel.setText(emitter == null ? "Click a model face to place an emitter." : "Anchor: local face " + emitter.getFace());
		refreshPreview();
	}

	private void refreshPreview() {
		var emitter = active && piece != null ? emitters.get(piece.getMeshKey()) : null;
		boolean visible = emitter != null && emitter.getFace() < piece.getFaces().length;
		viewport.setParticlePreview(visible ? emitter : null, visible ? piece.getFaces()[emitter.getFace()] : -1);
		if (visible)
			previewTimer.start();
		else
			previewTimer.stop();
	}

	private void chooseFile(boolean export) {
		var chooser = new JFileChooser();
		chooser.setFileFilter(new FileNameExtensionFilter("Particle preview (*.json)", "json"));
		if (export)
			chooser.setSelectedFile(new java.io.File("particle-preview.json"));
		int result = export ? chooser.showSaveDialog(this) : chooser.showOpenDialog(this);
		if (result != JFileChooser.APPROVE_OPTION)
			return;
		var path = chooser.getSelectedFile().toPath();
		try {
			if (export) {
				if (Files.exists(path) && javax.swing.JOptionPane.showConfirmDialog(
					this, "Replace the existing preview file?", "Export", javax.swing.JOptionPane.YES_NO_OPTION
				) != javax.swing.JOptionPane.YES_OPTION)
					return;
				Files.writeString(path, GSON.toJson(emitters));
			} else {
				Map<String, ParticleEmitter> imported = GSON.fromJson(Files.readString(path),
					new TypeToken<Map<String, ParticleEmitter>>() {}.getType());
				if (imported == null || imported.values().stream().anyMatch(emitter -> emitter == null || !emitter.isValid()))
					throw new IOException("Invalid particle preview file");
				emitters.putAll(imported);
				showEmitter();
			}
		} catch (IOException | RuntimeException ex) {
			javax.swing.JOptionPane.showMessageDialog(this, ex.getMessage(), "Particle preview", javax.swing.JOptionPane.ERROR_MESSAGE);
		}
	}
}
