package rs117.hd.scene.model.debug;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSlider;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.WindowConstants;
import rs117.hd.scene.model.ModelSnapshot;

public class ModelLightViewerFrame extends JFrame {
	private static final Color UI_BG = new Color(38, 38, 42);
	private static final Color UI_FG = new Color(210, 210, 215);
	private static final Color UI_LIST_BG = new Color(32, 32, 36);
	private static final Color UI_SELECT = new Color(58, 110, 155);
	private static final Color UI_ACCENT_PLACE = new Color(58, 130, 190);
	private static final Color UI_ACCENT_DELETE = new Color(170, 58, 58);

	public interface Callbacks {
		void refreshSnapshot();

		void pickClicked(ViewportPanel.Pick pick, ViewportPanel.PickAction action);

		void boxSelected(Set<Integer> globalIndices, boolean add, ViewportPanel.PickTarget target);

		Set<Integer> litFaces(int pieceIndex);

		Set<Integer> litVertices(int pieceIndex);

		List<String> getLightDescriptions();

		void onLightBrushChanged(String description);

		void saveChanges();

		String getPieceName(int pieceIndex);

		String getPieceItemIdsText(int pieceIndex);

		void applyPieceSettings(int pieceIndex, String name, String itemIdsText);

		String getPieceListLabel(int pieceIndex);
	}

	private static class Row {
		final int pieceIndex;

		Row(int pieceIndex) {
			this.pieceIndex = pieceIndex;
		}
	}

	private final Callbacks callbacks;
	private final ViewportPanel viewport;
	private final DefaultListModel<String> rowListModel = new DefaultListModel<>();
	private final JList<String> rowList;
	private final List<Row> rows = new ArrayList<>();

	private final JComboBox<String> lightCombo = new JComboBox<>();
	private final JLabel editorHint = new JLabel("Pick a light, then click triangles or vertices");
	private final JButton saveButton = new JButton("Save");

	private final JTextField pieceNameField = new JTextField();
	private final JTextField itemIdsField = new JTextField();
	private final JLabel pieceNameLabel = new JLabel("Name");
	private final JLabel itemIdsLabel = new JLabel("Item IDs");

	private final JSlider scrubSlider = new JSlider(0, 0, 0);
	private final JLabel scrubLabel = new JLabel();
	private final JPanel scrubPanel = new JPanel(new BorderLayout(8, 0));

	@Nullable
	private float[][] recordingXs;
	@Nullable
	private float[][] recordingYs;
	@Nullable
	private float[][] recordingZs;
	@Nullable
	private int[] recordingFrames;

	private ModelSnapshot snapshot;
	@Nullable
	private String targetRootLabel;
	private int appliedPieceIndex = -1;
	private boolean populating;
	private boolean rebuildingRows;
	private boolean syncingEditMode;

	private boolean syncingPickTarget;

