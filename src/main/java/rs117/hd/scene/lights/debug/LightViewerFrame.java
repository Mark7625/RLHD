package rs117.hd.scene.lights.debug;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;
import rs117.hd.scene.lights.Alignment;
import rs117.hd.scene.lights.LightDefinition;
import rs117.hd.scene.lights.LightType;
import rs117.hd.scene.model.ModelSnapshot;
import rs117.hd.scene.model.debug.ViewportPanel;
import rs117.hd.utils.ColorUtils;

public class LightViewerFrame extends JFrame {
	public static final class AnchorSelection {
		public final int pieceIndex;
		public final int localIndex;
		public final boolean triangle;

		public AnchorSelection(int pieceIndex, int localIndex, boolean triangle) {
			this.pieceIndex = pieceIndex;
			this.localIndex = localIndex;
			this.triangle = triangle;
		}
	}

	public interface Callbacks {
		void refreshSnapshot();

		void refreshSightings();

		void pickClicked(ViewportPanel.Pick pick, ViewportPanel.PickAction action);

		void boxSelected(Set<Integer> globalIndices, boolean add, ViewportPanel.PickTarget target);

		void anchorSelected(AnchorSelection anchor);

		Set<Integer> litFaces(int pieceIndex);

		Set<Integer> litVertices(int pieceIndex);

		List<String> getLightDescriptions();

		void onLightBrushChanged(String description);

		void saveModelLights();

		void saveLightDefinitions();

		void loadObject(int objectId);

		void loadNpc(int npcId);

		void loadGraphic(int graphicId);

		void playerViewSelected();

		void poseAnimation(int animId);

		List<LightMeshCapture.Sighting> getObjectSightings();

		List<LightMeshCapture.Sighting> getNpcSightings();

		String getPieceName(int pieceIndex);

		String getPieceItemIdsText(int pieceIndex);

		void applyPieceSettings(int pieceIndex, String name, String itemIdsText);

		void applyProfileOffset(int pieceIndex, float x, float y, float z);

		float[] getProfileOffset(int pieceIndex);

		String getPieceListLabel(int pieceIndex);

		@Nullable
		String getAnchorLightDescription(AnchorSelection anchor);

		void setAnchorLightDescription(AnchorSelection anchor, String description);

		@Nullable
		float[] getAnchorBarycentric(AnchorSelection anchor);

		void setAnchorBarycentric(AnchorSelection anchor, float b0, float b1, float b2);

		LightDefinition getDefinition(String description);

		void saveDefinition(String description, LightDefinition definition);

		void createDefinition(String description);

		void deleteDefinition(String description);
	}

	private static class Row {
		final int pieceIndex;
		final int sightingId;
		final List<Integer> equipmentParts;

		Row(List<Integer> equipmentParts) {
			this(equipmentParts.get(0), -1, equipmentParts);
		}

		Row(int pieceIndex) {
			this(pieceIndex, -1);
		}

		Row(int pieceIndex, int sightingId) {
			this(pieceIndex, sightingId, List.of());
		}

		private Row(int pieceIndex, int sightingId, List<Integer> equipmentParts) {
			this.pieceIndex = pieceIndex;
			this.sightingId = sightingId;
			this.equipmentParts = equipmentParts;
		}

		boolean containsPart(int index) {
			return pieceIndex == index || equipmentParts.contains(index);
		}
	}

	private final Callbacks callbacks;
	private final ViewportPanel viewport;


	private final DefaultListModel<String> rowListModel = new DefaultListModel<>();
	private final JList<String> rowList;
	private final List<Row> rows = new ArrayList<>();

	private final JComboBox<String> modeSelector = new JComboBox<>(
		new String[] { "Player", "World objects", "NPCs", "Graphics" });
	private final JTextField idField = new JTextField(7);
	private final JPanel loadPanel = new JPanel(new BorderLayout(4, 0));

	private final JButton placementNavBtn = new JButton("Placement");
	private final JButton definitionsNavBtn = new JButton("Definitions");
	private final JButton particlesNavBtn = new JButton("Particles");
	private final JToggleButton vertexPickButton = new JToggleButton("Vert");
	private final ParticlePropertiesPanel particlesPanel;
	private boolean editingParticles;
	private final CardLayout cardLayout = new CardLayout();
	private final JPanel cards = new JPanel(cardLayout);

	private final JComboBox<String> lightBrushCombo = new JComboBox<>();
	private final JLabel editorHint = new JLabel();
	private final JTextField pieceNameField = new JTextField();
	private final JComboBox<String> partSelector = new JComboBox<>();
	private JComponent partSelectorRow;
	private final JTextField itemIdsField = new JTextField();
	private final JSpinner offsetXSpinner = new JSpinner(new SpinnerNumberModel(0.0, -256.0, 256.0, 0.5));
	private final JSpinner offsetYSpinner = new JSpinner(new SpinnerNumberModel(0.0, -256.0, 256.0, 0.5));
	private final JSpinner offsetZSpinner = new JSpinner(new SpinnerNumberModel(0.0, -256.0, 256.0, 0.5));

	private final JPanel anchorPanel = new JPanel();
	private final JLabel anchorTitle = DevUi.mutedLabel("No anchor selected");
	private final JComboBox<String> anchorLightCombo = new JComboBox<>();
	private final JSpinner bary0Spinner = new JSpinner(new SpinnerNumberModel(0.33, 0.0, 1.0, 0.01));
	private final JSpinner bary1Spinner = new JSpinner(new SpinnerNumberModel(0.33, 0.0, 1.0, 0.01));
	private final JSpinner bary2Spinner = new JSpinner(new SpinnerNumberModel(0.34, 0.0, 1.0, 0.01));
	private JComponent baryRow;

