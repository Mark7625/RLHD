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
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.components.colorpicker.ColorPickerManager;
import net.runelite.client.ui.components.colorpicker.RuneliteColorPicker;
import rs117.hd.HdPlugin;
import rs117.hd.utils.tooling.props.PropertyContext;
import rs117.hd.utils.tooling.props.PropertyComponentFactory;
import rs117.hd.utils.tooling.props.PropertyData;
import rs117.hd.utils.tooling.props.impl.render.CollapsiblePanel;

/**
 * Generic property editor panel that can be used for any data type
 * @param <T> The type of data object being edited
 */
@Slf4j
public abstract class GenericPropertyEditorPanel<T> extends JPanel implements PropertyContext<T> {

	// UI Components
	private final JPanel attributePanel = new JPanel();
	private final JTextField searchField;
	private final JTree dataTree;
	public RuneliteColorPicker colorPicker;
	public final ColorPickerManager colorPickerManager;

	// Managers and context
	private final Client client;
	private final ClientThread clientThread;
	@Getter
	private final HdPlugin plugin;

	protected T currentlySelectedData;
	private String lastSearchText = "";
	private boolean treeInitialized = false;

	public GenericPropertyEditorPanel(
		ClientThread clientThread,
		Client client,
		ColorPickerManager colorPickerManager,
		HdPlugin plugin
	) {
		this.clientThread = clientThread;
		this.client = client;
		this.colorPickerManager = colorPickerManager;
		this.plugin = plugin;
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setLayout(new BorderLayout());

		// Search field above tree
		searchField = new JTextField();
		searchField.setToolTipText("Type to search (case-insensitive)...");
		searchField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		searchField.getDocument().addDocumentListener(new DocumentListener() {
			@Override public void insertUpdate(DocumentEvent e) { filterTree(); }
			@Override public void removeUpdate(DocumentEvent e) { filterTree(); }
			@Override public void changedUpdate(DocumentEvent e) { filterTree(); }
		});

		JPanel searchPanel = new JPanel(new BorderLayout());
		searchPanel.add(searchField, BorderLayout.CENTER);
		searchPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

		// Initialize tree with empty root first, will be populated later
		dataTree = new JTree(new DefaultMutableTreeNode("Loading..."));
		dataTree.setRootVisible(false);
		dataTree.setShowsRootHandles(true);
		dataTree.setCellRenderer(new HighlightTreeCellRenderer());
		dataTree.getSelectionModel().addTreeSelectionListener(e -> {
			// This should be implemented by subclasses
			onDataSelectionChanged(e);
		});

		createAttrPanel();

		final JScrollPane treeScrollPane = new JScrollPane(dataTree);
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
			onReset();
			updateView(getDefaultData());
		});
		bottomPanel.add(refreshWidgetsBtn);

		final JButton forceData = new JButton("Force Selection");
		forceData.setToolTipText("Force Selected Data");
		forceData.addActionListener(e -> onForceData(currentlySelectedData));
		bottomPanel.add(forceData);

		final JButton revalidateWidget = new JButton("Save");
		revalidateWidget.addActionListener(e -> save());
		bottomPanel.add(revalidateWidget);

		final JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, infoScrollPane);
		add(split, BorderLayout.CENTER);

		colorPicker = colorPickerManager.create(
			SwingUtilities.windowForComponent(this),
			Color.BLUE, "Editor", false
		);
	}

	/**
	 * Initialize the tree and view after constructor completes.
	 * This should be called by subclasses after they've set up their fields.
	 */
	protected void initializeTree() {
		// Build and set the actual tree
		dataTree.setModel(new javax.swing.tree.DefaultTreeModel(buildDataTree()));
		updateView(getDefaultData());
		treeInitialized = true;
	}

	/**
	 * Get the default data to display initially
	 */
	protected abstract T getDefaultData();

	/**
	 * Handle data selection changes in the tree
	 */
	protected abstract void onDataSelectionChanged(javax.swing.event.TreeSelectionEvent e);

	/**
	 * Handle reset action
	 */
	protected abstract void onReset();

	/**
	 * Handle force data action
	 */
	protected abstract void onForceData(T data);

	/**
	 * Build the data tree structure
	 */
	protected abstract DefaultMutableTreeNode buildDataTree();

	/**
	 * Get all available data objects
	 */
	protected abstract T[] getAllData();

	/**
	 * Get the property registry for this data type
	 */
	protected abstract Map<String, PropertyData<T>> getPropertyRegistry();

	/**
	 * Clears and prepares the attribute panel for new content.
	 */
	public void createAttrPanel() {
		attributePanel.removeAll();
		attributePanel.setLayout(new BoxLayout(attributePanel, BoxLayout.Y_AXIS));
		attributePanel.setOpaque(false);
	}

	/**
	 * Updates the attribute panel to show properties for the given data.
	 */
	public void updateView(T data) {
		attributePanel.removeAll();
		if (colorPicker != null) {
			colorPicker.setVisible(false);
		}
		
		// Don't update view until the tree is properly initialized
		if (!treeInitialized) {
			return;
		}
		
		// Group properties by category
		Map<String, List<Entry<String, PropertyData<T>>>> grouped = new LinkedHashMap<>();
		Map<String, PropertyData<T>> registry = getPropertyRegistry();
		if (registry != null) {
			registry.entrySet().forEach(entry -> {
				String category = entry.getValue().getCategory();
				grouped.computeIfAbsent(category, k -> new ArrayList<>()).add(entry);
			});
		}
		
		for (String category : grouped.keySet()) {
			CollapsiblePanel section = new CollapsiblePanel(category);
			for (Entry<String, PropertyData<T>> entry : grouped.get(category)) {
				String key = entry.getKey();
				PropertyData<T> propertyData = entry.getValue();

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
				rs117.hd.utils.tooling.props.ComponentData<T> componentData = PropertyComponentFactory.createComponent(propertyData);
				componentData.context = this;
				componentData.data = data;
				componentData.key = key;
				componentData.value = getValue(data, key);
				componentData.onValueChanged = () -> onValueChanged(data);
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
	 * Called when a value changes
	 */
	protected void onValueChanged(T data) {
		// Default implementation - can be overridden by subclasses
	}

	/**
	 * Saves the current data to file.
	 */
	public abstract void save();

	/**
	 * Filters the tree based on search text
	 */
	private void filterTree() {
		// Don't filter until the tree is properly initialized
		if (!treeInitialized) {
			return;
		}
		
		String text = searchField.getText().trim().toLowerCase();
		lastSearchText = text;
		DefaultMutableTreeNode root = buildDataTree();
		if (!text.isEmpty()) {
			filterTreeNodes(root, text);
		}
		dataTree.setModel(new javax.swing.tree.DefaultTreeModel(root));
		dataTree.setCellRenderer(new HighlightTreeCellRenderer());
		// Expand all nodes with children (sections with results)
		for (int i = 0; i < dataTree.getRowCount(); i++) {
			dataTree.expandRow(i);
		}
	}

	/**
	 * Recursively filters tree nodes based on search text
	 */
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
			String name = ((NodeData) userObj).toString().toLowerCase();
			if (name.contains(text)) keep = true;
		}
		if (userObj instanceof String) {
			if (((String) userObj).toLowerCase().contains(text)) keep = true;
		}
		return keep || node.getChildCount() > 0;
	}

	/**
	 * Generic node data wrapper
	 */
	protected static class NodeData<T> {
		private final T data;
		private final String displayText;
		
		public NodeData(T data) {
			this.data = data;
			this.displayText = data.toString();
		}
		
		public NodeData(T data, String displayText) {
			this.data = data;
			this.displayText = displayText;
		}
		
		public T getData() {
			return data;
		}
		
		@Override
		public String toString() {
			return displayText;
		}
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