	public ModelLightViewerFrame(Callbacks callbacks) {
		super("Model lights - mesh picker");
		setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		this.callbacks = callbacks;

		viewport = new ViewportPanel(
			(pick, action) -> callbacks.pickClicked(pick, action),
			(indices, add, target) -> callbacks.boxSelected(indices, add, target)
		);
		viewport.setOnHoverChanged(pick -> {
			if (pick != null) {
				if (viewport.getEditMode() == ViewportPanel.EditMode.DELETE) {
					if (pick.target == ViewportPanel.PickTarget.FACE)
						editorHint.setText("Triangle f" + pick.globalIndex + " — click or drag an area to remove lights");
					else
						editorHint.setText("Vertex v" + pick.globalIndex + " — click or drag an area to remove lights");
				} else if (pick.target == ViewportPanel.PickTarget.FACE) {
					editorHint.setText("Triangle f" + pick.globalIndex + " — click to place · drag to orbit · Shift+drag area · double-click change brush");
				} else {
					editorHint.setText("Vertex v" + pick.globalIndex + " — click to place · drag to orbit · Shift+drag area · double-click change brush");
				}
			} else {
				updateHint();
			}
		});
		rowList = new JList<>(rowListModel);
		styleDarkList(rowList);
		rowList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		rowList.addListSelectionListener(e -> {
			if (e.getValueIsAdjusting() || rebuildingRows)
				return;
			int index = rowList.getSelectedIndex();
			Row row = index >= 0 && index < rows.size() ? rows.get(index) : new Row(-1);

			if (row.pieceIndex == appliedPieceIndex)
				return;
			appliedPieceIndex = row.pieceIndex;
			viewport.setPieceFilter(row.pieceIndex);
			loadPieceSettings();
			refreshMarkers();
			updateHint();
		});

		JButton refreshButton = thinButton("Refresh");
		refreshButton.addActionListener(e -> callbacks.refreshSnapshot());

		JPanel topButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		topButtons.setBackground(UI_BG);
		topButtons.add(refreshButton);

		JCheckBox labelAll = new JCheckBox("Label lights");
		styleDarkCheckBox(labelAll);
		labelAll.addActionListener(e -> viewport.setLabelAll(labelAll.isSelected()));

		JPanel leftPanel = new JPanel(new BorderLayout(0, 6));
		leftPanel.setBackground(UI_BG);
		leftPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		leftPanel.add(topButtons, BorderLayout.NORTH);
		JScrollPane listScroll = new JScrollPane(rowList);
		listScroll.setBorder(BorderFactory.createLineBorder(new Color(60, 60, 66)));
		leftPanel.add(listScroll, BorderLayout.CENTER);
		JPanel leftSouth = new JPanel(new GridLayout(0, 1, 0, 4));
		leftSouth.setBackground(UI_BG);
		leftSouth.add(labelAll);
		leftPanel.add(leftSouth, BorderLayout.SOUTH);

		JLabel pieceNameLabel = this.pieceNameLabel;
		JLabel itemIdsLabel = this.itemIdsLabel;
		JLabel templateLabel = new JLabel("Template");
		styleDarkLabel(pieceNameLabel);
		styleDarkLabel(itemIdsLabel);
		styleDarkLabel(templateLabel);
		styleDarkLabel(editorHint);
		styleDarkField(pieceNameField);
		styleDarkField(itemIdsField);
		styleDarkCombo(lightCombo);
		styleDarkButton(saveButton);
		pieceNameField.addActionListener(e -> applyPieceSettingsFromUi());
		itemIdsField.addActionListener(e -> applyPieceSettingsFromUi());
		pieceNameField.addFocusListener(new java.awt.event.FocusAdapter() {
			@Override
			public void focusLost(java.awt.event.FocusEvent e) {
				applyPieceSettingsFromUi();
			}
		});
		itemIdsField.addFocusListener(new java.awt.event.FocusAdapter() {
			@Override
			public void focusLost(java.awt.event.FocusEvent e) {
				applyPieceSettingsFromUi();
			}
		});

		JPanel editorPanel = new JPanel(new GridLayout(0, 2, 6, 4));
		editorPanel.setBackground(UI_BG);
		editorPanel.setForeground(UI_FG);
		editorPanel.setBorder(BorderFactory.createTitledBorder(
			BorderFactory.createLineBorder(new Color(60, 60, 66)), "Light brush"));
		editorPanel.add(pieceNameLabel);
		editorPanel.add(pieceNameField);
		editorPanel.add(templateLabel);
		lightCombo.addActionListener(e -> {
			if (populating)
				return;
			String desc = (String) lightCombo.getSelectedItem();
			if (desc != null && !desc.isEmpty())
				callbacks.onLightBrushChanged(desc);
		});
		editorPanel.add(lightCombo);
		editorPanel.add(editorHint);
		editorPanel.add(new JLabel(""));
		editorPanel.add(itemIdsLabel);
		editorPanel.add(itemIdsField);
		saveButton.addActionListener(e -> callbacks.saveChanges());
		editorPanel.add(saveButton);
		editorPanel.add(new JLabel(""));
		setProfileFieldsVisible(false);

		JToggleButton placeModeButton = createModeToggle("Place", UI_ACCENT_PLACE, true);
		JToggleButton deleteModeButton = createModeToggle("Delete", UI_ACCENT_DELETE, false);
		ButtonGroup modeGroup = new ButtonGroup();
		modeGroup.add(placeModeButton);
		modeGroup.add(deleteModeButton);
		placeModeButton.addActionListener(e -> {
			if (placeModeButton.isSelected())
				viewport.setEditMode(ViewportPanel.EditMode.PLACE);
		});
		deleteModeButton.addActionListener(e -> {
			if (deleteModeButton.isSelected())
				viewport.setEditMode(ViewportPanel.EditMode.DELETE);
		});
		viewport.setOnEditModeChanged(mode -> syncEditModeButtons(mode, placeModeButton, deleteModeButton));

		JToggleButton facePickButton = createModeToggle("Face", UI_ACCENT_PLACE, true);
		JToggleButton pointPickButton = createModeToggle("Point", new Color(120, 150, 90), false);
		ButtonGroup pickGroup = new ButtonGroup();
		pickGroup.add(facePickButton);
		pickGroup.add(pointPickButton);
		facePickButton.addActionListener(e -> {
			if (facePickButton.isSelected())
				viewport.setPickTarget(ViewportPanel.PickTarget.FACE);
		});
		pointPickButton.addActionListener(e -> {
			if (pointPickButton.isSelected())
				viewport.setPickTarget(ViewportPanel.PickTarget.POINT);
		});
		viewport.setOnPickTargetChanged(target -> syncPickTargetButtons(facePickButton, pointPickButton));

		JButton viewFront = thinButton("Front");
		viewFront.addActionListener(e -> viewport.setViewFront());
		JButton viewRight = thinButton("Right");
		viewRight.addActionListener(e -> viewport.setViewRight());
		JButton viewTop = thinButton("Top");
		viewTop.addActionListener(e -> viewport.setViewTop());
		JButton viewReset = thinButton("Reset");
		viewReset.addActionListener(e -> viewport.resetView());
		JCheckBox shadedToggle = new JCheckBox("Shade", true);
		styleDarkCheckBox(shadedToggle);
		shadedToggle.addActionListener(e -> viewport.setShaded(shadedToggle.isSelected()));
		JCheckBox gridToggle = new JCheckBox("Grid", false);
		styleDarkCheckBox(gridToggle);
		gridToggle.addActionListener(e -> viewport.setShowGrid(gridToggle.isSelected()));
		JCheckBox gizmoToggle = new JCheckBox("Gizmo", true);
		styleDarkCheckBox(gizmoToggle);
		gizmoToggle.addActionListener(e -> viewport.setShowGizmo(gizmoToggle.isSelected()));

		JPanel floatingToolbar = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 4));
		floatingToolbar.setOpaque(true);
		floatingToolbar.setBackground(new Color(32, 32, 36));
		floatingToolbar.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(new Color(100, 100, 110), 1, true),
			BorderFactory.createEmptyBorder(4, 10, 4, 10)));
		floatingToolbar.add(placeModeButton);
		floatingToolbar.add(deleteModeButton);
		floatingToolbar.add(createToolbarSeparator());
		floatingToolbar.add(facePickButton);
		floatingToolbar.add(pointPickButton);
		floatingToolbar.add(createToolbarSeparator());
		floatingToolbar.add(viewFront);
		floatingToolbar.add(viewRight);
		floatingToolbar.add(viewTop);
		floatingToolbar.add(viewReset);
		floatingToolbar.add(createToolbarSeparator());
		floatingToolbar.add(shadedToggle);
		floatingToolbar.add(gridToggle);
		floatingToolbar.add(gizmoToggle);
		floatingToolbar.validate();
		viewport.setFloatingToolbar(floatingToolbar);

		scrubSlider.addChangeListener(e -> applyScrub());
		scrubPanel.setBackground(UI_BG);
		styleDarkLabel(scrubLabel);
		scrubPanel.add(scrubSlider, BorderLayout.CENTER);
		scrubPanel.add(scrubLabel, BorderLayout.EAST);
		scrubPanel.setVisible(false);

		JPanel rightPanel = new JPanel(new BorderLayout(4, 4));
		rightPanel.setBackground(UI_BG);
		rightPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
		rightPanel.add(editorPanel, BorderLayout.NORTH);
		rightPanel.add(viewport, BorderLayout.CENTER);
		rightPanel.add(scrubPanel, BorderLayout.SOUTH);

		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel);
		split.setResizeWeight(0.25);
		split.setDividerLocation(260);
		add(split);

		setMinimumSize(new Dimension(900, 600));
		pack();
	}

	public void setLightDescriptions(List<String> descriptions) {
		populating = true;
		String selected = (String) lightCombo.getSelectedItem();
		lightCombo.setModel(new DefaultComboBoxModel<>(descriptions.toArray(new String[0])));
		if (selected != null)
			lightCombo.setSelectedItem(selected);
		populating = false;
	}

	public void setSnapshot(@Nullable ModelSnapshot snapshot, @Nullable String targetRootLabel) {
		clearRecording();
		this.snapshot = snapshot;
		this.targetRootLabel = targetRootLabel;
		if (snapshot != null)
			viewport.setSnapshot(snapshot);
		else
			viewport.clearSnapshot();
		rebuildRows();
	}

	public void setRecording(float[][] xs, float[][] ys, float[][] zs, int[] frames) {
		if (snapshot == null || xs.length == 0 || xs[0].length != snapshot.getVertexCount())
			return;
		recordingXs = xs;
		recordingYs = ys;
		recordingZs = zs;
		recordingFrames = frames;
		scrubSlider.setMaximum(frames.length - 1);
		scrubSlider.setValue(0);
		scrubPanel.setVisible(true);
		applyScrub();
	}

	public void showHint(String text) {
		editorHint.setText(text);
	}

	public int getAppliedPieceIndex() {
		return appliedPieceIndex;
	}

	public String getPickLightDescription() {
		Object brush = lightCombo.getSelectedItem();
		if (brush instanceof String && !((String) brush).isEmpty())
			return (String) brush;
		return "Torch";
	}

	public String getItemIdsFieldText() {
		return itemIdsField.getText();
	}

	public void refreshMarkers() {
		viewport.setSelectedFaces(callbacks.litFaces(appliedPieceIndex));
		viewport.setSelectedVertices(callbacks.litVertices(appliedPieceIndex));
		viewport.repaint();
	}

	private void rebuildRows() {
		rebuildingRows = true;
		int keepPiece = appliedPieceIndex;
		rowListModel.clear();
		rows.clear();
		int selectRow = 0;

		if (snapshot != null) {
			String root = targetRootLabel != null ? targetRootLabel : "Player";
			addRow(root + " (" + snapshot.getVertexCount() + "v)", new Row(-1));
			for (int i = 0; i < snapshot.getPieces().size(); i++)
				addRow(callbacks.getPieceListLabel(i), new Row(i));
		}

		for (int i = 0; i < rows.size(); i++) {
			if (rows.get(i).pieceIndex == keepPiece) {
				selectRow = i;
				break;
			}
		}
		finishListRebuild(selectRow);
	}

	private void addRow(String label, Row row) {
		rowListModel.addElement(label);
		rows.add(row);
	}

	private void finishListRebuild(int selectRow) {
		rebuildingRows = false;
		if (rows.isEmpty())
			return;
		if (selectRow < 0) {
			rowList.clearSelection();
			return;
		}
		Row target = rows.get(selectRow);
		appliedPieceIndex = target.pieceIndex;
		viewport.setPieceFilter(target.pieceIndex);
		rebuildingRows = true;
		rowList.setSelectedIndex(selectRow);
		rebuildingRows = false;
		refreshMarkers();
		updateHint();
		loadPieceSettings();
	}

	private void loadPieceSettings() {
		boolean show = appliedPieceIndex >= 0 && snapshot != null;
		setProfileFieldsVisible(show);
		if (!show)
			return;

		populating = true;
		pieceNameField.setText(callbacks.getPieceName(appliedPieceIndex));
		itemIdsField.setText(callbacks.getPieceItemIdsText(appliedPieceIndex));
		populating = false;
	}

	private void setProfileFieldsVisible(boolean visible) {
		pieceNameLabel.setVisible(visible);
		pieceNameField.setVisible(visible);
		itemIdsLabel.setVisible(visible);
		itemIdsField.setVisible(visible);
	}

	public void rebuildPieceListRow(int pieceIndex) {
		updatePieceListRow(pieceIndex);
	}

	private void applyPieceSettingsFromUi() {
		if (populating || appliedPieceIndex < 0 || snapshot == null)
			return;
		callbacks.applyPieceSettings(appliedPieceIndex, pieceNameField.getText(), itemIdsField.getText());
		updatePieceListRow(appliedPieceIndex);
	}

	private void updatePieceListRow(int pieceIndex) {
		int rowIndex = -1;
		for (int i = 0; i < rows.size(); i++) {
			if (rows.get(i).pieceIndex == pieceIndex) {
				rowIndex = i;
				break;
			}
		}
		if (rowIndex < 0)
			return;
		rowListModel.set(rowIndex, callbacks.getPieceListLabel(pieceIndex));
	}

	private void updateHint() {
		String pickHint = viewport.getPickTarget() == ViewportPanel.PickTarget.FACE
			? "click triangles"
			: "click vertices";
		String modeHint = viewport.getEditMode() == ViewportPanel.EditMode.DELETE
			? "Delete mode — click or drag an area on lit lights"
			: "Place mode — pick a light template, then " + pickHint;
		if (appliedPieceIndex >= 0) {
			editorHint.setText(modeHint + " on the selected piece — name and item IDs above Save");
		} else if (snapshot != null) {
			editorHint.setText("Select a mesh piece below, then use " + (viewport.getEditMode() == ViewportPanel.EditMode.DELETE ? "delete" : "place") + " mode");
		} else {
			editorHint.setText("Refresh to capture the local player");
		}
	}

	private void applyScrub() {
		if (recordingXs == null)
			return;
		int i = Math.min(scrubSlider.getValue(), recordingXs.length - 1);
		viewport.setPositionOverride(recordingXs[i], recordingYs[i], recordingZs[i]);
		scrubLabel.setText(recordingFrames != null && recordingFrames[i] >= 0
			? "frame " + recordingFrames[i] + "  (" + (i + 1) + "/" + recordingFrames.length + ")"
			: "sample " + (i + 1) + "/" + recordingXs.length);
	}

	private void clearRecording() {
		recordingXs = null;
		recordingYs = null;
		recordingZs = null;
		recordingFrames = null;
		scrubPanel.setVisible(false);
		viewport.setPositionOverride(null, null, null);
	}

	private static JButton thinButton(String label) {
		JButton button = new JButton(label);
		button.setMargin(new Insets(1, 8, 1, 8));
		button.setFocusPainted(false);
		styleDarkButton(button);
		return button;
	}

	private static void styleDarkList(JList<?> list) {
		list.setBackground(UI_LIST_BG);
		list.setForeground(UI_FG);
		list.setSelectionBackground(UI_SELECT);
		list.setSelectionForeground(Color.WHITE);
	}

	private static void styleDarkLabel(JLabel label) {
		label.setForeground(UI_FG);
	}

	private static void styleDarkCheckBox(JCheckBox box) {
		box.setBackground(UI_BG);
		box.setForeground(UI_FG);
	}

	private static void styleDarkButton(JButton button) {
		button.setOpaque(true);
		button.setContentAreaFilled(true);
		button.setBorderPainted(true);
		button.setBackground(new Color(52, 52, 58));
		button.setForeground(UI_FG);
	}

	private static void styleDarkCombo(JComboBox<?> combo) {
		combo.setBackground(UI_LIST_BG);
		combo.setForeground(UI_FG);
	}

	private static void styleDarkField(JTextField field) {
		field.setBackground(UI_LIST_BG);
		field.setForeground(UI_FG);
		field.setCaretColor(UI_FG);
	}

	private static JToggleButton createModeToggle(String label, Color accent, boolean selected) {
		JToggleButton button = new JToggleButton(label, selected);
		button.setFocusPainted(false);
		button.setOpaque(true);
		button.setContentAreaFilled(true);
		button.setBorderPainted(true);
		button.setMargin(new Insets(3, 12, 3, 12));
		styleModeToggle(button, accent);
		return button;
	}

	private static void styleModeToggle(JToggleButton button, Color accent) {
		Runnable apply = () -> {
			if (button.isSelected()) {
				button.setBackground(accent);
				button.setForeground(Color.WHITE);
			} else {
				button.setBackground(new Color(52, 52, 58));
				button.setForeground(UI_FG);
			}
		};
		button.addChangeListener(e -> apply.run());
		apply.run();
	}

	private void syncPickTargetButtons(JToggleButton faceButton, JToggleButton pointButton) {
		if (syncingPickTarget)
			return;
		syncingPickTarget = true;
		faceButton.setSelected(viewport.getPickTarget() == ViewportPanel.PickTarget.FACE);
		pointButton.setSelected(viewport.getPickTarget() == ViewportPanel.PickTarget.POINT);
		syncingPickTarget = false;
		updateHint();
	}

	private void syncEditModeButtons(ViewportPanel.EditMode mode, JToggleButton placeButton, JToggleButton deleteButton) {
		if (syncingEditMode)
			return;
		syncingEditMode = true;
		placeButton.setSelected(mode == ViewportPanel.EditMode.PLACE);
		deleteButton.setSelected(mode == ViewportPanel.EditMode.DELETE);
		syncingEditMode = false;
		updateHint();
	}

	private static JSeparator createToolbarSeparator() {
		JSeparator separator = new JSeparator(SwingConstants.VERTICAL);
		separator.setPreferredSize(new Dimension(1, 22));
		separator.setForeground(new Color(72, 72, 80));
		return separator;
	}
}
