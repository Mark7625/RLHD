package rs117.hd.utils.tooling.environment;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTree;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.components.colorpicker.ColorPickerManager;
import net.runelite.client.ui.components.colorpicker.RuneliteColorPicker;
import rs117.hd.HdPlugin;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.scene.areas.Area;
import rs117.hd.scene.environments.Environment;
import rs117.hd.utils.ResourcePath;
import rs117.hd.utils.tooling.props.ComponentData;
import rs117.hd.utils.tooling.props.PropertyComponentFactory;
import rs117.hd.utils.tooling.props.PropertyData;
import rs117.hd.utils.tooling.props.impl.SchemaBasedEnvironmentPropertyRegistry;
import rs117.hd.utils.tooling.props.impl.render.CollapsiblePanel;

@Slf4j
public class EnvironmentEditorPanel extends JPanel {

	// UI Components
	private final JPanel attributePanel = new JPanel();
	private final JTextField searchField;
	private final JTree environmentTree;
	public RuneliteColorPicker colorPicker;
	public final ColorPickerManager colorPickerManager;

	// Managers and context
	private final EnvironmentManager environmentManager;
	private final Client client;
	private final ClientThread clientThread;
	private final HdPlugin plugin;

	private Environment currentlySelectedEnvironment;
	private String lastSearchText = "";

	public EnvironmentEditorPanel(
		ClientThread clientThread,
		Client client,
		EnvironmentManager environmentManager,
		ColorPickerManager colorPickerManager,
		HdPlugin plugin
	) {
		this.clientThread = clientThread;
		this.client = client;
		this.environmentManager = environmentManager;
		this.colorPickerManager = colorPickerManager;
		this.plugin = plugin;
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setLayout(new BorderLayout());

		// Search field above tree
		searchField = new JTextField();
		searchField.setToolTipText("Type to search environments...");
		searchField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		searchField.getDocument().addDocumentListener(new DocumentListener() {
			@Override public void insertUpdate(DocumentEvent e) { filterTree(); }
			@Override public void removeUpdate(DocumentEvent e) { filterTree(); }
			@Override public void changedUpdate(DocumentEvent e) { filterTree(); }
		});

		JPanel searchPanel = new JPanel(new BorderLayout());
		searchPanel.add(searchField, BorderLayout.CENTER);
		searchPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

		environmentTree = new JTree(buildEnvironmentTree());
		environmentTree.setRootVisible(false);
		environmentTree.setShowsRootHandles(true);
		environmentTree.setCellRenderer(new HighlightTreeCellRenderer());
		environmentTree.getSelectionModel().addTreeSelectionListener(e -> {
			String selectedNode = e.getPath().getLastPathComponent().toString().toUpperCase().replace(" ", "_");
			Environment matchingEnvironment = Arrays.stream(environmentManager.getEnvironments())
				.filter(environment -> environment.toString().equals(selectedNode))
				.findFirst()
				.orElse(Environment.DEFAULT);
			currentlySelectedEnvironment = matchingEnvironment;
			updateView(currentlySelectedEnvironment);
		});

		createAttrPanel();
		updateView(Environment.DEFAULT);

		final JScrollPane treeScrollPane = new JScrollPane(environmentTree);
		treeScrollPane.setPreferredSize(new Dimension(220, 400));

		JPanel leftPanel = new JPanel(new BorderLayout());
		leftPanel.add(searchPanel, BorderLayout.NORTH);
		leftPanel.add(treeScrollPane, BorderLayout.CENTER);

		final JScrollPane infoScrollPane = new JScrollPane(attributePanel);
		infoScrollPane.setPreferredSize(new Dimension(400, 400));

		final JPanel bottomPanel = new JPanel();
		add(bottomPanel, BorderLayout.SOUTH);

		final JButton refreshWidgetsBtn = new JButton("Reset");
		refreshWidgetsBtn.addActionListener(e -> {
			environmentManager.startUp();
			updateView(Environment.DEFAULT);
			environmentManager.forceEnvironment(null);
		});
		bottomPanel.add(refreshWidgetsBtn);

		final JButton forceEnvironment = new JButton("Force Environment");
		forceEnvironment.setToolTipText("Force Selected Environment");
		forceEnvironment.addActionListener(e -> environmentManager.forceEnvironment(currentlySelectedEnvironment));
		bottomPanel.add(forceEnvironment);

		final JButton revalidateWidget = new JButton("Save");
		revalidateWidget.addActionListener(e -> save());
		bottomPanel.add(revalidateWidget);

		final JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, infoScrollPane);
		add(split, BorderLayout.CENTER);

