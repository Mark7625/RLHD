package rs117.hd.particles;

import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.runelite.client.ui.ColorScheme;
import javax.annotation.Nullable;
import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ComboBoxModel;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.KeyStroke;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import lombok.Getter;

/**
 * Dev tool window with a target mode selector: player-model authoring
 * (emitter profiles and mesh pieces on the left with a per-profile style
 * editor, orbitable wireframe viewport on the right; clicking a vertex
 * toggles it as an emitter) and projectile authoring (profiles plus a live
 * capture list of recently seen projectile IDs). Profiles can be duplicated
 * and gated on worn item IDs so recolored variants of one model get their
 * own particles.
 */
class ModelViewerFrame extends JFrame
{
	/**
	 * Everything the viewer needs from the plugin. All methods run on the EDT.
	 */
	interface Callbacks
	{
		void refreshSnapshot();

		void vertexToggled(@Nullable String profileKey, int vertex);

		void boxSelected(@Nullable String profileKey, Set<Integer> vertices, boolean add);

		void selectionChanged();

		/** True when the viewport should accept vertex picking (player or loaded target mesh). */
		boolean canPickVertices();

		@Nullable
		EmitterProfile profile(String profileKey);

		void saveProfile(String profileKey, EmitterProfile profile);

		void saveDefinition(String definitionId, ParticleDefinition definition);

		@Nullable
		ParticleDefinition definition(String definitionId);

		void exportBundle();

		List<String> particleTextureFiles();

		List<String> particleDefinitionIds();

		void setProfileDefinition(String profileKey, String definitionId);

		@Nullable
		String duplicateProfile(String profileKey);

		void deleteProfile(String profileKey);

		String createProjectileProfile(int projectileId);

		String createGraphicProfile(int graphicId);

		String createWeatherProfile(List<String> weatherAreas);

		List<String> areaNames();

		/**
		 * Capture a nearby instance of this scenery object into the viewer.
		 */
		void loadObject(int objectId);

		/**
		 * Capture a nearby instance of this NPC into the viewer.
		 */
		void loadNpc(int npcId);

		/**
		 * Capture a live instance of this spot anim / graphic into the
		 * viewer for vertex picking.
		 */
		void loadGraphic(int graphicId);

		/**
		 * Pose the loaded NPC's cache mesh at every frame of this animation
		 * ID, arriving as an exact-frame recording. NPC snapshots only.
		 */
		void poseAnimation(int animId);

		/**
		 * The user manually returned to the model view; drop any object
		 * context and show the player again.
		 */
		void playerViewSelected();
	}

	/**
	 * Scenery object kind on a tile — used to filter the in-scene object list.
	 */
	enum ObjectKind
	{
		GAME_OBJECT("Game object"),
		WALL("Wall"),
		DECORATIVE("Decorative"),
		GROUND("Ground");

		final String label;

		ObjectKind(String label)
		{
			this.label = label;
		}

		static ObjectKind of(net.runelite.api.TileObject object)
		{
			if (object instanceof net.runelite.api.GameObject)
			{
				return GAME_OBJECT;
			}
			if (object instanceof net.runelite.api.WallObject)
			{
				return WALL;
			}
			if (object instanceof net.runelite.api.DecorativeObject)
			{
				return DECORATIVE;
			}
			return GROUND;
		}
	}

	enum ObjectTypeFilter
	{
		GAME_OBJECTS("Game objects", ObjectKind.GAME_OBJECT),
		WALLS("Walls", ObjectKind.WALL),
		DECORATIVE("Decorative", ObjectKind.DECORATIVE),
		GROUND("Ground", ObjectKind.GROUND),
		ALL("All types", null);

		final String label;
		@Nullable
		final ObjectKind kind;

		ObjectTypeFilter(String label, @Nullable ObjectKind kind)
		{
			this.label = label;
			this.kind = kind;
		}