	private final DefaultListModel<String> definitionListModel = new DefaultListModel<>();
	private final JList<String> definitionList = new JList<>(definitionListModel);
	private final JTextField defDescriptionField = new JTextField();
	private final JSpinner radiusSpinner = new JSpinner(new SpinnerNumberModel(300, 1, 5000, 10));
	private final JSpinner strengthSpinner = new JSpinner(new SpinnerNumberModel(5.0, 0.0, 100.0, 0.5));
	private final JButton colorButton = new JButton("Color");
	private final JComboBox<LightType> typeCombo = new JComboBox<>(LightType.values());
	private final JComboBox<Alignment> alignmentCombo = new JComboBox<>(Alignment.values());
	private final JSpinner defOffsetXSpinner = new JSpinner(new SpinnerNumberModel(0.0, -256.0, 256.0, 0.5));
	private final JSpinner defOffsetYSpinner = new JSpinner(new SpinnerNumberModel(0.0, -256.0, 256.0, 0.5));
	private final JSpinner defOffsetZSpinner = new JSpinner(new SpinnerNumberModel(0.0, -256.0, 256.0, 0.5));
	private final JSpinner durationSpinner = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 60000.0, 100.0));
	private final JSpinner rangeSpinner = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 100.0, 0.5));
	private final JSpinner innerConeSpinner = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 180.0, 1.0));
	private final JSpinner outerConeSpinner = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 180.0, 1.0));
	private final JSpinner conePitchSpinner = new JSpinner(new SpinnerNumberModel(0.0, -180.0, 180.0, 1.0));
	private final JTextField maskField = new JTextField();
	private final JSpinner maskScaleSpinner = new JSpinner(new SpinnerNumberModel(1.0, 0.1, 10.0, 0.1));
	private final JSpinner heightSpinner = new JSpinner(new SpinnerNumberModel(0, -1000, 5000, 5));
	private final JSpinner fadeInSpinner = new JSpinner(new SpinnerNumberModel(50, 0, 60000, 10));
	private final JSpinner fadeOutSpinner = new JSpinner(new SpinnerNumberModel(50, 0, 60000, 10));
	private final JSpinner spawnDelaySpinner = new JSpinner(new SpinnerNumberModel(0, 0, 60000, 10));
	private final JSpinner despawnDelaySpinner = new JSpinner(new SpinnerNumberModel(0, 0, 60000, 10));
	private final JSpinner renderableIndexSpinner = new JSpinner(new SpinnerNumberModel(-1, -1, 10, 1));
	private final JCheckBox fixedDespawnCheck = styleCheck("Fixed despawn time");
	private final JCheckBox despawnWithParentCheck = styleCheck("Despawn with parent");
	private final JCheckBox visibleOtherPlanesCheck = styleCheck("Visible from other planes");
	private final JCheckBox ignoreActorHidingCheck = styleCheck("Ignore actor hiding");
	private final JCheckBox waitForAnimationCheck = styleCheck("Wait for animation");
	private final JTextField animationIdsField = new JTextField();
	private final JTextField npcIdsField = new JTextField();
	private final JTextField objectIdsField = new JTextField();
	private final JTextField projectileIdsField = new JTextField();
	private final JTextField graphicsIdsField = new JTextField();

	private final JSlider scrubSlider = new JSlider(0, 0, 0);
	private final JLabel scrubLabel = new JLabel(" ");
	private final JPanel scrubPanel = new JPanel(new BorderLayout(6, 0));

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
	private int selectedMode;
	private int selectedCaptureId = -1;
	private int lastAutoLoadedCaptureId = -1;
	private boolean populating;
	private boolean rebuildingRows;
	private boolean syncingEditMode;
	private boolean syncingPickTarget;
	@Nullable
	private AnchorSelection selectedAnchor;
	@Nullable
	private String selectedDefinitionId;

	public LightViewerFrame(Callbacks callbacks) {
		super("117 HD Model Workspace");
		setIconImage(icon("box").getImage());
		setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		this.callbacks = callbacks;

		viewport = new ViewportPanel(
			this::pickModel,
			(indices, add, target) -> {
				if (!editingParticles)
					callbacks.boxSelected(indices, add, target);
			}
		);
		particlesPanel = new ParticlePropertiesPanel(viewport);
		viewport.setShowGrid(true);
		viewport.setOnHoverChanged(this::updateHoverHint);

		rowList = new JList<>(rowListModel);
		rowList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		rowList.setFixedCellHeight(28);
		DevUi.styleList(rowList);
		ImageIcon rootIcon = icon("list-tree");
		ImageIcon modelIcon = icon("box");
		rowList.setCellRenderer(new DefaultListCellRenderer() {
			@Override
			public Component getListCellRendererComponent(
				JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus
			) {
				super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				DevUi.applyListRendererFont(this);
				setBorder(BorderFactory.createEmptyBorder(2, index == 0 ? 10 : 20, 2, 8));
				setIcon(index == 0 ? rootIcon : modelIcon);
				setIconTextGap(7);
				if (isSelected) {
					setBackground(new Color(75, 62, 52));
					setForeground(new Color(255, 209, 162));
				}
				return this;
			}
		});
		rowList.addListSelectionListener(e -> {
			if (e.getValueIsAdjusting() || rebuildingRows)
				return;
			int index = rowList.getSelectedIndex();
			if (index < 0 || index >= rows.size())
				return;
			Row row = rows.get(index);
			if (selectedMode > 0 && row.sightingId >= 0) {
				applyInSceneCaptureRow(row.sightingId);
				return;
			}
			applyPieceRow(row, appliedPieceIndex);
		});

		buildUi();
		applyWorkspaceFonts(getContentPane());
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosed(WindowEvent e) {
				particlesPanel.setActive(false);
				releaseSnapshot();
			}
		});
	}

	private void pickModel(ViewportPanel.Pick pick, ViewportPanel.PickAction action) {
		if (editingParticles)
			particlesPanel.pick(snapshot, appliedPieceIndex, pick, action);
		else
			callbacks.pickClicked(pick, action);
	}

	private static ImageIcon icon(String name) {
		var url = LightViewerFrame.class.getResource("icons/" + name + ".png");
		return url == null ? new ImageIcon() : new ImageIcon(url);
	}

	private void buildUi() {
		JButton refresh = new JButton("Refresh", icon("refresh-cw"));
		refresh.setToolTipText("Recapture the target (~3s animation recording for players)");
		refresh.addActionListener(e -> callbacks.refreshSnapshot());

		JButton pose = new JButton("Pose", icon("person-standing"));
		pose.setToolTipText("NPC only: pose cache mesh at every frame of an animation ID");
		pose.addActionListener(e -> {
			String input = javax.swing.JOptionPane.showInputDialog(this, "Animation ID:");
			if (input == null)
				return;
			try {
				int id = Integer.parseInt(input.trim());
				if (id >= 0)
					callbacks.poseAnimation(id);
			} catch (NumberFormatException ignored) {}
		});

		JButton saveModels = new JButton("Save placement", icon("save"));
		saveModels.setToolTipText("Write model_lights.json");
		saveModels.addActionListener(e -> callbacks.saveModelLights());

		JButton saveDefs = new JButton("Save lights", icon("save"));
		saveDefs.setToolTipText("Write lights.json");
		saveDefs.addActionListener(e -> callbacks.saveLightDefinitions());

		DevUi.styleCombo(modeSelector);
		DevUi.styleToolbarButton(refresh);
		DevUi.styleToolbarButton(pose);
		DevUi.styleToolbarButton(saveModels);
		DevUi.styleToolbarButton(saveDefs);

		modeSelector.addActionListener(e -> {
			selectedMode = modeSelector.getSelectedIndex();
			updateLoadPanel();
			if (selectedMode == 0) {
				callbacks.playerViewSelected();
			} else if (selectedMode == 1 || selectedMode == 2) {
				callbacks.refreshSightings();
			}
			rebuildRows();
		});

		JButton loadBtn = new JButton("Load");
		loadBtn.addActionListener(e -> loadFromIdField());
		DevUi.sizeField(idField);
		loadPanel.setOpaque(false);
		loadPanel.add(new JLabel("ID "), BorderLayout.WEST);
		loadPanel.add(idField, BorderLayout.CENTER);
		loadPanel.add(loadBtn, BorderLayout.EAST);

		JPanel top = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 5));
		DevUi.themePanel(top);
		JLabel workspaceTitle = DevUi.titleLabel("117 HD  /  Model Workspace");
		workspaceTitle.setIcon(icon("box"));
		workspaceTitle.setIconTextGap(8);
		top.add(workspaceTitle);
		top.add(Box.createHorizontalStrut(10));
		top.add(modeSelector);
		top.add(loadPanel);
		updateLoadPanel();
		top.add(refresh);
		top.add(pose);
		top.add(saveModels);
		top.add(saveDefs);

		placementNavBtn.setText("Lights");
		placementNavBtn.setIcon(icon("lightbulb"));
		definitionsNavBtn.setIcon(icon("sliders-horizontal"));
		particlesNavBtn.setIcon(icon("sparkles"));
		placementNavBtn.addActionListener(e -> showCard("placement"));
		definitionsNavBtn.addActionListener(e -> showCard("definitions"));
		particlesNavBtn.addActionListener(e -> showCard("particles"));
		cards.add(buildPlacementPanel(), "placement");
		cards.add(buildDefinitionsPanel(), "definitions");
		cards.add(DevUi.scroll(particlesPanel), "particles");
		showCard("placement");

		JPanel outliner = new JPanel(new BorderLayout(0, 0));
		DevUi.themePanel(outliner);
		outliner.add(panelHeader("Outliner", "list-tree"), BorderLayout.NORTH);
		var listScroll = new JScrollPane(rowList);
		listScroll.setBorder(BorderFactory.createEmptyBorder());
		outliner.add(listScroll, BorderLayout.CENTER);
		outliner.setMinimumSize(new Dimension(190, 0));

		JPanel properties = new JPanel(new BorderLayout());
		DevUi.themePanel(properties);
		JPanel propertiesHeader = new JPanel(new BorderLayout());
		DevUi.themePanel(propertiesHeader);
		propertiesHeader.add(panelHeader("Properties", "sliders-horizontal"), BorderLayout.NORTH);
		JPanel propertyTabs = new JPanel(new BorderLayout());
		DevUi.themePanel(propertyTabs);
		propertyTabs.add(DevUi.segmentBar(placementNavBtn, definitionsNavBtn, particlesNavBtn), BorderLayout.NORTH);
		propertyTabs.add(partSelectorRow, BorderLayout.CENTER);
		propertiesHeader.add(propertyTabs, BorderLayout.CENTER);
		properties.add(propertiesHeader, BorderLayout.NORTH);
		properties.add(cards, BorderLayout.CENTER);
		properties.setMinimumSize(new Dimension(300, 0));

		scrubSlider.addChangeListener(e -> applyScrub());
		scrubPanel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
		scrubPanel.add(scrubSlider, BorderLayout.CENTER);
		scrubPanel.add(scrubLabel, BorderLayout.EAST);
		scrubPanel.setVisible(false);

		JPanel canvas = new JPanel(new BorderLayout());
		DevUi.themePanel(canvas);
		var canvasHeader = new JPanel(new BorderLayout());
		DevUi.themePanel(canvasHeader);
		canvasHeader.add(panelHeader("3D Viewport", "box"), BorderLayout.NORTH);
		var toolbarScroll = new JScrollPane(buildViewportToolbar(),
			ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		toolbarScroll.setBorder(BorderFactory.createEmptyBorder());
		canvasHeader.add(toolbarScroll, BorderLayout.CENTER);
		canvas.add(canvasHeader, BorderLayout.NORTH);
		canvas.add(viewport, BorderLayout.CENTER);
		canvas.add(scrubPanel, BorderLayout.SOUTH);
		canvas.setMinimumSize(new Dimension(360, 0));

		JSplitPane center = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, canvas, properties);
		center.setResizeWeight(1);
		center.setContinuousLayout(true);
		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, outliner, center);
		split.setResizeWeight(0);
		split.setContinuousLayout(true);

		JPanel workspace = new JPanel(new BorderLayout());
		DevUi.themePanel(workspace);
		workspace.add(top, BorderLayout.NORTH);
		workspace.add(split, BorderLayout.CENTER);
		setContentPane(workspace);
		setMinimumSize(new Dimension(940, 620));
		setSize(1470, 870);
		split.setDividerLocation(255);
		center.setDividerLocation(815);
		setLocationRelativeTo(null);

	}

	private static void applyWorkspaceFonts(Component component) {
		Font font = component.getFont();
		if (font != null)
			component.setFont(new Font(Font.SANS_SERIF, font.getStyle(), Math.max(12, font.getSize())));
		if (component instanceof Container)
			for (Component child : ((Container) component).getComponents())
				applyWorkspaceFonts(child);
	}

	private static JLabel panelHeader(String text, String iconName) {
		JLabel header = DevUi.titleLabel(text);
		header.setIcon(icon(iconName));
		header.setIconTextGap(8);
		header.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
		header.setOpaque(true);
		header.setBackground(new Color(38, 39, 43));
		return header;
	}

	private JComponent buildPlacementPanel() {
		DevUi.styleCombo(lightBrushCombo);
		lightBrushCombo.addActionListener(e -> {
			if (populating)
				return;
			String desc = (String) lightBrushCombo.getSelectedItem();
			if (desc != null && !desc.isEmpty())
				callbacks.onLightBrushChanged(desc);
		});

		DevUi.styleCombo(partSelector);
		partSelector.addActionListener(e -> {
			if (populating)
				return;
			int rowIndex = rowList.getSelectedIndex();
			int partIndex = partSelector.getSelectedIndex();
			if (rowIndex < 0 || rowIndex >= rows.size())
				return;
			var parts = rows.get(rowIndex).equipmentParts;
			if (partIndex >= 0 && partIndex < parts.size())
				applyPiece(parts.get(partIndex));
		});
		partSelectorRow = DevUi.formRow("Model part", partSelector);
		partSelectorRow.setVisible(false);

		DevUi.sizeField(pieceNameField);
		DevUi.sizeField(itemIdsField);
		pieceNameField.addActionListener(e -> applyPieceSettingsFromUi());
		itemIdsField.addActionListener(e -> applyPieceSettingsFromUi());

		for (JSpinner spinner : new JSpinner[] { offsetXSpinner, offsetYSpinner, offsetZSpinner })
			DevUi.sizeField(spinner);
		Runnable saveOffset = () -> {
			if (populating || appliedPieceIndex < 0)
				return;
			callbacks.applyProfileOffset(
				appliedPieceIndex,
				((Number) offsetXSpinner.getValue()).floatValue(),
				((Number) offsetYSpinner.getValue()).floatValue(),
				((Number) offsetZSpinner.getValue()).floatValue()
			);
		};
		offsetXSpinner.addChangeListener(e -> saveOffset.run());
		offsetYSpinner.addChangeListener(e -> saveOffset.run());
		offsetZSpinner.addChangeListener(e -> saveOffset.run());

		anchorPanel.setLayout(new BoxLayout(anchorPanel, BoxLayout.Y_AXIS));
		anchorPanel.setOpaque(false);
		anchorTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
		anchorPanel.add(anchorTitle);
		DevUi.styleCombo(anchorLightCombo);
		anchorLightCombo.addActionListener(e -> {
			if (populating || selectedAnchor == null)
				return;
			String desc = (String) anchorLightCombo.getSelectedItem();
			if (desc != null)
				callbacks.setAnchorLightDescription(selectedAnchor, desc);
		});
		for (JSpinner spinner : new JSpinner[] { bary0Spinner, bary1Spinner, bary2Spinner })
			DevUi.sizeField(spinner);
		Runnable saveBary = () -> {
			if (populating || selectedAnchor == null || !selectedAnchor.triangle)
				return;
			callbacks.setAnchorBarycentric(
				selectedAnchor,
				((Number) bary0Spinner.getValue()).floatValue(),
				((Number) bary1Spinner.getValue()).floatValue(),
				((Number) bary2Spinner.getValue()).floatValue()
			);
		};
		bary0Spinner.addChangeListener(e -> saveBary.run());
		bary1Spinner.addChangeListener(e -> saveBary.run());
		bary2Spinner.addChangeListener(e -> saveBary.run());
		baryRow = DevUi.vectorRow("Barycentric", bary0Spinner, bary1Spinner, bary2Spinner);
		baryRow.setVisible(false);
		anchorPanel.add(DevUi.formRow("Light definition", anchorLightCombo));
		anchorPanel.add(baryRow);
		anchorPanel.setVisible(false);

		JPanel editorBody = new JPanel();
		editorBody.setLayout(new BoxLayout(editorBody, BoxLayout.Y_AXIS));
		editorBody.setOpaque(false);
		editorBody.add(DevUi.section("Brush", true, null,
			DevUi.formRow("Light definition", lightBrushCombo),
			editorHint).root);
		editorBody.add(DevUi.section("Piece", true, null,
			DevUi.formRow("Name", pieceNameField),
			DevUi.formRow("Item IDs", itemIdsField),
			DevUi.vectorRow("Offset", offsetXSpinner, offsetYSpinner, offsetZSpinner)).root);
		editorBody.add(DevUi.section("Selected anchor", true, null, anchorPanel).root);

		return DevUi.scroll(editorBody);
	}

	private JPanel buildDefinitionsPanel() {
		definitionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		DevUi.styleList(definitionList);
		definitionList.addListSelectionListener(e -> {
			if (e.getValueIsAdjusting())
				return;
			selectedDefinitionId = definitionList.getSelectedValue();
			refreshDefinitionEditor();
		});

		JButton addDef = new JButton("New");
		addDef.addActionListener(e -> {
			String name = javax.swing.JOptionPane.showInputDialog(this, "Light description:");
			if (name != null && !name.trim().isEmpty()) {
				callbacks.createDefinition(name.trim());
				refreshDefinitionList(name.trim());
			}
		});
		JButton delDef = new JButton("Delete");
		delDef.addActionListener(e -> {
			if (selectedDefinitionId != null) {
				callbacks.deleteDefinition(selectedDefinitionId);
				refreshDefinitionList(null);
			}
		});
		DevUi.styleToolbarButton(addDef);
		DevUi.styleToolbarButton(delDef);

		JPanel defButtons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
		defButtons.setOpaque(false);
		defButtons.add(addDef);
		defButtons.add(delDef);

		DevUi.sizeField(defDescriptionField);
		DevUi.sizeField(colorButton);
		DevUi.styleCombo(typeCombo);
		DevUi.styleCombo(alignmentCombo);
		for (JSpinner spinner : new JSpinner[] {
			radiusSpinner, strengthSpinner, heightSpinner, defOffsetXSpinner, defOffsetYSpinner, defOffsetZSpinner,
			durationSpinner, rangeSpinner, fadeInSpinner, fadeOutSpinner, spawnDelaySpinner, despawnDelaySpinner,
			innerConeSpinner, outerConeSpinner, conePitchSpinner, maskScaleSpinner, renderableIndexSpinner
		})
			DevUi.sizeField(spinner);
		for (JTextField field : new JTextField[] {
			maskField, animationIdsField, npcIdsField, objectIdsField, projectileIdsField, graphicsIdsField
		})
			DevUi.sizeField(field);

		animationIdsField.setToolTipText("Comma-separated animation IDs (or gameval names in lights.json)");
		npcIdsField.setToolTipText("Comma-separated NPC IDs for scene attachment");
		objectIdsField.setToolTipText("Comma-separated object IDs for scene attachment");
		projectileIdsField.setToolTipText("Comma-separated projectile/spotanim IDs");
		graphicsIdsField.setToolTipText("Comma-separated graphics object IDs");
		fadeInSpinner.setToolTipText("Fade-in duration in milliseconds");
		fadeOutSpinner.setToolTipText("Fade-out duration in milliseconds");
		spawnDelaySpinner.setToolTipText("Delay before the light becomes visible (ms)");
		despawnDelaySpinner.setToolTipText("Delay after despawn before removal (ms)");
		renderableIndexSpinner.setToolTipText("Renderable slot on multi-part objects (-1 = any)");
		maskField.setToolTipText("Mask from textures/light_masks/ or \"circle\". 256x256, soft edges via alpha/grayscale.");

		colorButton.addActionListener(e -> pickDefinitionColor());
		Runnable saveDef = this::saveDefinitionFromUi;
		defDescriptionField.getDocument().addDocumentListener(docListener(saveDef));
		for (JSpinner spinner : new JSpinner[] {
			radiusSpinner, strengthSpinner, heightSpinner, defOffsetXSpinner, defOffsetYSpinner, defOffsetZSpinner,
			durationSpinner, rangeSpinner, fadeInSpinner, fadeOutSpinner, spawnDelaySpinner, despawnDelaySpinner,
			innerConeSpinner, outerConeSpinner, conePitchSpinner, maskScaleSpinner, renderableIndexSpinner
		})
			spinner.addChangeListener(e -> saveDef.run());
		for (JTextField field : new JTextField[] {
			maskField, animationIdsField, npcIdsField, objectIdsField, projectileIdsField, graphicsIdsField
		})
			field.getDocument().addDocumentListener(docListener(saveDef));
		for (JCheckBox check : new JCheckBox[] {
			fixedDespawnCheck, despawnWithParentCheck, visibleOtherPlanesCheck,
			ignoreActorHidingCheck, waitForAnimationCheck
		})
			check.addActionListener(e -> saveDef.run());
		typeCombo.addActionListener(e -> saveDef.run());
		alignmentCombo.addActionListener(e -> saveDef.run());

		JPanel editor = new JPanel();
		editor.setLayout(new BoxLayout(editor, BoxLayout.Y_AXIS));
		editor.setOpaque(false);
		editor.add(DevUi.section("Identity", true, null,
			DevUi.formRow("Description", defDescriptionField)).root);
		editor.add(DevUi.section("Appearance", true, null,
			DevUi.formRow("Radius", radiusSpinner),
			DevUi.formRow("Strength", strengthSpinner),
			DevUi.formRow("Color", colorButton),
			DevUi.formRow("Type", typeCombo),
			DevUi.formRow("Height", heightSpinner)).root);
		editor.add(DevUi.section("Placement", true, null,
			DevUi.formRow("Alignment", alignmentCombo),
			DevUi.vectorRow("Offset", defOffsetXSpinner, defOffsetYSpinner, defOffsetZSpinner)).root);
		editor.add(DevUi.section("Animation", true, null,
			DevUi.formRow("Duration", durationSpinner),
			DevUi.formRow("Range", rangeSpinner),
			DevUi.formRow("Fade in (ms)", fadeInSpinner),
			DevUi.formRow("Fade out (ms)", fadeOutSpinner),
			DevUi.formRow("Spawn delay (ms)", spawnDelaySpinner),
			DevUi.formRow("Despawn delay (ms)", despawnDelaySpinner),
			DevUi.formRow("Fixed despawn", fixedDespawnCheck)).root);
		editor.add(DevUi.section("Spotlight", true, null,
			DevUi.formRow("Inner cone", innerConeSpinner),
			DevUi.formRow("Outer cone", outerConeSpinner),
			DevUi.formRow("Cone pitch", conePitchSpinner),
			DevUi.formRow("Mask", maskField),
			DevUi.formRow("Mask scale", maskScaleSpinner)).root);
		editor.add(DevUi.section("Behavior", true, null,
			DevUi.formRow("Despawn w/ parent", despawnWithParentCheck),
			DevUi.formRow("Other planes", visibleOtherPlanesCheck),
			DevUi.formRow("Ignore hiding", ignoreActorHidingCheck),
			DevUi.formRow("Wait for anim", waitForAnimationCheck),
			DevUi.formRow("Renderable #", renderableIndexSpinner)).root);
		editor.add(DevUi.section("Attachment filters", true, null,
			DevUi.formRow("Animation IDs", animationIdsField),
			DevUi.formRow("NPC IDs", npcIdsField),
			DevUi.formRow("Object IDs", objectIdsField),
			DevUi.formRow("Projectile IDs", projectileIdsField),
			DevUi.formRow("Graphics IDs", graphicsIdsField)).root);

		JScrollPane listScroll = new JScrollPane(definitionList);
		listScroll.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		listScroll.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);
		listScroll.setPreferredSize(new Dimension(0, 140));

		JPanel panel = new JPanel(new BorderLayout(0, 6));
		DevUi.themePanel(panel);
		panel.add(defButtons, BorderLayout.NORTH);
		JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, listScroll, DevUi.scroll(editor));
		split.setResizeWeight(0.28);
		split.setDividerLocation(140);
		panel.add(split, BorderLayout.CENTER);
		return panel;
	}

	private JPanel buildViewportToolbar() {
		JToggleButton placeBtn = new JToggleButton("Place", true);
		JToggleButton deleteBtn = new JToggleButton("Remove");
		JToggleButton faceBtn = new JToggleButton("Face", true);
		JToggleButton vertBtn = vertexPickButton;
		setToolIcon(placeBtn, "mouse-pointer-2", "Place anchor (P)");
		setToolIcon(deleteBtn, "eraser", "Remove anchor (X)");
		setToolIcon(faceBtn, "triangle", "Pick faces (T)");
		setToolIcon(vertBtn, "dot", "Pick vertices (V)");

		DevUi.styleSegmentButton(placeBtn, true);
		DevUi.styleSegmentButton(deleteBtn, false);
		placeBtn.addActionListener(e -> viewport.setEditMode(ViewportPanel.EditMode.PLACE));
		deleteBtn.addActionListener(e -> viewport.setEditMode(ViewportPanel.EditMode.DELETE));
		faceBtn.addActionListener(e -> viewport.setPickTarget(ViewportPanel.PickTarget.FACE));
		vertBtn.addActionListener(e -> viewport.setPickTarget(ViewportPanel.PickTarget.POINT));
		viewport.setOnEditModeChanged(mode -> {
			if (syncingEditMode)
				return;
			syncingEditMode = true;
			DevUi.styleSegmentButton(placeBtn, mode == ViewportPanel.EditMode.PLACE);
			DevUi.styleSegmentButton(deleteBtn, mode == ViewportPanel.EditMode.DELETE);
			placeBtn.setSelected(mode == ViewportPanel.EditMode.PLACE);
			deleteBtn.setSelected(mode == ViewportPanel.EditMode.DELETE);
			syncingEditMode = false;
			updateHint();
		});
		viewport.setOnPickTargetChanged(target -> {
			if (syncingPickTarget)
				return;
			syncingPickTarget = true;
			boolean face = target == ViewportPanel.PickTarget.FACE;
			DevUi.styleSegmentButton(faceBtn, face);
			DevUi.styleSegmentButton(vertBtn, !face);
			faceBtn.setSelected(face);
			vertBtn.setSelected(!face);
			syncingPickTarget = false;
			updateHint();
		});

		JButton topBtn = new JButton("Top");
		JButton sideBtn = new JButton("Side");
		JButton behindBtn = new JButton("Front");
		JButton colorsBtn = new JButton("Colors");
		JButton wireBtn = new JButton("Wire");
		JButton dotsBtn = new JButton("Dots");
		JToggleButton gridBtn = new JToggleButton(icon("grid"), true);
		gridBtn.setToolTipText("Toggle viewport grid");
		gridBtn.getAccessibleContext().setAccessibleName("Viewport grid");
		DevUi.compactButton(gridBtn);
		gridBtn.addActionListener(e -> viewport.setShowGrid(gridBtn.isSelected()));
		topBtn.addActionListener(e -> viewport.setViewTop());
		sideBtn.addActionListener(e -> viewport.setViewRight());
		behindBtn.addActionListener(e -> viewport.setViewFront());
		setToolIcon(colorsBtn, "palette", "Model face colors (C)");
		setToolIcon(wireBtn, "layers", "Triangle wireframe (W)");
		setToolIcon(dotsBtn, "grip", "Vertex dots (D)");
		colorsBtn.addActionListener(e -> toggleViewportFaceColors(colorsBtn));
		wireBtn.addActionListener(e -> toggleViewportWireframe(wireBtn));
		dotsBtn.addActionListener(e -> toggleViewportVertices(dotsBtn));
		DevUi.styleSegmentButton(colorsBtn, viewport.isShowFaceColors());
		DevUi.styleSegmentButton(wireBtn, viewport.isShowWireframe());
		DevUi.styleSegmentButton(dotsBtn, viewport.isShowVertices());
		for (JButton btn : new JButton[] { topBtn, sideBtn, behindBtn })
			DevUi.styleToolbarButton(btn);

		JPanel bar = new JPanel(new BorderLayout(8, 0));
		bar.setOpaque(true);
		bar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		bar.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(3, 8, 2, 8)));

		JPanel modes = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
		modes.setOpaque(false);
		modes.add(DevUi.segmentBar(placeBtn, deleteBtn));
		modes.add(new JSeparator(SwingConstants.VERTICAL));
		modes.add(DevUi.segmentBar(faceBtn, vertBtn));
		modes.add(new JSeparator(SwingConstants.VERTICAL));
		modes.add(topBtn);
		modes.add(sideBtn);
		modes.add(behindBtn);
		modes.add(new JSeparator(SwingConstants.VERTICAL));
		modes.add(colorsBtn);
		modes.add(wireBtn);
		modes.add(dotsBtn);
		modes.add(gridBtn);
		bar.add(modes, BorderLayout.CENTER);
		return bar;
	}

	private static void setToolIcon(javax.swing.AbstractButton button, String iconName, String label) {
		button.setIcon(icon(iconName));
		button.setText(null);
		button.setToolTipText(label);
		button.getAccessibleContext().setAccessibleName(label);
	}

	private void toggleViewportFaceColors(JButton button) {
		viewport.setShowFaceColors(!viewport.isShowFaceColors());
		DevUi.styleSegmentButton(button, viewport.isShowFaceColors());
	}

	private void toggleViewportWireframe(JButton button) {
		viewport.setShowWireframe(!viewport.isShowWireframe());
		DevUi.styleSegmentButton(button, viewport.isShowWireframe());
	}

	private void toggleViewportVertices(JButton button) {
		viewport.setShowVertices(!viewport.isShowVertices());
		DevUi.styleSegmentButton(button, viewport.isShowVertices());
	}

	private void showCard(String name) {
		cardLayout.show(cards, name);
		editingParticles = "particles".equals(name);
		DevUi.styleSegmentButton(placementNavBtn, "placement".equals(name));
		DevUi.styleSegmentButton(definitionsNavBtn, "definitions".equals(name));
		DevUi.styleSegmentButton(particlesNavBtn, editingParticles);
		vertexPickButton.setEnabled(!editingParticles);
		viewport.setFacePickingOnly(editingParticles);
		particlesPanel.setActive(editingParticles);
		refreshMarkers();
	}

	private void updateLoadPanel() {
		loadPanel.setVisible(selectedMode > 0);
	}

	private void applyInSceneCaptureRow(int captureId) {
		selectedCaptureId = captureId;
		appliedPieceIndex = -1;
		viewport.setPieceFilter(-1);
		idField.setText(String.valueOf(captureId));
		if (captureId == lastAutoLoadedCaptureId)
			return;
		lastAutoLoadedCaptureId = captureId;
		switch (selectedMode) {
			case 1:
				callbacks.loadObject(captureId);
				break;
			case 2:
				callbacks.loadNpc(captureId);
				break;
			case 3:
				callbacks.loadGraphic(captureId);
				break;
		}
	}

	private void loadFromIdField() {
		String text = idField.getText().trim();
		int id = -1;
		if (!text.isEmpty()) {
			try {
				id = Integer.parseInt(text);
			} catch (NumberFormatException ignored) {
				return;
			}
		} else {
			int index = rowList.getSelectedIndex();
			if (index >= 0 && index < rows.size())
				id = rows.get(index).sightingId;
		}
		if (id < 0)
			return;
		lastAutoLoadedCaptureId = id;
		selectedCaptureId = id;
		appliedPieceIndex = -1;
		viewport.setPieceFilter(-1);
		switch (selectedMode) {
			case 1:
				callbacks.loadObject(id);
				break;
			case 2:
				callbacks.loadNpc(id);
				break;
			case 3:
				callbacks.loadGraphic(id);
				break;
		}
	}

	public void showHint(String text) {
		editorHint.setText(text);
	}

	public void setLoading(boolean loading) {
		setCursor(loading ? java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR) : null);
		if (loading)
			editorHint.setText("Capturing mesh…");
		else
			updateHint();
	}

	public void rebuildSightingsList() {
		if (selectedMode == 1 || selectedMode == 2)
			rebuildRows();
	}

	public void setLightDescriptions(List<String> descriptions) {
		populating = true;
		String brush = (String) lightBrushCombo.getSelectedItem();
		String anchor = (String) anchorLightCombo.getSelectedItem();
		DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>(descriptions.toArray(new String[0]));
		lightBrushCombo.setModel(model);
		anchorLightCombo.setModel(new DefaultComboBoxModel<>(descriptions.toArray(new String[0])));
		if (brush != null)
			lightBrushCombo.setSelectedItem(brush);
		if (anchor != null)
			anchorLightCombo.setSelectedItem(anchor);
		refreshDefinitionList(selectedDefinitionId);
		populating = false;
	}

	public void refreshDefinitionList(@Nullable String selectId) {
		populating = true;
		definitionListModel.clear();
		for (String desc : callbacks.getLightDescriptions())
			definitionListModel.addElement(desc);
		if (selectId != null)
			definitionList.setSelectedValue(selectId, true);
		else if (!definitionListModel.isEmpty())
			definitionList.setSelectedIndex(0);
		populating = false;
		refreshDefinitionEditor();
	}

	private void refreshDefinitionEditor() {
		if (populating || selectedDefinitionId == null)
			return;
		populating = true;
		LightDefinition def = callbacks.getDefinition(selectedDefinitionId);
		defDescriptionField.setText(def.description != null ? def.description : "");
		radiusSpinner.setValue(def.radius);
		strengthSpinner.setValue((double) def.strength);
		heightSpinner.setValue(def.height);
		typeCombo.setSelectedItem(def.type != null ? def.type : LightType.STATIC);
		alignmentCombo.setSelectedItem(def.alignment != null ? def.alignment : Alignment.CUSTOM);
		float[] offset = def.offset != null && def.offset.length == 3 ? def.offset : new float[3];
		defOffsetXSpinner.setValue((double) offset[0]);
		defOffsetYSpinner.setValue((double) offset[1]);
		defOffsetZSpinner.setValue((double) offset[2]);
		durationSpinner.setValue((double) def.duration);
		rangeSpinner.setValue((double) def.range);
		fadeInSpinner.setValue(def.fadeInDuration);
		fadeOutSpinner.setValue(def.fadeOutDuration);
		spawnDelaySpinner.setValue(def.spawnDelay);
		despawnDelaySpinner.setValue(def.despawnDelay);
		fixedDespawnCheck.setSelected(def.fixedDespawnTime);
		despawnWithParentCheck.setSelected(def.despawnWithParent);
		visibleOtherPlanesCheck.setSelected(def.visibleFromOtherPlanes);
		ignoreActorHidingCheck.setSelected(def.ignoreActorHiding);
		waitForAnimationCheck.setSelected(def.waitForAnimation);
		renderableIndexSpinner.setValue(def.renderableIndex);
		animationIdsField.setText(formatIds(def.animationIds));
		npcIdsField.setText(formatIds(def.npcIds));
		objectIdsField.setText(formatIds(def.objectIds));
		projectileIdsField.setText(formatIds(def.projectileIds));
		graphicsIdsField.setText(formatIds(def.graphicsObjectIds));
		innerConeSpinner.setValue((double) def.innerConeAngle);
		outerConeSpinner.setValue((double) def.outerConeAngle);
		conePitchSpinner.setValue((double) def.conePitch);
		maskField.setText(def.mask == null ? "" : def.mask);
		maskScaleSpinner.setValue((double) def.maskScale);
		if (def.color != null && def.color.length == 3) {
			float[] srgb = ColorUtils.linearToSrgb(def.color);
			colorButton.setBackground(new Color(
				Math.round(srgb[0] * 255),
				Math.round(srgb[1] * 255),
				Math.round(srgb[2] * 255)
			));
		}
		populating = false;
	}

	private void saveDefinitionFromUi() {
		if (populating || selectedDefinitionId == null)
			return;
		LightDefinition def = callbacks.getDefinition(selectedDefinitionId);
		def.description = defDescriptionField.getText().trim();
		def.radius = ((Number) radiusSpinner.getValue()).intValue();
		def.strength = ((Number) strengthSpinner.getValue()).floatValue();
		def.height = ((Number) heightSpinner.getValue()).intValue();
		def.type = (LightType) typeCombo.getSelectedItem();
		def.alignment = (Alignment) alignmentCombo.getSelectedItem();
		def.offset = new float[] {
			((Number) defOffsetXSpinner.getValue()).floatValue(),
			((Number) defOffsetYSpinner.getValue()).floatValue(),
			((Number) defOffsetZSpinner.getValue()).floatValue()
		};
		def.duration = ((Number) durationSpinner.getValue()).floatValue();
		def.range = ((Number) rangeSpinner.getValue()).floatValue();
		def.fadeInDuration = ((Number) fadeInSpinner.getValue()).intValue();
		def.fadeOutDuration = ((Number) fadeOutSpinner.getValue()).intValue();
		def.spawnDelay = ((Number) spawnDelaySpinner.getValue()).intValue();
		def.despawnDelay = ((Number) despawnDelaySpinner.getValue()).intValue();
		def.fixedDespawnTime = fixedDespawnCheck.isSelected();
		def.despawnWithParent = despawnWithParentCheck.isSelected();
		def.visibleFromOtherPlanes = visibleOtherPlanesCheck.isSelected();
		def.ignoreActorHiding = ignoreActorHidingCheck.isSelected();
		def.waitForAnimation = waitForAnimationCheck.isSelected();
		def.renderableIndex = ((Number) renderableIndexSpinner.getValue()).intValue();
		def.animationIds = parseIds(animationIdsField.getText());
		def.npcIds = parseIds(npcIdsField.getText());
		def.objectIds = parseIds(objectIdsField.getText());
		def.projectileIds = parseIds(projectileIdsField.getText());
		def.graphicsObjectIds = parseIds(graphicsIdsField.getText());
		def.innerConeAngle = ((Number) innerConeSpinner.getValue()).floatValue();
		def.outerConeAngle = ((Number) outerConeSpinner.getValue()).floatValue();
		def.conePitch = ((Number) conePitchSpinner.getValue()).floatValue();
		String mask = maskField.getText().trim();
		def.mask = mask.isEmpty() ? null : mask;
		def.maskScale = ((Number) maskScaleSpinner.getValue()).floatValue();
		Color bg = colorButton.getBackground();
		def.color = ColorUtils.srgbToLinear(ColorUtils.srgb(bg.getRed(), bg.getGreen(), bg.getBlue()));
		callbacks.saveDefinition(selectedDefinitionId, def);
		if (!def.description.equals(selectedDefinitionId)) {
			selectedDefinitionId = def.description;
			refreshDefinitionList(selectedDefinitionId);
		}
	}

	private void pickDefinitionColor() {
		Color chosen = JColorChooser.showDialog(this, "Light color", colorButton.getBackground());
		if (chosen != null) {
			colorButton.setBackground(chosen);
			saveDefinitionFromUi();
		}
	}

	public void setSnapshot(@Nullable ModelSnapshot snapshot, @Nullable String targetRootLabel) {
		clearRecording();
		selectAnchor(null);
		releaseSnapshot();
		this.snapshot = snapshot;
		this.targetRootLabel = targetRootLabel;
		particlesPanel.select(null);
		if (snapshot != null)
			viewport.setSnapshot(snapshot);
		else
			viewport.clearSnapshot();
		if (selectedMode == 0)
			rebuildRows();
		else
			refreshMarkers();
	}

	private void releaseSnapshot() {
		if (snapshot != null) {
			snapshot.release();
			snapshot = null;
		}
		viewport.clearSnapshot();
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

	public void selectAnchor(@Nullable AnchorSelection anchor) {
		selectedAnchor = anchor;
		populating = true;
		if (anchor == null) {
			anchorPanel.setVisible(false);
			anchorTitle.setText("No anchor selected");
		} else {
			anchorPanel.setVisible(true);
			String kind = anchor.triangle ? "triangle" : "vertex";
			anchorTitle.setText(kind + " " + anchor.localIndex + " on piece " + (anchor.pieceIndex + 1));
			String desc = callbacks.getAnchorLightDescription(anchor);
			if (desc != null)
				anchorLightCombo.setSelectedItem(desc);
			baryRow.setVisible(anchor.triangle);
			if (anchor.triangle) {
				float[] bary = callbacks.getAnchorBarycentric(anchor);
				if (bary != null) {
					bary0Spinner.setValue(bary[0]);
					bary1Spinner.setValue(bary[1]);
					bary2Spinner.setValue(bary[2]);
				}
			}
			String defId = desc != null ? desc : (String) anchorLightCombo.getSelectedItem();
			if (defId != null) {
				selectedDefinitionId = defId;
				definitionList.setSelectedValue(defId, true);
				refreshDefinitionEditor();
			}
		}
		populating = false;
	}

	public void refreshMarkers() {
		viewport.setSelectedFaces(editingParticles ? Set.of() : callbacks.litFaces(appliedPieceIndex));
		viewport.setSelectedVertices(editingParticles ? Set.of() : callbacks.litVertices(appliedPieceIndex));
		viewport.repaint();
	}

	public int getAppliedPieceIndex() {
		return appliedPieceIndex;
	}

	public String getPickLightDescription() {
		Object brush = lightBrushCombo.getSelectedItem();
		if (brush instanceof String && !((String) brush).isEmpty())
			return (String) brush;
		return "Torch";
	}

	public String getItemIdsFieldText() {
		return itemIdsField.getText();
	}

	public void rebuildPieceListRow(int pieceIndex) {
		for (int i = 0; i < rows.size(); i++) {
			Row row = rows.get(i);
			if (row.equipmentParts.isEmpty() && row.pieceIndex == pieceIndex) {
				rowListModel.set(i, callbacks.getPieceListLabel(pieceIndex));
				break;
			}
		}
	}

	private void rebuildRows() {
		rebuildingRows = true;
		int keepPiece = appliedPieceIndex;
		int keepCaptureId = selectedCaptureId >= 0 ? selectedCaptureId : lastAutoLoadedCaptureId;
		rowListModel.clear();
		rows.clear();
		int selectRow = 0;

		if (selectedMode == 1) {
			for (LightMeshCapture.Sighting sighting : callbacks.getObjectSightings()) {
				addRow(sighting.formatRow(), new Row(-1, sighting.id));
				if (sighting.id == keepCaptureId)
					selectRow = rows.size() - 1;
			}
		} else if (selectedMode == 2) {
			for (LightMeshCapture.Sighting sighting : callbacks.getNpcSightings()) {
				addRow(sighting.formatRow(), new Row(-1, sighting.id));
				if (sighting.id == keepCaptureId)
					selectRow = rows.size() - 1;
			}
		} else if (snapshot != null) {
			String root = targetRootLabel != null ? targetRootLabel : "Player";
			addRow(root + " (" + snapshot.getVertexCount() + "v)", new Row(-1));
			if (selectedMode == 0) {
				var groups = new LinkedHashMap<Integer, List<Integer>>();
				for (int i = 0; i < snapshot.getPieces().size(); i++) {
					int itemId = snapshot.getPieces().get(i).getItemId();
					if (itemId >= 0)
						groups.computeIfAbsent(itemId, key -> new ArrayList<>()).add(i);
				}
				for (var entry : groups.entrySet()) {
					List<Integer> parts = entry.getValue();
					var first = snapshot.getPieces().get(parts.get(0));
					String label = first.getLabel() + " (" + parts.size() + (parts.size() == 1 ? " part)" : " parts)");
					addRow(label, new Row(parts));
				}
			} else {
				for (int i = 0; i < snapshot.getPieces().size(); i++)
					addRow(callbacks.getPieceListLabel(i), new Row(i));
			}
		}

		for (int i = 0; i < rows.size(); i++) {
			if (rows.get(i).containsPart(keepPiece)) {
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
		if (rows.isEmpty()) {
			applyPieceRow(new Row(-1), -1);
			return;
		}
		int index = Math.max(0, Math.min(selectRow, rows.size() - 1));
		Row target = rows.get(index);
		if (target.sightingId < 0) {
			rebuildingRows = true;
			rowList.setSelectedIndex(index);
			rebuildingRows = false;
			applyPieceRow(target, appliedPieceIndex);
		} else {
			partSelectorRow.setVisible(false);
			rowList.setSelectedIndex(index);
		}
	}

	private void applyPieceRow(Row row, int preferredPiece) {
		int part = row.equipmentParts.indexOf(preferredPiece);
		if (part < 0)
			part = 0;
		populating = true;
		partSelector.removeAllItems();
		for (int index : row.equipmentParts) {
			var piece = snapshot.getPieces().get(index);
			partSelector.addItem("Model " + piece.getModelId() + " (" + piece.getFaces().length + " faces)");
		}
		if (!row.equipmentParts.isEmpty())
			partSelector.setSelectedIndex(part);
		partSelectorRow.setVisible(!row.equipmentParts.isEmpty());
		partSelector.setEnabled(row.equipmentParts.size() > 1);
		populating = false;
		applyPiece(row.equipmentParts.isEmpty() ? row.pieceIndex : row.equipmentParts.get(part));
	}

	private void applyPiece(int index) {
		if (appliedPieceIndex != index)
			selectAnchor(null);
		appliedPieceIndex = index;
		viewport.setPieceFilter(index);
		particlesPanel.select(snapshot != null && index >= 0 ? snapshot.getPieces().get(index) : null);
		loadPieceSettings();
		refreshMarkers();
		updateHint();
	}

	private void loadPieceSettings() {
		boolean show = appliedPieceIndex >= 0 && snapshot != null;
		pieceNameField.setEnabled(show);
		itemIdsField.setEnabled(show);
		itemIdsField.setEditable(false);
		offsetXSpinner.setEnabled(show);
		offsetYSpinner.setEnabled(show);
		offsetZSpinner.setEnabled(show);
		if (!show)
			return;
		populating = true;
		pieceNameField.setText(callbacks.getPieceName(appliedPieceIndex));
		itemIdsField.setText(callbacks.getPieceItemIdsText(appliedPieceIndex));
		float[] offset = callbacks.getProfileOffset(appliedPieceIndex);
		offsetXSpinner.setValue((double) offset[0]);
		offsetYSpinner.setValue((double) offset[1]);
		offsetZSpinner.setValue((double) offset[2]);
		populating = false;
	}

	private void applyPieceSettingsFromUi() {
		if (populating || appliedPieceIndex < 0 || snapshot == null)
			return;
		callbacks.applyPieceSettings(appliedPieceIndex, pieceNameField.getText(), itemIdsField.getText());
		rebuildPieceListRow(appliedPieceIndex);
	}

	private void updateHoverHint(@Nullable ViewportPanel.Pick pick) {
		if (pick == null) {
			updateHint();
			return;
		}
		String kind = pick.target == ViewportPanel.PickTarget.FACE ? "triangle" : "vertex";
		if (viewport.getEditMode() == ViewportPanel.EditMode.DELETE)
			editorHint.setText("Click or drag to remove " + kind + " " + pick.globalIndex);
		else
			editorHint.setText("Click " + kind + " " + pick.globalIndex + " to place · click lit anchor to edit");
	}

	private void updateHint() {
		if (appliedPieceIndex >= 0) {
			String pick = viewport.getPickTarget() == ViewportPanel.PickTarget.FACE ? "face" : "vertex";
			editorHint.setText(viewport.getEditMode() == ViewportPanel.EditMode.DELETE ?
				"Click an anchor to remove it." : "Choose a light and click a " + pick + ".");
		} else if (selectedMode > 0) {
			editorHint.setText("Select a sighting and Load, or type an ID");
		} else if (snapshot != null) {
			editorHint.setText("Select a mesh piece, then place lights");
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
			? "frame " + recordingFrames[i] + " (" + (i + 1) + "/" + recordingFrames.length + ")"
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

	private static JCheckBox styleCheck(String text) {
		JCheckBox check = new JCheckBox(text);
		check.setOpaque(false);
		check.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		check.setFont(DevUi.bodyFont(check.getFont()));
		return check;
	}

	private static String formatIds(@Nullable Set<Integer> ids) {
		if (ids == null || ids.isEmpty())
			return "";
		return ids.stream()
			.sorted()
			.map(String::valueOf)
			.collect(Collectors.joining(", "));
	}

	private static HashSet<Integer> parseIds(@Nullable String text) {
		HashSet<Integer> ids = new HashSet<>();
		if (text == null || text.trim().isEmpty())
			return ids;
		for (String part : text.split(",")) {
			String trimmed = part.trim();
			if (trimmed.isEmpty())
				continue;
			try {
				ids.add(Integer.parseInt(trimmed));
			} catch (NumberFormatException ignored) {}
		}
		return ids;
	}

	private static DocumentListener docListener(Runnable onChange) {
		return new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) { onChange.run(); }
			@Override
			public void removeUpdate(DocumentEvent e) { onChange.run(); }
			@Override
			public void changedUpdate(DocumentEvent e) { onChange.run(); }
		};
	}
}