		colorPicker = colorPickerManager.create(
			SwingUtilities.windowForComponent(this),
			Color.BLUE, "Editor", false
		);

		updateView(Environment.OVERWORLD);
	}

	/**
	 * Clears and prepares the attribute panel for new content.
	 */
	public void createAttrPanel() {
		attributePanel.removeAll();
		attributePanel.setLayout(new BoxLayout(attributePanel, BoxLayout.Y_AXIS));
		attributePanel.setOpaque(false);
	}

	/**
	 * Updates the attribute panel to show properties for the given environment.
	 */
	public void updateView(Environment environment) {
		attributePanel.removeAll();
		if (colorPicker != null) {
			colorPicker.setVisible(false);
		}
		// Group properties by category
		Map<String, List<Entry<String, PropertyData<Environment>>>> grouped = new LinkedHashMap<>();
		SchemaBasedEnvironmentPropertyRegistry.getInstance().getProperties().entrySet().forEach(entry -> {
			String category = entry.getValue().getCategory();
			grouped.computeIfAbsent(category, k -> new ArrayList<>()).add(entry);
		});
		for (String category : grouped.keySet()) {
			CollapsiblePanel section = new CollapsiblePanel(category);
			for (Entry<String, PropertyData<Environment>> entry : grouped.get(category)) {
				String key = entry.getKey();
				PropertyData<Environment> propertyData = entry.getValue();

				JPanel row = new JPanel();
				row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
				row.setOpaque(false);
				row.setAlignmentX(LEFT_ALIGNMENT);
				row.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

				JLabel propName = new JLabel(key);
				propName.setToolTipText("<html><p style='width:200px;'><b>Type:</b> " + "" + "<br>" + propertyData.getDescription() + "</p></html>");
				propName.setForeground(Color.WHITE);
				propName.setPreferredSize(new Dimension(140, 24));
				row.add(propName);

				JPanel cardLayoutPanel = new JPanel(new CardLayout());
				cardLayoutPanel.setOpaque(false);
				ComponentData componentData = PropertyComponentFactory.createComponent(propertyData);
				componentData.environmentEditor = this;
				componentData.environment = environment;
				componentData.key = key;
				componentData.value = getValue(environment, key);
				componentData.onValueChanged = () -> environmentManager.forceEnvironment(currentlySelectedEnvironment);
				componentData.create();
				cardLayoutPanel.add(componentData.component);
				row.add(cardLayoutPanel);

				row.add(Box.createHorizontalGlue());
				section.addContent(row);
			}
			attributePanel.add(section);
		}
		attributePanel.revalidate();
		attributePanel.repaint();
	}

	/**
	 * Saves the current environments to the environments.json file.
	 */
	public void save() {
		try {
			String json = plugin.getGson().toJson(environmentManager.getEnvironments());
			ResourcePath.path("src/main/resources/rs117/hd/scene/environments.json").writeString(json);
		} catch (Exception e) {
			log.error("Failed to save environments", e);
		}
	}

	/**
	 * Builds the environment tree structure for the editor.
	 */
	public DefaultMutableTreeNode buildEnvironmentTree() {
		DefaultMutableTreeNode root = new DefaultMutableTreeNode("Environments");
		addEnvironmentNodes(root, "Mainland", false);
		addEnvironmentNodes(root, "Underground", true);
		addEnvironmentNodesThemes(root, "Themes");
		return root;
	}

	private void addEnvironmentNodes(DefaultMutableTreeNode parent, String label, boolean underground) {
		DefaultMutableTreeNode node = (underground ? new DefaultMutableTreeNode(label) : parent);
		if (underground) {
			parent.add(node);
		}

		List<Environment> environments = Arrays.stream(environmentManager.getEnvironments())
			.filter(env -> env.area != null && (underground != Area.OVERWORLD.intersects(env.area)))
			.collect(Collectors.toList());

		environments.forEach(env -> node.add(new DefaultMutableTreeNode(new NodeData(env))));
		rearrangeNodes(node);
	}

	private void addEnvironmentNodesThemes(DefaultMutableTreeNode parent, String label) {
		DefaultMutableTreeNode node = new DefaultMutableTreeNode(label);
		parent.add(node);

		node.add(new DefaultMutableTreeNode(new NodeData(Environment.AUTUMN)));
		node.add(new DefaultMutableTreeNode(new NodeData(Environment.WINTER)));
	}

	private void rearrangeNodes(DefaultMutableTreeNode parent) {
		List<DefaultMutableTreeNode> nodesToRearrange = new ArrayList<>();
		for (int i = 0; i < parent.getChildCount(); i++) {
			DefaultMutableTreeNode childNode = (DefaultMutableTreeNode) parent.getChildAt(i);
			nodesToRearrange.addAll(findNodesToReparent(childNode, parent));
		}
		for (DefaultMutableTreeNode nodeToMove : nodesToRearrange) {
			reparentNode(nodeToMove, parent);
		}
	}

	private List<DefaultMutableTreeNode> findNodesToReparent(DefaultMutableTreeNode node, DefaultMutableTreeNode parent) {
		List<DefaultMutableTreeNode> nodesToReparent = new ArrayList<>();
		NodeData nodeData = (NodeData) node.getUserObject();
		for (int i = 0; i < parent.getChildCount(); i++) {
			DefaultMutableTreeNode potentialParentNode = (DefaultMutableTreeNode) parent.getChildAt(i);
			if (node != potentialParentNode) {
				NodeData potentialParentData = (NodeData) potentialParentNode.getUserObject();
				if (shouldReparent(nodeData.getData(), potentialParentData.getData())) {
					nodesToReparent.add(node);
					break;
				}
			}
		}
		return nodesToReparent;
	}

	private void reparentNode(DefaultMutableTreeNode nodeToMove, DefaultMutableTreeNode currentParent) {
		NodeData dataToMove = (NodeData) nodeToMove.getUserObject();
		for (int i = 0; i < currentParent.getChildCount(); i++) {
			DefaultMutableTreeNode potentialParentNode = (DefaultMutableTreeNode) currentParent.getChildAt(i);
			if (!potentialParentNode.isNodeDescendant(nodeToMove)) {
				NodeData potentialParentData = (NodeData) potentialParentNode.getUserObject();
				if (shouldReparent(dataToMove.getData(), potentialParentData.getData())) {
					currentParent.remove(nodeToMove);
					potentialParentNode.add(nodeToMove);
					break;
				}
			}
		}
	}

	private static boolean shouldReparent(Environment child, Environment parent) {
		return parent.area.intersects(child.area);
	}

	public String getValue(Environment environment, String key) {
		Function<Environment, String> retriever = SchemaBasedEnvironmentPropertyRegistry.getInstance().getProperties().get(key).getGetter();
		if (retriever != null) {
			System.out.println("VALIE: " + retriever.apply(environment));
			return retriever.apply(environment);
		}
		return "";
	}

	public void setValue(Environment environment, String key, Object value) {
		Environment targetEnvironment = findEnvironmentByName(environment.name());
		if (targetEnvironment == null) {
			log.info("Unable to find Environment: {}", environment.name());
			return;
		}
		BiConsumer<Environment, Object> setter = SchemaBasedEnvironmentPropertyRegistry.getInstance().getProperties().get(key).getSetter();
		if (setter != null) {
			setter.accept(targetEnvironment, value);
			environmentManager.refreshEnvironmentValues(environment,true);
		} else {
			log.info("Key not found: {}", key);
		}
	}

	private Environment findEnvironmentByName(String name) {
		for (Environment env : environmentManager.getEnvironments()) {
			if (Objects.equals(env.name(), name)) {
				return env;
			}
		}
		return null;
	}

	private void filterTree() {
		String text = searchField.getText().trim().toLowerCase();
		lastSearchText = text;
		DefaultMutableTreeNode root = buildEnvironmentTree();
		if (!text.isEmpty()) {
			filterTreeNodes(root, text);
		}
		environmentTree.setModel(new javax.swing.tree.DefaultTreeModel(root));
		environmentTree.setCellRenderer(new HighlightTreeCellRenderer());
		// Expand all nodes with children (sections with results)
		for (int i = 0; i < environmentTree.getRowCount(); i++) {
			environmentTree.expandRow(i);
		}
	}

	private boolean filterTreeNodes(DefaultMutableTreeNode node, String text) {
		boolean keep = false;
		for (int i = node.getChildCount() - 1; i >= 0; i--) {
			DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
			if (!filterTreeNodes(child, text)) {
				node.remove(i);
			} else {
				keep = true;
			}
		}
		Object userObj = node.getUserObject();
		if (userObj instanceof NodeData) {
			String name = ((NodeData) userObj).getData().name().toLowerCase();
			if (name.contains(text)) keep = true;
		}
		if (userObj instanceof String) {
			if (((String) userObj).toLowerCase().contains(text)) keep = true;
		}
		return keep || node.getChildCount() > 0;
	}

	private class HighlightTreeCellRenderer extends DefaultTreeCellRenderer {
		@Override
		public Component getTreeCellRendererComponent(javax.swing.JTree tree, Object value, boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
			Component c = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
			// No highlighting, just plain text
			setText(value.toString());
			return c;
		}
	}

}