		boolean matches(ObjectKind sightingKind)
		{
			return kind == null || kind == sightingKind;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	/**
	 * A scenery object in the loaded scene, for the world-object capture list.
	 */
	static class ObjectSighting
	{
		final int id;
		final String name;
		final int distanceTiles;
		final ObjectKind kind;
		final int instanceCount;

		ObjectSighting(int id, String name, int distanceTiles, ObjectKind kind, int instanceCount)
		{
			this.id = id;
			this.name = name;
			this.distanceTiles = distanceTiles;
			this.kind = kind;
			this.instanceCount = instanceCount;
		}
	}

	/**
	 * A spot anim / graphics ID recently seen, for the graphics capture
	 * list. Spot anims have no name in the cache, so source is who played
	 * it ("tile" for a graphics object).
	 */
	static class GraphicSighting
	{
		final int id;
		final String source;
		final int count;
		final int secondsAgo;

		GraphicSighting(int id, String source, int count, int secondsAgo)
		{
			this.id = id;
			this.source = source;
			this.count = count;
			this.secondsAgo = secondsAgo;
		}
	}

	/**
	 * One list entry per profile, or per piece if it has no profiles.
	 */
	static class ProfileEntry
	{
		final String key;
		final String name;
		final boolean filtered;

		ProfileEntry(String key, String name, boolean filtered)
		{
			this.key = key;
			this.name = name;
			this.filtered = filtered;
		}
	}

	private static class Row
	{
		final int pieceIndex;
		@Nullable
		final String profileKey;
		/**
		 * Projectile ID of a capture-list row, or -1.
		 */
		final int captureId;

		Row(int pieceIndex, @Nullable String profileKey)
		{
			this(pieceIndex, profileKey, -1);
		}

		Row(int pieceIndex, @Nullable String profileKey, int captureId)
		{
			this.pieceIndex = pieceIndex;
			this.profileKey = profileKey;
			this.captureId = captureId;
		}
	}

	private final Callbacks callbacks;
	private final ViewportPanel viewport;
	private final JPanel viewportToolbar;
	private JPanel viewportModeBar;
	private JLayeredPane viewportLayer;
	private final JButton viewportPlaceBtn = new JButton("Place");
	private final JButton viewportRemoveBtn = new JButton("Remove");
	private final JButton viewportColorsBtn = new JButton("Colors");
	private final JButton viewportWireBtn = new JButton("Wire");
	private final JButton viewportVertsBtn = new JButton("Verts");
	private final DefaultListModel<String> rowListModel = new DefaultListModel<>();
	private final JList<String> rowList;
	private final JButton emittersNavBtn = new JButton("Emitters");
	private final JButton definitionsNavBtn = new JButton("Definitions");
	private final CardLayout particleCardLayout = new CardLayout();
	private final JPanel particleCards = new JPanel(particleCardLayout);
	private final JButton inSceneFilterBtn = new JButton("In scene");
	private final JButton savedFilterBtn = new JButton("Saved");
	private final JPanel rowListFilterBar;
	private final JComboBox<ObjectTypeFilter> objectTypeFilterCombo =
		new JComboBox<>(ObjectTypeFilter.values());
	private boolean savedEmitterList;
	private final List<Row> rows = new ArrayList<>();
	private final JComboBox<String> modeSelector = new JComboBox<>(
		new String[]{"Player", "Projectiles", "World objects", "NPCs", "Graphics", "Weather"});
	private final JPanel projectileAddPanel = new JPanel(new BorderLayout(4, 0));
	private final JTextField projectileIdField = new JTextField();
	private final JPanel objectLoadPanel = new JPanel(new BorderLayout(4, 0));
	private final JTextField objectIdField = new JTextField();
	private final JPanel npcLoadPanel = new JPanel(new BorderLayout(4, 0));
	private final JTextField npcIdField = new JTextField();
	private final JPanel graphicAddPanel = new JPanel(new BorderLayout(4, 0));
	private final JTextField graphicIdField = new JTextField();
	private final JPanel weatherAddPanel = new JPanel(new BorderLayout(4, 0));
	private final JComboBox<String> weatherAreaCombo = new JComboBox<>();
	private final JPanel addPanelHolder = new JPanel(new BorderLayout());

	// Animation scrubber below the viewport, shown while a recording exists
	private final JSlider scrubSlider = new JSlider(0, 0, 0);
	private final JLabel scrubLabel = new JLabel(" ");
	private final JPanel scrubPanel = new JPanel(new BorderLayout(6, 0));
	private float[][] recordingXs;
	private float[][] recordingYs;
	private float[][] recordingZs;
	private int[] recordingFrames;

	// Style editor controls
	private final JButton colorButton = new JButton();
	private final JCheckBox colorFadeCheck = new JCheckBox();
	private final JButton endColorButton = new JButton();
	private final JSpinner fadeStartSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 100, 5));
	// Rows hidden while colour-over-life is off, to declutter the common case
	private JComponent endColorRow;
	private JComponent fadeStartRow;
	private final JComboBox<Shape> shapeCombo = new JComboBox<>(Shape.values());
	private static final String TEXTURE_DEFAULT = "";
	private final JComboBox<String> textureCombo = new JComboBox<>();
	private final JComboBox<String> definitionCombo = new JComboBox<>();
	private final JSpinner flipbookColsSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 16, 1));
	private final JSpinner flipbookRowsSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 16, 1));
	private final JComboBox<String> flipbookModeCombo = new JComboBox<>(new String[] { "None", "Order", "Random" });
	private JComponent flipbookColsRow;
	private JComponent flipbookRowsRow;
	private JComponent flipbookModeRow;
	private final JSpinner alphaSpinner = new JSpinner(new SpinnerNumberModel(128, 0, 255, 4));
	private final JSpinner sizeSpinner = new JSpinner(new SpinnerNumberModel(12, 2, 64, 1));
	private final JSpinner sizeJitterSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 32, 1));
	private final JSpinner rateSpinner = new JSpinner(new SpinnerNumberModel(80, 0, 1000, 4));
	private final JSpinner trailSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 200, 4));
	private final JSpinner lifetimeSpinner = new JSpinner(new SpinnerNumberModel(600, 100, 10000, 100));
	private final JSpinner moveLifetimeSpinner = new JSpinner(new SpinnerNumberModel(100, 10, 100, 4));
	private final JSpinner riseSpinner = new JSpinner(new SpinnerNumberModel(26, -256, 256, 2));
	private final JSpinner spreadSpinner = new JSpinner(new SpinnerNumberModel(12, 0, 256, 2));
	private final JSpinner gravitySpinner = new JSpinner(new SpinnerNumberModel(0, 0, 512, 8));
	private final JSpinner windXSpinner = new JSpinner(new SpinnerNumberModel(0, -128, 128, 2));
	private final JSpinner windYSpinner = new JSpinner(new SpinnerNumberModel(0, -128, 128, 2));
	private final JSpinner windZSpinner = new JSpinner(new SpinnerNumberModel(0, -128, 128, 2));
	private final JSpinner dragSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 300, 10));
	private final JSpinner vortexSpinner = new JSpinner(new SpinnerNumberModel(0, -256, 256, 4));
	private final JSpinner emitScaleSpinner = new JSpinner(new SpinnerNumberModel(100, 10, 400, 4));
	private final JSpinner stretchSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 300, 10));
	private final JSpinner stretchRampSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 100, 10));
	private final JSpinner jitterSpinner = new JSpinner(new SpinnerNumberModel(10, 0, 64, 1));
	private final JCheckBox uniformColorCheck = new JCheckBox();
	private final JCheckBox useEnvironmentLightCheck = new JCheckBox();
	private final JSpinner rotationSpeedSpinner = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 20.0, 0.1));
	private final JSpinner scaleStartSpinner = new JSpinner(new SpinnerNumberModel(100, 0, 500, 5));
	private final JSpinner scaleEndSpinner = new JSpinner(new SpinnerNumberModel(100, 0, 500, 5));
	private final JPanel colorPreviewPanel = new JPanel()
	{
		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			int w = getWidth();
			int h = getHeight();
			if (w <= 1 || h <= 0)
			{
				return;
			}
			Color start = colorButton.getBackground();
			Color end = endColorButton.getBackground();
			int alpha = (int) alphaSpinner.getValue() & 0xff;
			int startArgb = (alpha << 24) | (start.getRed() << 16) | (start.getGreen() << 8) | start.getBlue();
			int endArgb = (alpha << 24) | (end.getRed() << 16) | (end.getGreen() << 8) | end.getBlue();
			boolean uniform = uniformColorCheck.isSelected();
			int steps = w;
			for (int i = 0; i < steps; i++)
			{
				float t = steps <= 1 ? 0f : i / (float) (steps - 1);
				int argb = uniform
					? lerpPreviewArgb(startArgb, endArgb, t)
					: (i % 2 == 0 ? startArgb : endArgb);
				g.setColor(new Color(argb, true));
				g.fillRect(i, 0, 1, h);
			}
		}

		private int lerpPreviewArgb(int a, int b, float t)
		{
			int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
			int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
			int alpha = (int) alphaSpinner.getValue() & 0xff;
			int r = Math.round(ar + (br - ar) * t);
			int g = Math.round(ag + (bg - ag) * t);
			int bl = Math.round(ab + (bb - ab) * t);
			return (alpha << 24) | (r << 16) | (g << 8) | bl;
		}
	};
	private final JSpinner featherSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 8, 1));
	private final JSpinner interpolationSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 4, 1));
	private final JSpinner depthBiasSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 64, 1));
	private final JSpinner offsetXSpinner = new JSpinner(new SpinnerNumberModel(0, -256, 256, 1));
	private final JSpinner offsetYSpinner = new JSpinner(new SpinnerNumberModel(0, -256, 256, 1));
	private final JSpinner offsetZSpinner = new JSpinner(new SpinnerNumberModel(0, -256, 256, 1));
	private final JComboBox<String> weatherAreaPicker = new JComboBox<>();
	private final DefaultListModel<String> weatherAreaListModel = new DefaultListModel<>();
	private final JList<String> weatherAreaList = new JList<>(weatherAreaListModel);
	private final JButton weatherAreaAddBtn = new JButton("Add");
	private final JButton weatherAreaRemoveBtn = new JButton("Remove");
	private final JSpinner weatherPptSpinner = new JSpinner(new SpinnerNumberModel(10, 0, 1000, 0.1));
	private final JSpinner weatherDensitySpinner = new JSpinner(new SpinnerNumberModel(1.0, 0.0, 10.0, 0.05));
	private JComponent weatherAreaPickerRow;
	private JComponent weatherAreaListRow;
	private JComponent weatherPptRow;
	private JComponent weatherDensityRow;
	private final JTextField itemFilterField = new JTextField();
	private final JTextField animFilterField = new JTextField();
	private final JTextField animFramesField = new JTextField();
	private final JComboBox<String> wornItemsCombo = new JComboBox<>();
	private final JButton addWornItemButton = new JButton("+");
	private final JButton duplicateButton = new JButton("Duplicate");
	private final JButton deleteButton = new JButton("Delete");
	private final DefaultListModel<String> definitionListModel = new DefaultListModel<>();
	private final JList<String> definitionList = new JList<>(definitionListModel);

	private ModelSnapshot snapshot;
	@Getter
	@Nullable
	private String selectedProfileKey;
	@Nullable
	private String selectedDefinitionId;
	private int selectedCaptureId = -1;
	private String pendingSelection;
	private boolean populating;
	private boolean populatingDefinition;
	private boolean rebuildingRows;
	private boolean updatingMode;
	private boolean projectileMode;
	private boolean objectMode;
	private boolean npcMode;
	private boolean graphicMode;
	private boolean weatherMode;
	private int appliedPieceIndex = Integer.MIN_VALUE;
	private int lastAutoLoadedCaptureId = -1;

	// Latest data from the plugin, cached so mode switches can rebuild rows
	private Map<String, List<ProfileEntry>> profilesBySignature = Map.of();
	private List<ProfileEntry> projectileEntries = List.of();
	private List<int[]> recentProjectiles = List.of();
	private List<ProfileEntry> objectEntries = List.of();
	private List<ObjectSighting> objectSightings = List.of();
	private List<ProfileEntry> npcEntries = List.of();
	private List<ObjectSighting> npcSightings = List.of();
	private List<ProfileEntry> graphicEntries = List.of();
	private List<ProfileEntry> weatherEntries = List.of();
	private List<GraphicSighting> recentGraphics = List.of();

	private JScrollPane emitterScrollPane;
	private JScrollPane particleScrollPane;

	ModelViewerFrame(Callbacks callbacks)
	{
		super("Particles dev");
		setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		this.callbacks = callbacks;

		viewport = new ViewportPanel((vertices, add) ->
		{
			if (callbacks.canPickVertices())
			{
				callbacks.boxSelected(selectedProfileKey, vertices, add);
			}
		});
		viewportToolbar = buildViewportToolbar();

		rowList = new JList<>(rowListModel);
		rowList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		rowList.setFixedCellHeight(28);
		ParticleDevUi.styleList(rowList);
		rowList.setCellRenderer(new DefaultListCellRenderer()
		{
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index,
				boolean isSelected, boolean cellHasFocus)
			{
				super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				ParticleDevUi.applyListRendererFont(this);
				setBorder(BorderFactory.createEmptyBorder(2, 10, 2, 8));
				return this;
			}
		});
		rowList.addListSelectionListener(e ->
		{
			if (e.getValueIsAdjusting() || rebuildingRows)
			{
				return;
			}
			int index = rowList.getSelectedIndex();
			if (index >= 0 && index < rows.size())
			{
				applyRowSelection(rows.get(index));
			}
		});

		installRowListContextMenu();

		rowListFilterBar = ParticleDevUi.segmentBar(inSceneFilterBtn, savedFilterBtn);
		rowListFilterBar.setVisible(false);
		updateEmitterListFilterUi();

		objectTypeFilterCombo.setSelectedItem(ObjectTypeFilter.GAME_OBJECTS);
		ParticleDevUi.styleCombo(objectTypeFilterCombo);
		objectTypeFilterCombo.addActionListener(e ->
		{
			if (snapshot != null)
			{
				rebuildRows();
			}
		});

		inSceneFilterBtn.addActionListener(e -> setEmitterListSaved(false));
		savedFilterBtn.addActionListener(e -> setEmitterListSaved(true));

		emittersNavBtn.addActionListener(e -> showParticleSubview(false));
		definitionsNavBtn.addActionListener(e -> showParticleSubview(true));

		modeSelector.addActionListener(e ->
		{
			if (updatingMode)
			{
				return;
			}
			applyMode(modeSelector.getSelectedIndex());
			if (modeSelector.getSelectedIndex() == 0)
			{
				// Manual return to the model view always means the player;
				// object snapshots only arrive via Load
				callbacks.playerViewSelected();
			}
			if (snapshot != null)
			{
				rebuildRows();
			}
		});

		JButton refresh = new JButton("Refresh");
		refresh.setToolTipText("Recapture the target, auto-recording ~3 seconds of its animation for the scrubber - trigger an animation right after clicking to catch it");
		refresh.addActionListener(e -> callbacks.refreshSnapshot());

		JButton pose = new JButton("Pose");
		pose.setToolTipText("NPC snapshots only: pose the cache mesh at every exact frame of an animation ID and scrub - nothing needs to play in game");
		pose.addActionListener(e ->
		{
			String input = javax.swing.JOptionPane.showInputDialog(this, "Animation ID:");
			if (input == null)
			{
				return;
			}
			try
			{
				int id = Integer.parseInt(input.trim());
				if (id >= 0)
				{
					callbacks.poseAnimation(id);
				}
			}
			catch (NumberFormatException ignored)
			{
			}
		});

		JButton export = new JButton("Export");
		export.setToolTipText("Write emitters.json + definitions.json + folders.json");
		export.addActionListener(e -> callbacks.exportBundle());

		ParticleDevUi.styleCombo(modeSelector);
		ParticleDevUi.styleCombo(definitionCombo);
		ParticleDevUi.styleCombo(wornItemsCombo);
		ParticleDevUi.styleCombo(textureCombo);
		ParticleDevUi.styleCombo(shapeCombo);
		ParticleDevUi.styleCombo(flipbookModeCombo);
		ParticleDevUi.styleList(definitionList);
		definitionList.setFixedCellHeight(28);

		ParticleDevUi.styleToolbarButton(refresh);
		ParticleDevUi.styleToolbarButton(pose);
		ParticleDevUi.styleToolbarButton(export);
		ParticleDevUi.styleToolbarButton(duplicateButton);
		ParticleDevUi.styleToolbarButton(deleteButton);
		ParticleDevUi.styleToolbarButton(addWornItemButton);
		colorButton.setPreferredSize(new Dimension(56, ParticleDevUi.FIELD_HEIGHT));
		endColorButton.setPreferredSize(new Dimension(56, ParticleDevUi.FIELD_HEIGHT));

		definitionCombo.setRenderer(new DefaultListCellRenderer()
		{
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index,
				boolean isSelected, boolean cellHasFocus)
			{
				super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				ParticleDevUi.applyListRendererFont(this);
				String id = value instanceof String ? (String) value : "";
				if (id.isEmpty())
				{
					setText("Select definition");
				}
				else
				{
					setText(ParticleIds.displayName(id));
				}
				return this;
			}
		});

		definitionList.setCellRenderer(new DefaultListCellRenderer()
		{
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index,
				boolean isSelected, boolean cellHasFocus)
			{
				super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				ParticleDevUi.applyListRendererFont(this);
				String id = value instanceof String ? (String) value : "";
				setText(id.isEmpty() ? "" : ParticleIds.displayName(id));
				setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
				return this;
			}
		});

		JPanel actionRow = new JPanel(new GridLayout(1, 3, 6, 0));
		actionRow.add(refresh);
		actionRow.add(pose);
		actionRow.add(export);

		JPanel top = new JPanel(new GridLayout(0, 1, 0, 6));
		ParticleDevUi.themePanel(top);
		top.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
		top.add(modeSelector);
		top.add(actionRow);

		JButton loadProjectile = new JButton("Load");
		loadProjectile.setToolTipText("Create or open the emitter profile for the typed projectile ID, or the selected in-scene row");
		loadProjectile.addActionListener(e -> loadSelectedProjectile());
		projectileIdField.setToolTipText("Projectile ID; leave blank to use the selected in-scene row");
		projectileIdField.setColumns(6);
		ParticleDevUi.sizeField(projectileIdField);
		projectileAddPanel.add(new JLabel("ID "), BorderLayout.WEST);
		projectileAddPanel.add(projectileIdField, BorderLayout.CENTER);
		projectileAddPanel.add(loadProjectile, BorderLayout.EAST);

		JButton loadObject = new JButton("Load");
		loadObject.setToolTipText("Capture the typed object ID, or the selected nearby object, into the viewer for vertex picking");
		loadObject.addActionListener(e -> loadSelectedObject());
		objectIdField.setToolTipText("Object ID; leave blank to use the selected nearby object");
		objectIdField.setColumns(6);
		ParticleDevUi.sizeField(objectIdField);
		ParticleDevUi.sizeField(objectTypeFilterCombo);
		objectTypeFilterCombo.setToolTipText("Filter the in-scene object list by scenery type");
		JPanel objectIdGroup = new JPanel(new BorderLayout(4, 0));
		objectIdGroup.setOpaque(false);
		objectIdGroup.add(new JLabel("ID "), BorderLayout.WEST);
		objectIdGroup.add(objectIdField, BorderLayout.CENTER);
		JPanel objectLoadActions = new JPanel(new BorderLayout(4, 0));
		objectLoadActions.setOpaque(false);
		objectLoadActions.add(objectTypeFilterCombo, BorderLayout.CENTER);
		objectLoadActions.add(loadObject, BorderLayout.EAST);
		objectLoadPanel.add(objectIdGroup, BorderLayout.WEST);
		objectLoadPanel.add(objectLoadActions, BorderLayout.CENTER);

		JButton loadNpc = new JButton("Load");
		loadNpc.setToolTipText("Capture the typed NPC ID, or the selected nearby NPC, into the viewer for vertex picking");
		loadNpc.addActionListener(e -> loadSelectedNpc());
		npcIdField.setToolTipText("NPC ID; leave blank to use the selected in-scene NPC");
		npcIdField.setColumns(6);
		ParticleDevUi.sizeField(npcIdField);
		npcLoadPanel.add(new JLabel("ID "), BorderLayout.WEST);
		npcLoadPanel.add(npcIdField, BorderLayout.CENTER);
		npcLoadPanel.add(loadNpc, BorderLayout.EAST);

		JButton addGraphic = new JButton("Add");
		addGraphic.setToolTipText("Create a point-based profile for the typed spot anim ID, or the selected seen graphic");
		addGraphic.addActionListener(e -> addGraphicProfile());
		JButton loadGraphic = new JButton("Load");
		loadGraphic.setToolTipText("Capture a live instance of the graphic into the viewer to pick emitter vertices - the graphic must be active somewhere in view");
		loadGraphic.addActionListener(e -> loadSelectedGraphic());
		graphicIdField.setToolTipText("Spot anim / graphics ID; leave blank to use the selected in-scene row");
		graphicIdField.setColumns(6);
		ParticleDevUi.sizeField(graphicIdField);
		JPanel graphicButtons = new JPanel(new GridLayout(1, 0, 4, 0));
		graphicButtons.add(addGraphic);
		graphicButtons.add(loadGraphic);
		graphicAddPanel.add(new JLabel("ID "), BorderLayout.WEST);
		graphicAddPanel.add(graphicIdField, BorderLayout.CENTER);
		graphicAddPanel.add(graphicButtons, BorderLayout.EAST);

		JButton addWeather = new JButton("Create");
		addWeather.setToolTipText("Create a weather profile for the typed area name");
		addWeather.addActionListener(e -> addWeatherProfile());
		weatherAreaCombo.setToolTipText("Scene area from areas.json");
		ParticleDevUi.sizeField(weatherAreaCombo);
		weatherAddPanel.add(new JLabel("Area "), BorderLayout.WEST);
		weatherAddPanel.add(weatherAreaCombo, BorderLayout.CENTER);
		weatherAddPanel.add(addWeather, BorderLayout.EAST);
		refreshWeatherAreaChoices();

		JPanel emittersTab = new JPanel(new BorderLayout(0, 6));
		emittersTab.add(top, BorderLayout.NORTH);

		JScrollPane rowListScroll = new JScrollPane(rowList);
		rowListScroll.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		rowListScroll.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);
		rowListScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
		rowListScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		rowListScroll.setMinimumSize(new Dimension(0, 60));
		rowListScroll.setPreferredSize(new Dimension(0, 180));

		JPanel rowListNorth = new JPanel();
		rowListNorth.setLayout(new BoxLayout(rowListNorth, BoxLayout.Y_AXIS));
		rowListNorth.setOpaque(false);
		rowListNorth.add(rowListFilterBar);

		JPanel rowListColumn = new JPanel(new BorderLayout(0, 4));
		ParticleDevUi.themePanel(rowListColumn);
		rowListColumn.add(rowListNorth, BorderLayout.NORTH);
		rowListColumn.add(rowListScroll, BorderLayout.CENTER);

		JPanel editorColumn = new JPanel(new BorderLayout(0, 0));
		ParticleDevUi.themePanel(editorColumn);
		editorColumn.add(addPanelHolder, BorderLayout.NORTH);
		editorColumn.add(buildEmitterEditor(), BorderLayout.CENTER);
		editorColumn.setMinimumSize(new Dimension(0, 220));

		JSplitPane emittersSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, rowListColumn, editorColumn);
		emittersSplit.setResizeWeight(0.42);
		emittersSplit.setDividerLocation(220);
		emittersSplit.setContinuousLayout(true);
		emittersSplit.setOneTouchExpandable(true);
		emittersTab.add(emittersSplit, BorderLayout.CENTER);

		definitionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		definitionList.addListSelectionListener(e ->
		{
			if (e.getValueIsAdjusting())
			{
				return;
			}
			String id = definitionList.getSelectedValue();
			selectedDefinitionId = id;
			refreshDefinitionEditor();
		});

		JScrollPane definitionListScroll = new JScrollPane(definitionList);
		definitionListScroll.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		definitionListScroll.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);
		definitionListScroll.setPreferredSize(new Dimension(0, 140));
		JSplitPane definitionsSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
			definitionListScroll, buildDefinitionEditor());
		definitionsSplit.setResizeWeight(0.3);
		definitionsSplit.setDividerLocation(160);
		definitionsSplit.setContinuousLayout(true);
		definitionsSplit.setOneTouchExpandable(true);

		JPanel definitionsTab = new JPanel(new BorderLayout());
		ParticleDevUi.themePanel(definitionsTab);
		definitionsTab.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		definitionsTab.add(definitionsSplit, BorderLayout.CENTER);

		particleCards.add(emittersTab, "emitters");
		particleCards.add(definitionsTab, "definitions");
		JPanel particlesRoot = new JPanel(new BorderLayout(0, 4));
		ParticleDevUi.themePanel(particlesRoot);
		particlesRoot.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		particlesRoot.add(ParticleDevUi.segmentBar(emittersNavBtn, definitionsNavBtn), BorderLayout.NORTH);
		particlesRoot.add(particleCards, BorderLayout.CENTER);
		showParticleSubview(false);

		JTabbedPane mainTabs = new JTabbedPane(JTabbedPane.TOP);
		ParticleDevUi.styleMainTabs(mainTabs);
		mainTabs.setBackground(ColorScheme.DARK_GRAY_COLOR);
		mainTabs.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		mainTabs.addTab("Particles", particlesRoot);
		JPanel lightsTab = new JPanel(new BorderLayout());
		ParticleDevUi.themePanel(lightsTab);
		lightsTab.setBorder(BorderFactory.createEmptyBorder(24, 8, 8, 8));
		JLabel lightsSoon = ParticleDevUi.mutedLabel("Coming soon!");
		lightsSoon.setHorizontalAlignment(SwingConstants.CENTER);
		lightsTab.add(lightsSoon, BorderLayout.NORTH);
		mainTabs.addTab("Lights", lightsTab);

		JPanel left = new JPanel(new BorderLayout(0, 6));
		ParticleDevUi.themePanel(left);
		left.setBorder(BorderFactory.createEmptyBorder(8, 10, 10, 8));
		left.add(mainTabs, BorderLayout.CENTER);
		left.setPreferredSize(new Dimension(ParticleDevUi.LEFT_WIDTH, 0));

		scrubSlider.addChangeListener(e -> applyScrub());
		scrubPanel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
		scrubPanel.add(scrubSlider, BorderLayout.CENTER);
		scrubPanel.add(scrubLabel, BorderLayout.EAST);
		scrubPanel.setVisible(false);

		viewportLayer = new JLayeredPane();
		viewportLayer.setLayout(null);
		viewportLayer.add(viewport, JLayeredPane.DEFAULT_LAYER);
		viewportLayer.add(viewportToolbar, JLayeredPane.PALETTE_LAYER);
		viewportLayer.addComponentListener(new ComponentAdapter()
		{
			@Override
			public void componentResized(ComponentEvent e)
			{
				layoutViewportOverlay();
			}
		});
		installViewportKeyBindings();

		JPanel right = new JPanel(new BorderLayout());
		right.add(viewportLayer, BorderLayout.CENTER);
		right.add(scrubPanel, BorderLayout.SOUTH);

		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
		split.setDividerLocation(ParticleDevUi.LEFT_WIDTH);
		ParticleDevUi.themeFrame(split);
		setContentPane(split);

		setSize(1190, 780);
		setLocationRelativeTo(null);
		addComponentListener(new ComponentAdapter()
		{
			@Override
			public void componentShown(ComponentEvent e)
			{
				layoutViewportOverlay();
			}
		});
	}

	private void layoutViewportOverlay()
	{
		if (viewportLayer == null)
		{
			return;
		}
		Dimension size = viewportLayer.getSize();
		if (size.width <= 0 || size.height <= 0)
		{
			return;
		}
		viewport.setBounds(0, 0, size.width, size.height);
		Dimension toolbar = viewportToolbar.getPreferredSize();
		int w = Math.min(toolbar.width, size.width - 16);
		viewportToolbar.setBounds(8, 8, w, toolbar.height);
	}

	private void loadSelectedProjectile()
	{
		int id = parseIdOrSelection(projectileIdField);
		if (id < 0)
		{
			return;
		}
		lastAutoLoadedCaptureId = id;
		projectileIdField.setText(String.valueOf(id));
		pendingSelection = callbacks.createProjectileProfile(id);
		if (snapshot != null)
		{
			rebuildRows();
		}
	}

	private JPanel buildViewportToolbar()
	{
		JPanel bar = new JPanel(new BorderLayout(8, 0));
		bar.setOpaque(true);
		bar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		bar.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR, 2),
			BorderFactory.createEmptyBorder(6, 8, 6, 8)));

		ParticleDevUi.styleSegmentButton(viewportPlaceBtn, true);
		ParticleDevUi.styleSegmentButton(viewportRemoveBtn, false);
		JPanel modeBar = ParticleDevUi.segmentBar(viewportPlaceBtn, viewportRemoveBtn);
		modeBar.setOpaque(false);
		viewportModeBar = modeBar;
		viewportPlaceBtn.setToolTipText("Click or shift+drag to add emitters (P)");
		viewportRemoveBtn.setToolTipText("Click or ctrl+drag to remove emitters (D)");
		viewportPlaceBtn.addActionListener(e -> setViewportInteractionMode(ViewportPanel.InteractionMode.PLACE));
		viewportRemoveBtn.addActionListener(e -> setViewportInteractionMode(ViewportPanel.InteractionMode.REMOVE));
		updateViewportModeUi();

		ParticleDevUi.styleSegmentButton(viewportColorsBtn, false);
		viewportColorsBtn.setToolTipText("Filled face colours (C)");
		viewportColorsBtn.addActionListener(e -> toggleViewportFaceColors());

		ParticleDevUi.styleSegmentButton(viewportWireBtn, true);
		viewportWireBtn.setToolTipText("Triangle wireframe (W)");
		viewportWireBtn.addActionListener(e -> toggleViewportWireframe());

		ParticleDevUi.styleSegmentButton(viewportVertsBtn, true);
		viewportVertsBtn.setToolTipText("Vertex dots (V)");
		viewportVertsBtn.addActionListener(e -> toggleViewportVertices());

		JButton topBtn = new JButton("Top");
		JButton sideBtn = new JButton("Side");
		JButton behindBtn = new JButton("Behind");
		topBtn.setToolTipText("Look straight down (1)");
		sideBtn.setToolTipText("Side view (2)");
		behindBtn.setToolTipText("Behind view (3)");
		ParticleDevUi.styleToolbarButton(topBtn);
		ParticleDevUi.styleToolbarButton(sideBtn);
		ParticleDevUi.styleToolbarButton(behindBtn);
		topBtn.addActionListener(e -> viewport.setCameraPreset(ViewportPanel.CameraPreset.TOP));
		sideBtn.addActionListener(e -> viewport.setCameraPreset(ViewportPanel.CameraPreset.SIDE));
		behindBtn.addActionListener(e -> viewport.setCameraPreset(ViewportPanel.CameraPreset.BEHIND));

		JPanel cameraBar = new JPanel(new GridLayout(1, 0, 4, 0));
		cameraBar.setOpaque(false);
		cameraBar.add(topBtn);
		cameraBar.add(sideBtn);
		cameraBar.add(behindBtn);

		JPanel centerBar = new JPanel(new GridLayout(1, 0, 4, 0));
		centerBar.setOpaque(false);
		centerBar.add(viewportColorsBtn);
		centerBar.add(viewportWireBtn);
		centerBar.add(viewportVertsBtn);

		bar.add(modeBar, BorderLayout.WEST);
		bar.add(centerBar, BorderLayout.CENTER);
		bar.add(cameraBar, BorderLayout.EAST);
		bar.setPreferredSize(new Dimension(480, ParticleDevUi.FIELD_HEIGHT + 12));
		return bar;
	}

	private void toggleViewportFaceColors()
	{
		boolean on = !viewport.isShowFaceColors();
		viewport.setShowFaceColors(on);
		ParticleDevUi.styleSegmentButton(viewportColorsBtn, on);
	}

	private void toggleViewportWireframe()
	{
		boolean on = !viewport.isShowWireframe();
		viewport.setShowWireframe(on);
		ParticleDevUi.styleSegmentButton(viewportWireBtn, on);
	}

	private void toggleViewportVertices()
	{
		boolean on = !viewport.isShowVertices();
		viewport.setShowVertices(on);
		ParticleDevUi.styleSegmentButton(viewportVertsBtn, on);
	}

	private void setViewportInteractionMode(ViewportPanel.InteractionMode mode)
	{
		viewport.setInteractionMode(mode);
		updateViewportModeUi();
	}

	private void updateViewportModeUi()
	{
		ViewportPanel.InteractionMode mode = viewport.getInteractionMode();
		ParticleDevUi.styleSegmentButton(viewportPlaceBtn, mode == ViewportPanel.InteractionMode.PLACE);
		ParticleDevUi.styleSegmentButton(viewportRemoveBtn, mode == ViewportPanel.InteractionMode.REMOVE);
	}

	private void installViewportKeyBindings()
	{
		InputMap input = viewport.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
		ActionMap actions = viewport.getActionMap();

		input.put(KeyStroke.getKeyStroke("P"), "viewportPlace");
		actions.put("viewportPlace", new AbstractAction()
		{
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e)
			{
				setViewportInteractionMode(ViewportPanel.InteractionMode.PLACE);
			}
		});

		input.put(KeyStroke.getKeyStroke("D"), "viewportRemove");
		actions.put("viewportRemove", new AbstractAction()
		{
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e)
			{
				setViewportInteractionMode(ViewportPanel.InteractionMode.REMOVE);
			}
		});

		input.put(KeyStroke.getKeyStroke("C"), "viewportColors");
		actions.put("viewportColors", new AbstractAction()
		{
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e)
			{
				toggleViewportFaceColors();
			}
		});

		input.put(KeyStroke.getKeyStroke("W"), "viewportWire");
		actions.put("viewportWire", new AbstractAction()
		{
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e)
			{
				toggleViewportWireframe();
			}
		});

		input.put(KeyStroke.getKeyStroke("V"), "viewportVerts");
		actions.put("viewportVerts", new AbstractAction()
		{
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e)
			{
				toggleViewportVertices();
			}
		});

		input.put(KeyStroke.getKeyStroke("1"), "cameraTop");
		actions.put("cameraTop", new AbstractAction()
		{
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e)
			{
				viewport.setCameraPreset(ViewportPanel.CameraPreset.TOP);
			}
		});

		input.put(KeyStroke.getKeyStroke("2"), "cameraSide");
		actions.put("cameraSide", new AbstractAction()
		{
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e)
			{
				viewport.setCameraPreset(ViewportPanel.CameraPreset.SIDE);
			}
		});

		input.put(KeyStroke.getKeyStroke("3"), "cameraBehind");
		actions.put("cameraBehind", new AbstractAction()
		{
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e)
			{
				viewport.setCameraPreset(ViewportPanel.CameraPreset.BEHIND);
			}
		});
	}

	private void loadSelectedObject()
	{
		int id = parseIdOrSelection(objectIdField);
		if (id < 0)
		{
			return;
		}
		lastAutoLoadedCaptureId = id;
		callbacks.loadObject(id);
		viewport.setPieceFilter(-1);
		appliedPieceIndex = -1;
	}

	private void loadSelectedNpc()
	{
		int id = parseIdOrSelection(npcIdField);
		if (id < 0)
		{
			return;
		}
		lastAutoLoadedCaptureId = id;
		callbacks.loadNpc(id);
		viewport.setPieceFilter(-1);
		appliedPieceIndex = -1;
	}

	private void addGraphicProfile()
	{
		int id = parseIdOrSelection(graphicIdField);
		if (id < 0)
		{
			return;
		}
		pendingSelection = callbacks.createGraphicProfile(id);
		callbacks.refreshSnapshot();
	}

	private void addWeatherProfile()
	{
		Object selected = weatherAreaCombo.getSelectedItem();
		String area = selected instanceof String ? ((String) selected).trim() : "";
		if (area.isEmpty())
		{
			return;
		}
		pendingSelection = callbacks.createWeatherProfile(List.of(area));
		savedEmitterList = true;
		updateEmitterListFilterUi();
		callbacks.refreshSnapshot();
	}

	private void refreshWeatherAreaChoices()
	{
		List<String> names = callbacks.areaNames();
		String[] items = names.toArray(new String[0]);
		weatherAreaCombo.setModel(new DefaultComboBoxModel<>(items));
		weatherAreaPicker.setModel(new DefaultComboBoxModel<>(items));
	}

	private void selectWeatherAreaInPicker(@Nullable List<String> areas)
	{
		if (areas == null || areas.isEmpty())
		{
			return;
		}
		String preferred = areas.get(0);
		ComboBoxModel<String> model = weatherAreaPicker.getModel();
		for (int i = 0; i < model.getSize(); i++)
		{
			if (preferred.equals(model.getElementAt(i)))
			{
				weatherAreaPicker.setSelectedIndex(i);
				return;
			}
		}
	}

	private void addWeatherAreaFromPicker()
	{
		Object selected = weatherAreaPicker.getSelectedItem();
		if (!(selected instanceof String))
		{
			return;
		}
		String area = ((String) selected).trim();
		if (area.isEmpty())
		{
			return;
		}
		for (int i = 0; i < weatherAreaListModel.size(); i++)
		{
			if (area.equals(weatherAreaListModel.getElementAt(i)))
			{
				return;
			}
		}
		weatherAreaListModel.addElement(area);
		saveEmitterConfig();
	}

	private void removeSelectedWeatherArea()
	{
		int index = weatherAreaList.getSelectedIndex();
		if (index < 0)
		{
			return;
		}
		weatherAreaListModel.remove(index);
		saveEmitterConfig();
	}

	private void loadSelectedGraphic()
	{
		int id = parseIdOrSelection(graphicIdField);
		if (id < 0)
		{
			return;
		}
		lastAutoLoadedCaptureId = id;
		// Short-lived gfx are usually gone before Load can be clicked; the
		// plugin arms a capture and calls notifyGraphicSnapshot when the mesh lands
		callbacks.loadGraphic(id);
		viewport.setPieceFilter(-1);
		appliedPieceIndex = -1;
	}

	/**
	 * Show a message in the editor hint line. EDT only.
	 */
	void showHint(String text)
	{
		// Hints are shown via tooltips / in-game context; no header line in the panel.
	}

	/**
	 * Graphic capture landed in the viewer; refresh the list without leaving
	 * Graphics mode. EDT only.
	 */
	void notifyGraphicSnapshot()
	{
		if (snapshot != null)
		{
			rebuildRows();
		}
	}

	/**
	 * Hand a finished animation recording to the scrubber: per-sample vertex
	 * positions over the current snapshot's topology, plus each sample's
	 * animation frame (-1 when the source exposes none). EDT only.
	 */
	void setRecording(float[][] xs, float[][] ys, float[][] zs, int[] frames)
	{
		// Recordings arrive without re-pushing a snapshot, so ignore any
		// whose topology doesn't match what the viewer currently shows
		if (snapshot == null || xs.length == 0 || xs[0].length != snapshot.getVertexCount())
		{
			return;
		}
		recordingXs = xs;
		recordingYs = ys;
		recordingZs = zs;
		recordingFrames = frames;
		scrubSlider.setMaximum(frames.length - 1);
		scrubSlider.setValue(0);
		scrubPanel.setVisible(true);
		scrubPanel.revalidate();
		applyScrub();
	}

	private void applyScrub()
	{
		if (recordingXs == null)
		{
			return;
		}
		int i = Math.min(scrubSlider.getValue(), recordingXs.length - 1);
		viewport.setPositionOverride(recordingXs[i], recordingYs[i], recordingZs[i]);
		scrubLabel.setText(recordingFrames[i] >= 0
			? "frame " + recordingFrames[i] + "  (" + (i + 1) + "/" + recordingFrames.length + ")"
			: "sample " + (i + 1) + "/" + recordingFrames.length);
	}

	private void clearRecording()
	{
		recordingXs = null;
		recordingYs = null;
		recordingZs = null;
		recordingFrames = null;
		scrubPanel.setVisible(false);
		viewport.setPositionOverride(null, null, null);
	}

	/**
	 * The typed ID, falling back to the selected capture row; clears the
	 * field on success.
	 */
	private int parseIdOrSelection(JTextField field)
	{
		int id = -1;
		try
		{
			id = Integer.parseInt(field.getText().trim());
		}
		catch (NumberFormatException ignored)
		{
		}
		if (id < 0)
		{
			id = selectedCaptureId;
		}
		if (id >= 0)
		{
			field.setText("");
		}
		return id;
	}

	private void showParticleSubview(boolean definitions)
	{
		particleCardLayout.show(particleCards, definitions ? "definitions" : "emitters");
		ParticleDevUi.styleSegmentButton(emittersNavBtn, !definitions);
		ParticleDevUi.styleSegmentButton(definitionsNavBtn, definitions);
	}

	private void applyRowSelection(Row row)
	{
		if (!savedEmitterList && row.captureId >= 0 && row.profileKey == null)
		{
			if (projectileMode)
			{
				applyInSceneCaptureRow(row.captureId, projectileIdField, () ->
					pendingSelection = callbacks.createProjectileProfile(row.captureId));
				return;
			}
			if (objectMode)
			{
				applyInSceneCaptureRow(row.captureId, objectIdField, () -> callbacks.loadObject(row.captureId));
				return;
			}
			if (npcMode)
			{
				applyInSceneCaptureRow(row.captureId, npcIdField, () -> callbacks.loadNpc(row.captureId));
				return;
			}
			if (graphicMode)
			{
				applyInSceneCaptureRow(row.captureId, graphicIdField, () -> callbacks.loadGraphic(row.captureId));
				return;
			}
		}

		boolean pieceChanged = row.pieceIndex != appliedPieceIndex;
		boolean profileChanged = !Objects.equals(row.profileKey, selectedProfileKey)
			|| row.captureId != selectedCaptureId;
		appliedPieceIndex = row.pieceIndex;
		selectedProfileKey = row.profileKey;
		selectedCaptureId = row.captureId;
		if (pieceChanged)
		{
			viewport.setPieceFilter(row.pieceIndex);
		}
		if (pieceChanged || profileChanged)
		{
			refreshStyleEditor();
			callbacks.selectionChanged();
		}
	}

	private void applyInSceneCaptureRow(int captureId, JTextField idField, Runnable loadTarget)
	{
		selectedCaptureId = captureId;
		selectedProfileKey = null;
		appliedPieceIndex = -1;
		viewport.setPieceFilter(-1);
		if (captureId != lastAutoLoadedCaptureId)
		{
			lastAutoLoadedCaptureId = captureId;
			idField.setText(String.valueOf(captureId));
			loadTarget.run();
			if (projectileMode && pendingSelection != null && snapshot != null)
			{
				rebuildRows();
				return;
			}
		}
		refreshStyleEditor();
		callbacks.selectionChanged();
	}

	private boolean meshTargetMode()
	{
		return objectMode || npcMode || graphicMode;
	}

	private void installRowListContextMenu()
	{
		JPopupMenu popup = new JPopupMenu();
		MouseAdapter listener = new MouseAdapter()
		{
			private void showPopup(MouseEvent e)
			{
				if (!e.isPopupTrigger() || snapshot == null)
				{
					return;
				}
				if (e.getComponent() == viewport)
				{
					if (!meshTargetMode() || savedEmitterList)
					{
						return;
					}
					popup.removeAll();
					addPieceViewMenu(popup);
					if (popup.getComponentCount() > 0)
					{
						popup.show(viewport, e.getX(), e.getY());
					}
					return;
				}
				if (!meshTargetMode() || savedEmitterList)
				{
					return;
				}
				int index = rowList.locationToIndex(e.getPoint());
				if (index < 0 || index >= rows.size())
				{
					return;
				}
				if (!rowList.getCellBounds(index, index).contains(e.getPoint()))
				{
					return;
				}
				rowList.setSelectedIndex(index);
				popup.removeAll();
				addPieceViewMenu(popup);
				if (popup.getComponentCount() > 0)
				{
					popup.show(rowList, e.getX(), e.getY());
				}
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				showPopup(e);
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				showPopup(e);
			}
		};
		rowList.addMouseListener(listener);
		viewport.addMouseListener(listener);
	}

	private void addPieceViewMenu(JPopupMenu popup)
	{
		if (snapshot == null || snapshot.getPieces().isEmpty())
		{
			return;
		}
		JMenuItem all = new JMenuItem("View all pieces");
		all.addActionListener(e ->
		{
			appliedPieceIndex = -1;
			viewport.setPieceFilter(-1);
		});
		popup.add(all);
		if (snapshot.getPieces().size() <= 1)
		{
			return;
		}
		popup.addSeparator();
		for (int i = 0; i < snapshot.getPieces().size(); i++)
		{
			ModelSnapshot.Piece piece = snapshot.getPieces().get(i);
			int pieceIndex = i;
			JMenuItem item = new JMenuItem("Piece " + (i + 1)
				+ " (" + piece.getVertices().length + "v, " + piece.getFaces().length + "f)");
			item.addActionListener(e ->
			{
				appliedPieceIndex = pieceIndex;
				viewport.setPieceFilter(pieceIndex);
			});
			popup.add(item);
		}
	}

	private void setEmitterListSaved(boolean saved)
	{
		if (savedEmitterList == saved)
		{
			return;
		}
		savedEmitterList = saved;
		updateEmitterListFilterUi();
		syncObjectTypeFilterRow();
		rebuildRows();
	}

	private void updateEmitterListFilterUi()
	{
		ParticleDevUi.styleSegmentButton(inSceneFilterBtn, !savedEmitterList);
		ParticleDevUi.styleSegmentButton(savedFilterBtn, savedEmitterList);
	}

	private void syncEmitterListFilterBar()
	{
		boolean player = inModelMode();
		rowListFilterBar.setVisible(!player && !weatherMode);
		if (player && savedEmitterList)
		{
			savedEmitterList = false;
			updateEmitterListFilterUi();
		}
		syncObjectTypeFilterRow();
	}

	private void syncObjectTypeFilterRow()
	{
		objectTypeFilterCombo.setVisible(objectMode && !savedEmitterList);
	}

	@Nullable
	private ObjectTypeFilter selectedObjectTypeFilter()
	{
		Object selected = objectTypeFilterCombo.getSelectedItem();
		return selected instanceof ObjectTypeFilter ? (ObjectTypeFilter) selected : ObjectTypeFilter.GAME_OBJECTS;
	}

	private static String formatObjectSighting(ObjectSighting sighting)
	{
		String count = sighting.instanceCount > 1 ? sighting.instanceCount + "x, " : "";
		return sighting.name + " · " + sighting.id + " (" + count + sighting.distanceTiles + "t)";
	}

	boolean inModelMode()
	{
		return !projectileMode && !objectMode && !npcMode && !graphicMode && !weatherMode;
	}

	boolean isWeatherMode()
	{
		return weatherMode;
	}

	private JPanel buildEmitterEditor()
	{
		offsetXSpinner.setToolTipText("Fixed emit offset sideways (local X), rotates with facing.");
		offsetYSpinner.setToolTipText("Fixed emit offset forward/back (local Y), rotates with facing.");
		offsetZSpinner.setToolTipText("Fixed emit offset up (local Z, positive), rotates with facing.");
		definitionCombo.setToolTipText("Reusable particle definition shared by id. Edit the look on the Definitions tab.");

		weatherAreaPicker.setToolTipText("Pick a scene area from areas.json");
		weatherPptSpinner.setToolTipText("Target alive particles per tile across the area footprint");
		weatherDensitySpinner.setToolTipText("Extra weather density multiplier on top of the global Particles density setting");
		weatherAreaAddBtn.addActionListener(e -> addWeatherAreaFromPicker());
		weatherAreaRemoveBtn.addActionListener(e -> removeSelectedWeatherArea());
		weatherAreaList.addListSelectionListener(e -> weatherAreaRemoveBtn.setEnabled(weatherAreaList.getSelectedIndex() >= 0));

		JPanel sections = new JPanel();
		sections.setLayout(new BoxLayout(sections, BoxLayout.Y_AXIS));
		sections.setOpaque(false);
		weatherAreaPickerRow = ParticleDevUi.formRow("Add area", weatherAreaPicker);
		JPanel weatherAreaButtons = new JPanel(new GridLayout(1, 0, 4, 0));
		weatherAreaButtons.add(weatherAreaAddBtn);
		weatherAreaButtons.add(weatherAreaRemoveBtn);
		JScrollPane weatherAreaScroll = new JScrollPane(weatherAreaList);
		weatherAreaScroll.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		weatherAreaScroll.setPreferredSize(new Dimension(0, 72));
		weatherAreaListRow = ParticleDevUi.section("Areas", false, this::revalidateStyleScroll,
			weatherAreaPickerRow,
			weatherAreaButtons,
			weatherAreaScroll).root;
		weatherPptRow = ParticleDevUi.formRow("Per tile", weatherPptSpinner);
		weatherDensityRow = ParticleDevUi.formRow("Density scale", weatherDensitySpinner);
		syncWeatherPlacementVisible(false);
		refreshWeatherAreaChoices();
		sections.add(ParticleDevUi.section("Placement", true, this::revalidateStyleScroll,
			ParticleDevUi.vectorRow("Offset", offsetXSpinner, offsetYSpinner, offsetZSpinner),
			weatherAreaListRow,
			weatherPptRow,
			weatherDensityRow,
			ParticleDevUi.formRow("Depth bias", depthBiasSpinner),
			ParticleDevUi.formRow("Emit scale %", emitScaleSpinner),
			ParticleDevUi.formRow("Feather", featherSpinner),
			ParticleDevUi.formRow("Interpolate", interpolationSpinner)).root);
		sections.add(ParticleDevUi.section("Gating", false, this::revalidateStyleScroll,
			ParticleDevUi.formRow("Item filter", itemFilterField),
			ParticleDevUi.formRow("Anim filter", animFilterField),
			ParticleDevUi.formRow("Anim frames", animFramesField)).root);

		definitionCombo.addActionListener(e ->
		{
			if (populating || selectedProfileKey == null)
			{
				return;
			}
			String defId = (String) definitionCombo.getSelectedItem();
			if (defId != null && !defId.isEmpty())
			{
				callbacks.setProfileDefinition(selectedProfileKey, defId);
				refreshStyleEditor();
			}
		});
		emitScaleSpinner.addChangeListener(e -> saveEmitterConfig());
		emitScaleSpinner.setToolTipText("Scale the emitter's vertices about their centre before emitting. 100 = unchanged; below draws the emit points together, above pushes them apart (like scaling vertices in a mesh editor). Vertex-ring targets only.");
		featherSpinner.addChangeListener(e -> saveEmitterConfig());
		featherSpinner.setToolTipText("Emit along a line chained through the emitter vertices, smoothed over this many neighbors (0 = off). Higher values cut across jagged notches; the marker overlay draws the resulting line.");
		interpolationSpinner.addChangeListener(e -> saveEmitterConfig());
		interpolationSpinner.setToolTipText("Insert this many extra emitter points between each pair of mesh-adjacent picked vertices - 1 roughly doubles the emitter count. Densifies emission on low-poly meshes.");
		depthBiasSpinner.addChangeListener(e -> saveEmitterConfig());
		depthBiasSpinner.setToolTipText("Nudge particles this many units toward the camera at render time, so garments that render over their neighbors (a cape over a skirt) don't swallow them. ~16-32 fixes clipping; too much floats particles in front of things.");
		offsetXSpinner.addChangeListener(e -> saveEmitterConfig());
		offsetYSpinner.addChangeListener(e -> saveEmitterConfig());
		offsetZSpinner.addChangeListener(e -> saveEmitterConfig());

		DocumentListener saveEmitterOnEdit = new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				saveEmitterConfig();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				saveEmitterConfig();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				saveEmitterConfig();
			}
		};
		itemFilterField.getDocument().addDocumentListener(saveEmitterOnEdit);
		weatherPptSpinner.addChangeListener(e -> saveEmitterConfig());
		weatherDensitySpinner.addChangeListener(e -> saveEmitterConfig());
		itemFilterField.setToolTipText("Comma-separated item IDs; only emit while one is worn. Blank = any variant of this mesh.");
		animFilterField.getDocument().addDocumentListener(saveEmitterOnEdit);
		animFilterField.setToolTipText("Comma-separated animation IDs; only emit while one is playing (action or pose). Blank = always. The overlay stats line shows the last action animation id.");
		animFramesField.getDocument().addDocumentListener(saveEmitterOnEdit);
		animFramesField.setToolTipText("Frame windows within the action animation, e.g. \"9-13, 15-19\" or \"7\". Blank = all frames.");

		addWornItemButton.setToolTipText("Append the selected worn item's ID to the filter");
		addWornItemButton.addActionListener(e ->
		{
			String selected = (String) wornItemsCombo.getSelectedItem();
			if (selected == null || selectedProfile() == null)
			{
				return;
			}
			String id = selected.split(" ")[0];
			String current = itemFilterField.getText().trim();
			itemFilterField.setText(current.isEmpty() ? id : current + ", " + id);
			saveEmitterConfig();
		});

		duplicateButton.setToolTipText("Clone this profile so another item variant gets its own particles");
		duplicateButton.addActionListener(e ->
		{
			if (selectedProfileKey == null)
			{
				return;
			}
			String newKey = callbacks.duplicateProfile(selectedProfileKey);
			if (newKey != null)
			{
				pendingSelection = newKey;
				callbacks.refreshSnapshot();
			}
		});

		deleteButton.setToolTipText("Delete this profile entirely");
		deleteButton.addActionListener(e ->
		{
			if (selectedProfileKey == null)
			{
				return;
			}
			EmitterProfile profile = selectedProfile();
			String name = profile != null ? profile.getName() : selectedProfileKey;
			if (javax.swing.JOptionPane.showConfirmDialog(this,
				"Delete profile '" + name + "'?", "Delete profile",
				javax.swing.JOptionPane.YES_NO_OPTION) == javax.swing.JOptionPane.YES_OPTION)
			{
				callbacks.deleteProfile(selectedProfileKey);
			}
		});

		JPanel wornRow = new JPanel(new BorderLayout(6, 0));
		wornRow.setOpaque(false);
		wornRow.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		ParticleDevUi.sizeField(wornItemsCombo);
		wornRow.add(wornItemsCombo, BorderLayout.CENTER);
		wornRow.add(addWornItemButton, BorderLayout.EAST);

		JPanel buttons = new JPanel(new GridLayout(1, 2, 8, 0));
		buttons.setOpaque(false);
		buttons.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		buttons.add(duplicateButton);
		buttons.add(deleteButton);

		JPanel footer = new JPanel();
		footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
		footer.setOpaque(false);
		footer.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, ColorScheme.MEDIUM_GRAY_COLOR));
		footer.add(wornRow);
		footer.add(buttons);

		JComponent header = ParticleDevUi.formRow("Particle def", definitionCombo);
		header.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));

		emitterScrollPane = ParticleDevUi.scroll(sections);
		JPanel editor = new JPanel(new BorderLayout(0, 8));
		ParticleDevUi.themePanel(editor);
		editor.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		editor.add(header, BorderLayout.NORTH);
		editor.add(emitterScrollPane, BorderLayout.CENTER);
		editor.add(footer, BorderLayout.SOUTH);
		setEditorEnabled(false);
		return editor;
	}

	private JPanel buildDefinitionEditor()
	{
		endColorRow = ParticleDevUi.formRow("End color", endColorButton);
		fadeStartRow = ParticleDevUi.formRow("Fade start %", fadeStartSpinner);
		flipbookColsRow = ParticleDevUi.formRow("Flipbook cols", flipbookColsSpinner);
		flipbookRowsRow = ParticleDevUi.formRow("Flipbook rows", flipbookRowsSpinner);
		flipbookModeRow = ParticleDevUi.formRow("Flipbook mode", flipbookModeCombo);

		textureCombo.setRenderer(new DefaultListCellRenderer()
		{
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index,
				boolean isSelected, boolean cellHasFocus)
			{
				super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				String file = value instanceof String ? (String) value : "";
				setText(file == null || file.isEmpty() ? "Default (soft glow)" : file);
				return this;
			}
		});
		textureCombo.setToolTipText("Particle texture from the textures folder, or the default soft glow disc.");

		JPanel sections = new JPanel();
		sections.setLayout(new BoxLayout(sections, BoxLayout.Y_AXIS));
		sections.setOpaque(false);
		sections.add(ParticleDevUi.section("Appearance", true, this::revalidateStyleScroll,
			ParticleDevUi.formRow("Color", colorButton),
			ParticleDevUi.formRow("Color over life", colorFadeCheck),
			endColorRow,
			fadeStartRow,
			ParticleDevUi.formRow("Uniform colour", uniformColorCheck),
			ParticleDevUi.formRow("Scene ambient", useEnvironmentLightCheck),
			colorPreviewPanel,
			ParticleDevUi.formRow("Shape", shapeCombo),
			ParticleDevUi.formRow("Texture", textureCombo),
			flipbookColsRow,
			flipbookRowsRow,
			flipbookModeRow,
			ParticleDevUi.formRow("Opacity", alphaSpinner),
			ParticleDevUi.formRow("Size", sizeSpinner),
			ParticleDevUi.formRow("Size jitter", sizeJitterSpinner),
			ParticleDevUi.formRow("Scale start %", scaleStartSpinner),
			ParticleDevUi.formRow("Scale end %", scaleEndSpinner)).root);
		sections.add(ParticleDevUi.section("Emission", true, this::revalidateStyleScroll,
			ParticleDevUi.formRow("Rate /s", rateSpinner),
			ParticleDevUi.formRow("Trail / tile", trailSpinner),
			ParticleDevUi.formRow("Lifetime ms", lifetimeSpinner),
			ParticleDevUi.formRow("Moving life %", moveLifetimeSpinner),
			ParticleDevUi.formRow("Jitter", jitterSpinner)).root);
		sections.add(ParticleDevUi.section("Motion & forces", true, this::revalidateStyleScroll,
			ParticleDevUi.formRow("Rise", riseSpinner),
			ParticleDevUi.formRow("Spread", spreadSpinner),
			ParticleDevUi.formRow("Gravity", gravitySpinner),
			ParticleDevUi.vectorRow("Wind", windXSpinner, windYSpinner, windZSpinner),
			ParticleDevUi.formRow("Drag", dragSpinner),
			ParticleDevUi.formRow("Vortex", vortexSpinner),
			ParticleDevUi.formRow("Stretch %", stretchSpinner),
			ParticleDevUi.formRow("Stretch ramp", stretchRampSpinner),
			ParticleDevUi.formRow("Rotation /s", rotationSpeedSpinner)).root);

		colorPreviewPanel.setPreferredSize(new Dimension(0, 28));
		colorPreviewPanel.setMinimumSize(new Dimension(0, 28));
		colorPreviewPanel.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		uniformColorCheck.setToolTipText("On: smooth colour gradient over life (use Color over life). Off: each particle picks a random colour between Color and End color at spawn.");
		useEnvironmentLightCheck.setToolTipText("Tint particles by scene ambient light instead of self-lit glow. Good for snow/ash; off for fire and magic.");
		rotationSpeedSpinner.setToolTipText("Billboard spin speed in radians per second (0 = none).");
		scaleStartSpinner.setToolTipText("Particle size at spawn as % of base Size.");
		scaleEndSpinner.setToolTipText("Particle size at death as % of base Size.");

		colorButton.addActionListener(e ->
		{
			ParticleDefinition def = selectedDefinition();
			if (def == null)
			{
				return;
			}
			Color initial = new Color(def.getColor(), true);
			Color picked = JColorChooser.showDialog(this, "Particle color", initial);
			if (picked != null)
			{
				colorButton.setBackground(picked);
				colorPreviewPanel.repaint();
				saveDefinitionStyle();
			}
		});
		colorFadeCheck.setToolTipText("Fade each particle from Color to the End color over its life. Off = a single constant Color.");
		colorFadeCheck.addActionListener(e ->
		{
			syncColorFadeEnabled();
			colorPreviewPanel.repaint();
			saveDefinitionStyle();
		});
		uniformColorCheck.addActionListener(e ->
		{
			colorPreviewPanel.repaint();
			saveDefinitionStyle();
		});
		useEnvironmentLightCheck.addActionListener(e -> saveDefinitionStyle());
		fadeStartSpinner.setToolTipText("Life percent at which the fade to the End color begins. 0 fades across the whole life; higher holds the start Color longer, then shifts late (e.g. embers reddening near the end).");
		fadeStartSpinner.addChangeListener(e -> saveDefinitionStyle());
		endColorButton.setToolTipText("Colour a particle reaches by the end of its life when Color over life is on. The fade is a hue shift; opacity still follows the life envelope.");
		endColorButton.addActionListener(e ->
		{
			ParticleDefinition def = selectedDefinition();
			if (def == null)
			{
				return;
			}
			Color initial = new Color(def.getColorEnd(), true);
			Color picked = JColorChooser.showDialog(this, "End color", initial);
			if (picked != null)
			{
				endColorButton.setBackground(picked);
				colorPreviewPanel.repaint();
				saveDefinitionStyle();
			}
		});
		shapeCombo.addActionListener(e -> saveDefinitionStyle());
		shapeCombo.setToolTipText("Particle silhouette: a soft glow (Default) or a carved shape - ring, star, teardrop, cross. Reads best at larger sizes.");
		textureCombo.addActionListener(e -> saveDefinitionStyle());
		flipbookColsSpinner.addChangeListener(e -> saveDefinitionStyle());
		flipbookRowsSpinner.addChangeListener(e -> saveDefinitionStyle());
		flipbookModeCombo.addActionListener(e ->
		{
			syncFlipbookEnabled();
			saveDefinitionStyle();
		});
		flipbookModeCombo.setToolTipText("Flipbook animation over a texture grid: Order advances frames over life, Random picks one frame at spawn.");
		alphaSpinner.addChangeListener(e ->
		{
			colorPreviewPanel.repaint();
			saveDefinitionStyle();
		});
		sizeSpinner.addChangeListener(e -> saveDefinitionStyle());
		sizeJitterSpinner.addChangeListener(e -> saveDefinitionStyle());
		sizeJitterSpinner.setToolTipText("Random size spread per particle, in the same units as Size. 0 = every particle the base size; higher varies each between Size-jitter and Size+jitter (floored at 2). Realized as three size variants: low, base, high.");
		rateSpinner.addChangeListener(e -> saveDefinitionStyle());
		trailSpinner.addChangeListener(e -> saveDefinitionStyle());
		trailSpinner.setToolTipText("Particles per tile of emitter movement, spread evenly along the path - for weapon and projectile trails. Combine with Rate 0 for a pure ribbon.");
		lifetimeSpinner.addChangeListener(e -> saveDefinitionStyle());
		moveLifetimeSpinner.addChangeListener(e -> saveDefinitionStyle());
		moveLifetimeSpinner.setToolTipText("Percent of normal lifetime while the wearer walks or runs. Lower keeps plumes tight at speed instead of smearing a tile behind.");
		riseSpinner.addChangeListener(e -> saveDefinitionStyle());
		riseSpinner.setToolTipText("Vertical drift speed. Positive rises (embers, glow); negative sinks (dripping blood, falling dust).");
		spreadSpinner.addChangeListener(e -> saveDefinitionStyle());
		gravitySpinner.addChangeListener(e -> saveDefinitionStyle());
		gravitySpinner.setToolTipText("Downward acceleration. 0 = constant velocity; higher makes particles fall and speed up - the signature of a blood or water drip. Pair with Rise near 0.");
		windXSpinner.addChangeListener(e -> saveDefinitionStyle());
		windYSpinner.addChangeListener(e -> saveDefinitionStyle());
		windZSpinner.addChangeListener(e -> saveDefinitionStyle());
		dragSpinner.addChangeListener(e -> saveDefinitionStyle());
		vortexSpinner.addChangeListener(e -> saveDefinitionStyle());
		stretchSpinner.addChangeListener(e -> saveDefinitionStyle());
		stretchRampSpinner.addChangeListener(e -> saveDefinitionStyle());
		jitterSpinner.addChangeListener(e -> saveDefinitionStyle());
		rotationSpeedSpinner.addChangeListener(e -> saveDefinitionStyle());
		scaleStartSpinner.addChangeListener(e -> saveDefinitionStyle());
		scaleEndSpinner.addChangeListener(e -> saveDefinitionStyle());

		particleScrollPane = ParticleDevUi.scroll(sections);
		JPanel editor = new JPanel(new BorderLayout(0, 8));
		ParticleDevUi.themePanel(editor);
		editor.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		editor.add(particleScrollPane, BorderLayout.CENTER);
		setDefinitionEditorEnabled(false);
		return editor;
	}

	void refreshDefinitions(Map<String, ParticleDefinition> definitions)
	{
		String keep = selectedDefinitionId;
		definitionListModel.clear();
		java.util.List<String> ids = new java.util.ArrayList<>(definitions.keySet());
		ids.sort(java.util.Comparator.comparing(ParticleIds::displayName));
		for (String id : ids)
		{
			definitionListModel.addElement(id);
		}
		if (keep != null)
		{
			definitionList.setSelectedValue(keep, true);
		}
		if (definitionList.getSelectedValue() == null && !ids.isEmpty())
		{
			definitionList.setSelectedIndex(0);
		}
	}

	@Nullable
	private EmitterProfile selectedProfile()
	{
		return selectedProfileKey == null ? null : callbacks.profile(selectedProfileKey);
	}

	/**
	 * Reload the style editor from the selected profile, e.g. after a vertex
	 * toggle created one. Must be called on the Swing EDT.
	 */
	void refreshStyleEditor()
	{
		EmitterProfile profile = selectedProfile();
		if (profile == null)
		{
			setEditorEnabled(false);
			syncWeatherPlacementVisible(false);
			return;
		}

		populating = true;
		refreshDefinitionCombo();
		emitScaleSpinner.setValue(profile.getEmitScale());
		featherSpinner.setValue(profile.getFeatherStrength());
		interpolationSpinner.setValue(profile.getInterpolation());
		depthBiasSpinner.setValue(profile.getDepthBias());
		offsetXSpinner.setValue(profile.getOffsetX());
		offsetYSpinner.setValue(profile.getOffsetY());
		offsetZSpinner.setValue(profile.getOffsetZ());
		itemFilterField.setText(joinIds(profile.getItemIds()));
		animFilterField.setText(joinIds(profile.getAnimationIds()));
		animFramesField.setText(profile.getAnimFrames());
		populating = false;
		setEditorEnabled(true);

		boolean projectile = profile.isProjectileTarget();
		boolean graphic = profile.isGraphicTarget();
		boolean object = profile.isObjectTarget();
		boolean npc = profile.isNpcTarget();
		boolean weather = profile.isWeatherTarget();
		boolean showWeatherPlacement = weatherMode && weather;
		boolean actorTarget = EmitterProfile.TARGET_PLAYER.equals(profile.getTargetType()) || npc;
		if (showWeatherPlacement)
		{
			weatherAreaListModel.clear();
			if (profile.getWeatherAreas() != null)
			{
				for (String area : profile.getWeatherAreas())
				{
					weatherAreaListModel.addElement(area);
				}
			}
			weatherPptSpinner.setValue((double) profile.getWeatherParticlesPerTile());
			weatherDensitySpinner.setValue((double) profile.getWeatherDensityScale());
			selectWeatherAreaInPicker(profile.getWeatherAreas());
		}
		syncWeatherPlacementVisible(showWeatherPlacement);
		boolean showMeshPlacement = !weather;
		offsetXSpinner.getParent().setVisible(showMeshPlacement);
		featherSpinner.setEnabled(!projectile && !weather);
		interpolationSpinner.setEnabled(!projectile && !weather);
		offsetXSpinner.setEnabled(!projectile && !weather);
		offsetYSpinner.setEnabled(!projectile && !weather);
		offsetZSpinner.setEnabled(!projectile && !weather);
		animFilterField.setEnabled(actorTarget);
		animFramesField.setEnabled(actorTarget || graphic);
		emitScaleSpinner.setEnabled(!projectile && !graphic && !weather);
		itemFilterField.setEnabled(!object && !npc && !graphic && !weather);
		wornItemsCombo.setEnabled(!object && !npc && !graphic && !weather);
		addWornItemButton.setEnabled(!object && !npc && !graphic && !weather);
		depthBiasSpinner.setEnabled(!weather);
	}

	@Nullable
	private ParticleDefinition selectedDefinition()
	{
		return selectedDefinitionId == null ? null : callbacks.definition(selectedDefinitionId);
	}

	void refreshDefinitionEditor()
	{
		ParticleDefinition def = selectedDefinition();
		if (def == null)
		{
			setDefinitionEditorEnabled(false);
			return;
		}

		populatingDefinition = true;
		refreshTextureCombo();
		Color color = new Color(def.getColor(), true);
		colorButton.setBackground(new Color(color.getRed(), color.getGreen(), color.getBlue()));
		Color endColor = new Color(def.getColorEnd(), true);
		endColorButton.setBackground(new Color(endColor.getRed(), endColor.getGreen(), endColor.getBlue()));
		colorFadeCheck.setSelected(def.isColorFade());
		fadeStartSpinner.setValue(def.getColorFadeStart());
		shapeCombo.setSelectedItem(def.getShape() == null ? Shape.DEFAULT : def.getShape());
		String texture = def.getTextureFile();
		textureCombo.setSelectedItem(texture == null || texture.isEmpty() ? TEXTURE_DEFAULT : texture);
		boolean flipbookOn = def.getFlipbookColumns() > 0 && def.getFlipbookRows() > 0
			&& def.getFlipbookMode() != null && !def.getFlipbookMode().isEmpty();
		flipbookColsSpinner.setValue(Math.max(1, flipbookOn ? def.getFlipbookColumns() : 1));
		flipbookRowsSpinner.setValue(Math.max(1, flipbookOn ? def.getFlipbookRows() : 1));
		String mode = def.getFlipbookMode();
		if (mode != null && "random".equalsIgnoreCase(mode))
		{
			flipbookModeCombo.setSelectedItem("Random");
		}
		else if (mode != null && "order".equalsIgnoreCase(mode))
		{
			flipbookModeCombo.setSelectedItem("Order");
		}
		else
		{
			flipbookModeCombo.setSelectedItem("None");
		}
		alphaSpinner.setValue(color.getAlpha());
		sizeSpinner.setValue(def.getSize());
		sizeJitterSpinner.setValue(def.getSizeJitter());
		rateSpinner.setValue(def.getParticlesPerSecond());
		trailSpinner.setValue(def.getTrailDensity());
		lifetimeSpinner.setValue(def.getLifetimeMs());
		moveLifetimeSpinner.setValue(def.getMovementLifetime());
		riseSpinner.setValue(def.getRiseSpeed());
		spreadSpinner.setValue(def.getSpreadSpeed());
		gravitySpinner.setValue(def.getGravity());
		windXSpinner.setValue(def.getWindX());
		windYSpinner.setValue(def.getWindY());
		windZSpinner.setValue(def.getWindZ());
		dragSpinner.setValue(def.getDrag());
		vortexSpinner.setValue(def.getVortex());
		stretchSpinner.setValue(def.getStretch());
		stretchRampSpinner.setValue(def.getStretchRamp());
		jitterSpinner.setValue(def.getSpawnJitter());
		uniformColorCheck.setSelected(def.isUniformColorVariation());
		useEnvironmentLightCheck.setSelected(def.isUseEnvironmentLight());
		rotationSpeedSpinner.setValue((double) def.getRotationSpeed());
		scaleStartSpinner.setValue(def.getScaleStartPercent());
		scaleEndSpinner.setValue(def.getScaleEndPercent());
		populatingDefinition = false;

		setDefinitionEditorEnabled(true);
		syncColorFadeEnabled();
		syncFlipbookEnabled();
		colorPreviewPanel.repaint();
	}

	private void refreshDefinitionCombo()
	{
		String selected = definitionCombo.getSelectedItem() instanceof String
			? (String) definitionCombo.getSelectedItem()
			: "";
		EmitterProfile profile = selectedProfile();
		if (profile != null && profile.getDefinitionId() != null)
		{
			selected = profile.getDefinitionId();
		}
		DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
		for (String id : callbacks.particleDefinitionIds())
		{
			model.addElement(id);
		}
		definitionCombo.setModel(model);
		if (model.getIndexOf(selected) >= 0)
		{
			definitionCombo.setSelectedItem(selected);
		}
		else if (model.getSize() > 0)
		{
			definitionCombo.setSelectedIndex(0);
		}
	}

	private void refreshTextureCombo()
	{
		String selected = textureCombo.getSelectedItem() instanceof String
			? (String) textureCombo.getSelectedItem()
			: TEXTURE_DEFAULT;
		DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
		model.addElement(TEXTURE_DEFAULT);
		for (String file : callbacks.particleTextureFiles())
		{
			if (file != null && !file.isEmpty())
			{
				model.addElement(file);
			}
		}
		textureCombo.setModel(model);
		if (model.getIndexOf(selected) >= 0)
		{
			textureCombo.setSelectedItem(selected);
		}
		else
		{
			textureCombo.setSelectedItem(TEXTURE_DEFAULT);
		}
	}

	private void syncFlipbookEnabled()
	{
		boolean on = flipbookModeCombo.isEnabled()
			&& !"None".equals(flipbookModeCombo.getSelectedItem());
		flipbookColsSpinner.setEnabled(on);
		flipbookRowsSpinner.setEnabled(on);
		if (flipbookColsRow != null)
		{
			flipbookColsRow.setVisible(on);
			flipbookRowsRow.setVisible(on);
			revalidateStyleScroll();
		}
	}

	private void revalidateStyleScroll()
	{
		revalidateScroll(emitterScrollPane);
		revalidateScroll(particleScrollPane);
	}

	private static void revalidateScroll(@Nullable JScrollPane scroll)
	{
		if (scroll == null)
		{
			return;
		}
		scroll.getViewport().revalidate();
		scroll.revalidate();
		scroll.repaint();
	}

	private void syncWeatherPlacementVisible(boolean visible)
	{
		if (weatherAreaListRow != null)
		{
			weatherAreaListRow.setVisible(visible);
		}
		if (weatherPptRow != null)
		{
			weatherPptRow.setVisible(visible);
		}
		if (weatherDensityRow != null)
		{
			weatherDensityRow.setVisible(visible);
		}
		if (!visible)
		{
			revalidateStyleScroll();
		}
	}

	/**
	 * The end colour and its fade-start only matter while colour-over-life is
	 * enabled, so they follow the checkbox.
	 */
	private void syncColorFadeEnabled()
	{
		boolean on = colorFadeCheck.isEnabled() && colorFadeCheck.isSelected();
		endColorButton.setEnabled(on);
		fadeStartSpinner.setEnabled(on);
		colorPreviewPanel.setVisible(on);
		// Fold the end colour and fade-start rows away entirely while off, so
		// the common single-colour profile shows two fewer rows
		if (endColorRow != null)
		{
			endColorRow.setVisible(on);
			fadeStartRow.setVisible(on);
			revalidateStyleScroll();
		}
	}

	private static String joinIds(Set<Integer> ids)
	{
		StringBuilder sb = new StringBuilder();
		for (int id : ids)
		{
			if (sb.length() > 0)
			{
				sb.append(", ");
			}
			sb.append(id);
		}
		return sb.toString();
	}

	private void setEditorEnabled(boolean enabled)
	{
		definitionCombo.setEnabled(enabled);
		emitScaleSpinner.setEnabled(enabled);
		featherSpinner.setEnabled(enabled);
		interpolationSpinner.setEnabled(enabled);
		depthBiasSpinner.setEnabled(enabled);
		offsetXSpinner.setEnabled(enabled);
		offsetYSpinner.setEnabled(enabled);
		offsetZSpinner.setEnabled(enabled);
		itemFilterField.setEnabled(enabled);
		animFilterField.setEnabled(enabled);
		animFramesField.setEnabled(enabled);
		wornItemsCombo.setEnabled(enabled);
		addWornItemButton.setEnabled(enabled);
		duplicateButton.setEnabled(enabled);
		deleteButton.setEnabled(enabled);
	}

	private void setDefinitionEditorEnabled(boolean enabled)
	{
		colorButton.setEnabled(enabled);
		colorFadeCheck.setEnabled(enabled);
		endColorButton.setEnabled(enabled);
		fadeStartSpinner.setEnabled(enabled);
		shapeCombo.setEnabled(enabled);
		textureCombo.setEnabled(enabled);
		flipbookColsSpinner.setEnabled(enabled);
		flipbookRowsSpinner.setEnabled(enabled);
		flipbookModeCombo.setEnabled(enabled);
		alphaSpinner.setEnabled(enabled);
		sizeSpinner.setEnabled(enabled);
		sizeJitterSpinner.setEnabled(enabled);
		rateSpinner.setEnabled(enabled);
		trailSpinner.setEnabled(enabled);
		lifetimeSpinner.setEnabled(enabled);
		moveLifetimeSpinner.setEnabled(enabled);
		riseSpinner.setEnabled(enabled);
		spreadSpinner.setEnabled(enabled);
		gravitySpinner.setEnabled(enabled);
		windXSpinner.setEnabled(enabled);
		windYSpinner.setEnabled(enabled);
		windZSpinner.setEnabled(enabled);
		dragSpinner.setEnabled(enabled);
		vortexSpinner.setEnabled(enabled);
		stretchSpinner.setEnabled(enabled);
		stretchRampSpinner.setEnabled(enabled);
		jitterSpinner.setEnabled(enabled);
		uniformColorCheck.setEnabled(enabled);
		useEnvironmentLightCheck.setEnabled(enabled);
		rotationSpeedSpinner.setEnabled(enabled);
		scaleStartSpinner.setEnabled(enabled);
		scaleEndSpinner.setEnabled(enabled);
	}

	private void saveEmitterConfig()
	{
		if (populating || selectedProfileKey == null)
		{
			return;
		}
		EmitterProfile profile = selectedProfile();
		if (profile == null)
		{
			return;
		}
		String defId = definitionCombo.getSelectedItem() instanceof String
			? (String) definitionCombo.getSelectedItem()
			: null;
		if (defId != null && !defId.isEmpty())
		{
			profile.setDefinitionId(defId);
		}
		profile.setEmitScale((int) emitScaleSpinner.getValue());
		profile.setFeatherStrength((int) featherSpinner.getValue());
		profile.setInterpolation((int) interpolationSpinner.getValue());
		profile.setDepthBias((int) depthBiasSpinner.getValue());
		profile.setOffsetX((int) offsetXSpinner.getValue());
		profile.setOffsetY((int) offsetYSpinner.getValue());
		profile.setOffsetZ((int) offsetZSpinner.getValue());
		if (profile.isWeatherTarget())
		{
			List<String> areas = new ArrayList<>();
			for (int i = 0; i < weatherAreaListModel.size(); i++)
			{
				areas.add(weatherAreaListModel.getElementAt(i));
			}
			profile.setWeatherAreas(areas);
			profile.setWeatherParticlesPerTile(((Number) weatherPptSpinner.getValue()).floatValue());
			profile.setWeatherDensityScale(((Number) weatherDensitySpinner.getValue()).floatValue());
		}
		else
		{
			profile.setWeatherAreas(new ArrayList<>());
		}
		profile.setItemIds(parseIds(itemFilterField.getText()));
		profile.setAnimationIds(parseIds(animFilterField.getText()));
		profile.setAnimFrames(animFramesField.getText().trim());
		callbacks.saveProfile(selectedProfileKey, profile);
	}

	private void saveDefinitionStyle()
	{
		if (populatingDefinition || selectedDefinitionId == null)
		{
			return;
		}
		ParticleDefinition def = selectedDefinition();
		if (def == null)
		{
			def = new ParticleDefinition();
		}

		Color rgb = colorButton.getBackground();
		int alpha = (int) alphaSpinner.getValue() & 0xff;
		int argb = alpha << 24 | (rgb.getRed() << 16) | (rgb.getGreen() << 8) | rgb.getBlue();
		def.setColor(argb);
		Color endRgb = endColorButton.getBackground();
		int endArgb = alpha << 24 | (endRgb.getRed() << 16) | (endRgb.getGreen() << 8) | endRgb.getBlue();
		def.setColorEnd(endArgb);
		def.setColorFade(colorFadeCheck.isSelected());
		def.setColorFadeStart((int) fadeStartSpinner.getValue());
		def.setShape((Shape) shapeCombo.getSelectedItem());
		String texture = textureCombo.getSelectedItem() instanceof String
			? (String) textureCombo.getSelectedItem()
			: TEXTURE_DEFAULT;
		def.setTextureFile(texture == null ? "" : texture);
		String flipMode = (String) flipbookModeCombo.getSelectedItem();
		if ("Order".equals(flipMode))
		{
			def.setFlipbookColumns((int) flipbookColsSpinner.getValue());
			def.setFlipbookRows((int) flipbookRowsSpinner.getValue());
			def.setFlipbookMode("order");
		}
		else if ("Random".equals(flipMode))
		{
			def.setFlipbookColumns((int) flipbookColsSpinner.getValue());
			def.setFlipbookRows((int) flipbookRowsSpinner.getValue());
			def.setFlipbookMode("random");
		}
		else
		{
			def.setFlipbookColumns(0);
			def.setFlipbookRows(0);
			def.setFlipbookMode(null);
		}
		def.setSize((int) sizeSpinner.getValue());
		def.setSizeJitter((int) sizeJitterSpinner.getValue());
		def.setParticlesPerSecond((int) rateSpinner.getValue());
		def.setTrailDensity((int) trailSpinner.getValue());
		def.setLifetimeMs((int) lifetimeSpinner.getValue());
		def.setMovementLifetime((int) moveLifetimeSpinner.getValue());
		def.setRiseSpeed((int) riseSpinner.getValue());
		def.setSpreadSpeed((int) spreadSpinner.getValue());
		def.setGravity((int) gravitySpinner.getValue());
		def.setWindX((int) windXSpinner.getValue());
		def.setWindY((int) windYSpinner.getValue());
		def.setWindZ((int) windZSpinner.getValue());
		def.setDrag((int) dragSpinner.getValue());
		def.setVortex((int) vortexSpinner.getValue());
		def.setStretch((int) stretchSpinner.getValue());
		def.setStretchRamp((int) stretchRampSpinner.getValue());
		def.setSpawnJitter((int) jitterSpinner.getValue());
		def.setRotationSpeed(((Number) rotationSpeedSpinner.getValue()).floatValue());
		def.setUseEnvironmentLight(useEnvironmentLightCheck.isSelected());
		def.setUniformColorVariation(uniformColorCheck.isSelected());
		def.setScaleStartPercent((int) scaleStartSpinner.getValue());
		def.setScaleEndPercent((int) scaleEndSpinner.getValue());
		callbacks.saveDefinition(selectedDefinitionId, def);
	}

	private static Set<Integer> parseIds(String text)
	{
		Set<Integer> ids = new HashSet<>();
		for (String token : text.split("[,\\s]+"))
		{
			try
			{
				if (!token.isEmpty())
				{
					ids.add(Integer.parseInt(token));
				}
			}
			catch (NumberFormatException ignored)
			{
			}
		}
		return ids;
	}

	/**
	 * Swap in a new snapshot and fresh profile data. Must be called on the
	 * Swing EDT.
	 */
	void setSnapshot(ModelSnapshot snapshot, Set<Integer> selectedVertices,
		Map<String, List<ProfileEntry>> profilesBySignature, List<String> wornItems,
		List<ProfileEntry> projectileEntries, List<int[]> recentProjectiles,
		List<ProfileEntry> objectEntries, List<ObjectSighting> objectSightings,
		List<ProfileEntry> npcEntries, List<ObjectSighting> npcSightings,
		List<ProfileEntry> graphicEntries, List<GraphicSighting> recentGraphics,
		List<ProfileEntry> weatherEntries)
	{
		this.profilesBySignature = profilesBySignature;
		this.projectileEntries = projectileEntries;
		this.recentProjectiles = recentProjectiles;
		this.objectEntries = objectEntries;
		this.objectSightings = objectSightings;
		this.npcEntries = npcEntries;
		this.npcSightings = npcSightings;
		this.graphicEntries = graphicEntries;
		this.recentGraphics = recentGraphics;
		this.weatherEntries = weatherEntries;
		appliedPieceIndex = Integer.MIN_VALUE;
		clearRecording();
		if (weatherMode)
		{
			this.snapshot = null;
			viewport.setSnapshot(null);
			viewport.setEmptyMessage("Weather emitters have no mesh preview.");
			viewport.setSelectedVertices(Set.of());
			wornItemsCombo.setModel(new DefaultComboBoxModel<>(new String[0]));
		}
		else
		{
			this.snapshot = snapshot;
			viewport.setEmptyMessage(null);
			viewport.setSnapshot(snapshot);
			viewport.setSelectedVertices(selectedVertices);
			wornItemsCombo.setModel(new DefaultComboBoxModel<>(wornItems.toArray(new String[0])));
		}

		// Editing a profile from the sidebar lands in the matching target mode.
		if (pendingSelection != null)
		{
			int mode = 0;
			for (ProfileEntry entry : projectileEntries)
			{
				if (entry.key.equals(pendingSelection))
				{
					mode = 1;
					break;
				}
			}
			if (mode == 0)
			{
				for (ProfileEntry entry : objectEntries)
				{
					if (entry.key.equals(pendingSelection))
					{
						mode = 2;
						break;
					}
				}
			}
			if (mode == 0)
			{
				for (ProfileEntry entry : npcEntries)
				{
					if (entry.key.equals(pendingSelection))
					{
						mode = 3;
						break;
					}
				}
			}
			if (mode == 0)
			{
				for (ProfileEntry entry : graphicEntries)
				{
					if (entry.key.equals(pendingSelection))
					{
						mode = 4;
						break;
					}
				}
			}
			if (mode == 0)
			{
				for (ProfileEntry entry : weatherEntries)
				{
					if (entry.key.equals(pendingSelection))
					{
						mode = 5;
						break;
					}
				}
			}
			setMode(mode);
		}
		rebuildRows();
	}

	private void setMode(int index)
	{
		updatingMode = true;
		modeSelector.setSelectedIndex(index);
		applyMode(index);
		updatingMode = false;
	}

	private void applyMode(int index)
	{
		projectileMode = index == 1;
		objectMode = index == 2;
		npcMode = index == 3;
		graphicMode = index == 4;
		weatherMode = index == 5;
		lastAutoLoadedCaptureId = -1;
		addPanelHolder.removeAll();
		if (projectileMode)
		{
			addPanelHolder.add(projectileAddPanel, BorderLayout.CENTER);
		}
		else if (objectMode)
		{
			addPanelHolder.add(objectLoadPanel, BorderLayout.CENTER);
		}
		else if (npcMode)
		{
			addPanelHolder.add(npcLoadPanel, BorderLayout.CENTER);
		}
		else if (graphicMode)
		{
			addPanelHolder.add(graphicAddPanel, BorderLayout.CENTER);
		}
		else if (weatherMode)
		{
			addPanelHolder.add(weatherAddPanel, BorderLayout.CENTER);
			viewport.setEmptyMessage("Weather emitters have no mesh preview.");
			refreshWeatherAreaChoices();
			selectWeatherAreaInPicker(List.of("WINTERTODT_ARENA"));
		}
		else
		{
			viewport.setEmptyMessage(null);
		}
		viewportToolbar.setVisible(true);
		viewportModeBar.setVisible(!weatherMode);
		scrubPanel.setVisible(!weatherMode && recordingXs != null);
		addPanelHolder.revalidate();
		addPanelHolder.repaint();
		syncEmitterListFilterBar();
		if (weatherMode)
		{
			savedEmitterList = true;
			updateEmitterListFilterUi();
			rebuildRows();
		}
		refreshStyleEditor();
	}

	/**
	 * Rebuild the rows with fresh profile data, e.g. after a vertex click
	 * created a profile. Keeps the camera. EDT only.
	 */
	void refreshRows(Map<String, List<ProfileEntry>> profilesBySignature, List<ProfileEntry> projectileEntries,
		List<ProfileEntry> objectEntries, List<ProfileEntry> npcEntries, List<ProfileEntry> graphicEntries,
		List<ProfileEntry> weatherEntries)
	{
		this.profilesBySignature = profilesBySignature;
		this.projectileEntries = projectileEntries;
		this.objectEntries = objectEntries;
		this.npcEntries = npcEntries;
		this.graphicEntries = graphicEntries;
		this.weatherEntries = weatherEntries;
		if (weatherMode || snapshot != null)
		{
			rebuildRows();
		}
	}

	private void rebuildRows()
	{
		rebuildingRows = true;
		rowListModel.clear();
		rows.clear();
		int selectRow = 0;
		syncEmitterListFilterBar();

		if (inModelMode())
		{
			rowListModel.addElement("All pieces (" + snapshot.getVertexCount() + "v)");
			rows.add(new Row(-1, null));

			int i = 0;
			for (ModelSnapshot.Piece piece : snapshot.getPieces())
			{
				String counts = " (" + piece.getVertices().length + "v, " + piece.getFaces().length + "f)";
				List<ProfileEntry> entries = profilesBySignature.get(piece.getSignature());
				if (entries == null || entries.isEmpty())
				{
					rowListModel.addElement("Piece " + (i + 1) + counts);
					rows.add(new Row(i, null));
				}
				else
				{
					for (ProfileEntry entry : entries)
					{
						rowListModel.addElement(entry.name + (entry.filtered ? " [item-gated]" : "") + counts);
						rows.add(new Row(i, entry.key));
						if (entry.key.equals(pendingSelection))
						{
							selectRow = rows.size() - 1;
						}
					}
				}
				i++;
			}
		}
		else if (projectileMode)
		{
			maybeSelectSavedEmitterTab();
			if (savedEmitterList)
			{
				for (ProfileEntry entry : projectileEntries)
				{
					rowListModel.addElement(entry.name + (entry.filtered ? " [item-gated]" : ""));
					rows.add(new Row(-1, entry.key));
					if (entry.key.equals(pendingSelection))
					{
						selectRow = rows.size() - 1;
					}
				}
				if (rows.isEmpty())
				{
					rowListModel.addElement("No saved projectile emitters");
					rows.add(new Row(-1, null));
				}
			}
			else
			{
				int keepCaptureId = selectedCaptureId >= 0 ? selectedCaptureId : lastAutoLoadedCaptureId;
				for (int[] recent : recentProjectiles)
				{
					rowListModel.addElement("seen: " + recent[0] + "  (" + recent[1] + "x, " + recent[2] + "s ago)");
					rows.add(new Row(-1, null, recent[0]));
					if (recent[0] == keepCaptureId)
					{
						selectRow = rows.size() - 1;
					}
				}
				if (rows.isEmpty())
				{
					rowListModel.addElement("No projectiles seen yet - fire something nearby, then Refresh");
					rows.add(new Row(-1, null));
				}
			}
		}
		else if (objectMode || npcMode)
		{
			maybeSelectSavedEmitterTab();
			List<ProfileEntry> entries = objectMode ? objectEntries : npcEntries;
			List<ObjectSighting> sightings = objectMode ? objectSightings : npcSightings;
			if (savedEmitterList)
			{
				for (ProfileEntry entry : entries)
				{
					rowListModel.addElement(entry.name);
					rows.add(new Row(-1, entry.key));
					if (entry.key.equals(pendingSelection))
					{
						selectRow = rows.size() - 1;
					}
				}
				if (rows.isEmpty())
				{
					rowListModel.addElement(objectMode
						? "No saved object emitters"
						: "No saved NPC emitters");
					rows.add(new Row(-1, null));
				}
			}
			else
			{
				ObjectTypeFilter typeFilter = objectMode ? selectedObjectTypeFilter() : null;
				int keepCaptureId = selectedCaptureId >= 0 ? selectedCaptureId : lastAutoLoadedCaptureId;
				int shown = 0;
				for (ObjectSighting sighting : sightings)
				{
					if (typeFilter != null && !typeFilter.matches(sighting.kind))
					{
						continue;
					}
					rowListModel.addElement(formatObjectSighting(sighting));
					rows.add(new Row(-1, null, sighting.id));
					if (sighting.id == keepCaptureId)
					{
						selectRow = rows.size() - 1;
					}
					shown++;
				}
				if (shown == 0)
				{
					rowListModel.addElement(objectMode
						? "No matching objects in scene - try another type or Refresh"
						: "No NPCs nearby - Refresh snapshot to rescan");
					rows.add(new Row(-1, null));
				}
			}
		}
		else if (graphicMode)
		{
			maybeSelectSavedEmitterTab();
			if (savedEmitterList)
			{
				for (ProfileEntry entry : graphicEntries)
				{
					rowListModel.addElement(entry.name);
					rows.add(new Row(-1, entry.key));
					if (entry.key.equals(pendingSelection))
					{
						selectRow = rows.size() - 1;
					}
				}
				if (rows.isEmpty())
				{
					rowListModel.addElement("No saved graphic emitters");
					rows.add(new Row(-1, null));
				}
			}
			else
			{
				int keepCaptureId = selectedCaptureId >= 0 ? selectedCaptureId : lastAutoLoadedCaptureId;
				for (GraphicSighting recent : recentGraphics)
				{
					String from = recent.source == null || recent.source.isEmpty() ? "" : " " + recent.source;
					rowListModel.addElement("seen: " + recent.id + from
						+ "  (" + recent.count + "x, " + recent.secondsAgo + "s ago)");
					rows.add(new Row(-1, null, recent.id));
					if (recent.id == keepCaptureId)
					{
						selectRow = rows.size() - 1;
					}
				}
				if (rows.isEmpty())
				{
					rowListModel.addElement("No graphics seen yet - cast something nearby, then Refresh");
					rows.add(new Row(-1, null));
				}
			}
		}
		else if (weatherMode)
		{
			for (ProfileEntry entry : weatherEntries)
			{
				rowListModel.addElement(entry.name);
				rows.add(new Row(-1, entry.key));
				if (entry.key.equals(pendingSelection))
				{
					selectRow = rows.size() - 1;
				}
			}
			if (rows.isEmpty())
			{
				rowListModel.addElement("No saved weather emitters");
				rows.add(new Row(-1, null));
			}
		}

		String pendingProfile = pendingSelection;
		pendingSelection = null;
		rebuildingRows = false;
		rowList.setSelectedIndex(selectRow);
		if (selectRow >= 0 && selectRow < rows.size())
		{
			applyRowSelection(rows.get(selectRow));
		}
		// In-scene rows are sightings only; keep the active profile for editing.
		if (!savedEmitterList && !inModelMode() && pendingProfile != null)
		{
			selectedProfileKey = pendingProfile;
			refreshStyleEditor();
			callbacks.selectionChanged();
		}
	}

	private boolean shouldStayOnInSceneTab()
	{
		return lastAutoLoadedCaptureId >= 0 || selectedCaptureId >= 0;
	}

	private void maybeSelectSavedEmitterTab()
	{
		if (pendingSelection == null || inModelMode())
		{
			return;
		}
		if (shouldStayOnInSceneTab())
		{
			return;
		}
		boolean saved = false;
		if (projectileMode)
		{
			for (ProfileEntry entry : projectileEntries)
			{
				if (pendingSelection.equals(entry.key))
				{
					saved = true;
					break;
				}
			}
		}
		else if (objectMode)
		{
			for (ProfileEntry entry : objectEntries)
			{
				if (pendingSelection.equals(entry.key))
				{
					saved = true;
					break;
				}
			}
		}
		else if (npcMode)
		{
			for (ProfileEntry entry : npcEntries)
			{
				if (pendingSelection.equals(entry.key))
				{
					saved = true;
					break;
				}
			}
		}
		else if (graphicMode)
		{
			for (ProfileEntry entry : graphicEntries)
			{
				if (pendingSelection.equals(entry.key))
				{
					saved = true;
					break;
				}
			}
		}
		if (saved)
		{
			savedEmitterList = true;
			updateEmitterListFilterUi();
		}
	}

	/**
	 * Select the row of this profile when the next snapshot loads,
	 * e.g. when editing a saved profile from the sidebar. EDT only.
	 */
	void selectProfileOnNextSnapshot(String profileKey)
	{
		pendingSelection = profileKey;
	}

	/**
	 * Update the highlighted emitter vertices. Must be called on the Swing EDT.
	 */
	void setSelectedVertices(Set<Integer> selectedVertices)
	{
		viewport.setSelectedVertices(selectedVertices);
	}
}
