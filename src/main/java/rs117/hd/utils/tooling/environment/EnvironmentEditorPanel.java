package rs117.hd.utils.tooling.environment;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.swing.tree.DefaultMutableTreeNode;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.components.colorpicker.ColorPickerManager;
import rs117.hd.HdPlugin;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.scene.areas.Area;
import rs117.hd.scene.environments.Environment;
import rs117.hd.utils.ResourcePath;
import rs117.hd.utils.tooling.props.PropertyData;
import rs117.hd.utils.tooling.props.impl.SchemaBasedEnvironmentPropertyRegistry;

@Slf4j
public class EnvironmentEditorPanel extends GenericPropertyEditorPanel<Environment> {

	// Managers and context
	private final EnvironmentManager environmentManager;

	public EnvironmentEditorPanel(
		ClientThread clientThread,
		Client client,
		EnvironmentManager environmentManager,
		ColorPickerManager colorPickerManager,
		HdPlugin plugin
	) {
		super(clientThread, client, colorPickerManager, plugin);
		this.environmentManager = environmentManager;
		
		// Initialize the tree after all fields are set
		initializeTree();
	}

	@Override
	protected Environment getDefaultData() {
		return Environment.DEFAULT;
	}

	@Override
	protected void onDataSelectionChanged(javax.swing.event.TreeSelectionEvent e) {
		if (environmentManager == null) {
			log.warn("Environment manager not available for data selection change");
			return;
		}
		
		String selectedNode = e.getPath().getLastPathComponent().toString();
		try {
			Environment matchingEnvironment = Arrays.stream(environmentManager.getEnvironments())
				.filter(environment -> environment.name().toLowerCase().equals(selectedNode.toLowerCase()))
				.findFirst()
				.orElse(Environment.DEFAULT);
			currentlySelectedData = matchingEnvironment;
			updateView(currentlySelectedData);
		} catch (Exception ex) {
			log.warn("Failed to handle data selection change: {}", ex.getMessage());
		}
	}

	@Override
	protected void onReset() {
		if (environmentManager != null) {
			environmentManager.startUp();
			environmentManager.forceEnvironment(null);
		} else {
			log.warn("Environment manager not available for reset");
		}
	}

	@Override
	protected void onForceData(Environment data) {
		if (environmentManager != null) {
			environmentManager.forceEnvironment(data);
		} else {
			log.warn("Environment manager not available for force data");
		}
	}

	@Override
	protected DefaultMutableTreeNode buildDataTree() {
		DefaultMutableTreeNode root = new DefaultMutableTreeNode("Environments");
		
		if (environmentManager != null) {
			addEnvironmentNodes(root, "Mainland", false);
			addEnvironmentNodes(root, "Underground", true);
			addEnvironmentNodesThemes(root, "Themes");
		} else {
			root.add(new DefaultMutableTreeNode("Environment Manager not available"));
		}
		
		return root;
	}

	@Override
	protected Environment[] getAllData() {
		if (environmentManager == null) {
			log.warn("Environment manager not available");
			return new Environment[0];
		}
		try {
			return environmentManager.getEnvironments();
		} catch (Exception e) {
			log.warn("Failed to get environments: {}", e.getMessage());
			return new Environment[0];
		}
	}

	@Override
	protected Map<String, PropertyData<Environment>> getPropertyRegistry() {
		try {
			return SchemaBasedEnvironmentPropertyRegistry.getInstance().getProperties();
		} catch (Exception e) {
			log.warn("Failed to get property registry: {}", e.getMessage());
			return new java.util.HashMap<>();
		}
	}

	@Override
	protected void onValueChanged(Environment data) {
		if (environmentManager != null) {
			environmentManager.forceEnvironment(data);
		} else {
			log.warn("Environment manager not available for value change");
		}
	}

	@Override
	public String getValue(Environment data, String key) {
		Map<String, PropertyData<Environment>> properties = SchemaBasedEnvironmentPropertyRegistry.getInstance().getProperties();
		if (properties == null || !properties.containsKey(key)) {
			return "";
		}
		
		PropertyData<Environment> propertyData = properties.get(key);
		if (propertyData == null || propertyData.getGetter() == null) {
			return "";
		}
		
		Function<Environment, String> retriever = propertyData.getGetter();
		System.out.println("VALUE: " + retriever.apply(data));
		return retriever.apply(data);
	}

	@Override
	public void setValue(Environment data, String key, Object value) {
		Environment targetEnvironment = findEnvironmentByName(data.name());
		if (targetEnvironment == null) {
			log.info("Unable to find Environment: {}", data.name());
			return;
		}
		
		Map<String, PropertyData<Environment>> properties = SchemaBasedEnvironmentPropertyRegistry.getInstance().getProperties();
		if (properties == null || !properties.containsKey(key)) {
			log.info("Property registry not available or key not found: {}", key);
			return;
		}
		
		PropertyData<Environment> propertyData = properties.get(key);
		if (propertyData == null || propertyData.getSetter() == null) {
			log.info("Property data or setter not available for key: {}", key);
			return;
		}
		
		BiConsumer<Environment, Object> setter = propertyData.getSetter();
		setter.accept(targetEnvironment, value);
		environmentManager.refreshEnvironmentValues(data, true);
	}

	@Override
	public net.runelite.client.ui.components.colorpicker.ColorPickerManager getColorPickerManager() {
		return colorPickerManager;
	}

	@Override
	public java.awt.Component getWindowComponent() {
		return this;
	}

	@Override
	public String getDisplayName(Environment data) {
		return data.name();
	}

	/**
	 * Saves the current environments to the environments.json file.
	 */
	@Override
	public void save() {
		if (environmentManager == null) {
			log.warn("Environment manager not available for save");
			return;
		}
		
		try {
			String json = getPlugin().getGson().toJson(environmentManager.getEnvironments());
			ResourcePath.path("src/main/resources/rs117/hd/scene/environments.json").writeString(json);
		} catch (Exception e) {
			log.error("Failed to save environments", e);
		}
	}

	private void addEnvironmentNodes(DefaultMutableTreeNode parent, String label, boolean underground) {
		if (environmentManager == null) {
			log.warn("Environment manager not available for addEnvironmentNodes");
			return;
		}
		
		DefaultMutableTreeNode node = (underground ? new DefaultMutableTreeNode(label) : parent);
		if (underground) {
			parent.add(node);
		}

		try {
			java.util.List<Environment> environments = Arrays.stream(environmentManager.getEnvironments())
				.filter(env -> env.area != null && (underground != Area.OVERWORLD.intersects(env.area)))
				.collect(Collectors.toList());

			environments.forEach(env -> node.add(new DefaultMutableTreeNode(new NodeData<>(env, env.name().toLowerCase()))));
			rearrangeNodes(node);
		} catch (Exception e) {
			log.warn("Failed to add environment nodes: {}", e.getMessage());
		}
	}

	private void addEnvironmentNodesThemes(DefaultMutableTreeNode parent, String label) {
		DefaultMutableTreeNode node = new DefaultMutableTreeNode(label);
		parent.add(node);

		node.add(new DefaultMutableTreeNode(new NodeData<>(Environment.AUTUMN, Environment.AUTUMN.name().toLowerCase())));
		node.add(new DefaultMutableTreeNode(new NodeData<>(Environment.WINTER, Environment.WINTER.name().toLowerCase())));
	}

	private void rearrangeNodes(DefaultMutableTreeNode parent) {
		java.util.List<DefaultMutableTreeNode> nodesToRearrange = new java.util.ArrayList<>();
		for (int i = 0; i < parent.getChildCount(); i++) {
			DefaultMutableTreeNode childNode = (DefaultMutableTreeNode) parent.getChildAt(i);
			nodesToRearrange.addAll(findNodesToReparent(childNode, parent));
		}
		for (DefaultMutableTreeNode nodeToMove : nodesToRearrange) {
			reparentNode(nodeToMove, parent);
		}
	}

	private java.util.List<DefaultMutableTreeNode> findNodesToReparent(DefaultMutableTreeNode node, DefaultMutableTreeNode parent) {
		java.util.List<DefaultMutableTreeNode> nodesToReparent = new java.util.ArrayList<>();
		NodeData<Environment> nodeData = (NodeData<Environment>) node.getUserObject();
		for (int i = 0; i < parent.getChildCount(); i++) {
			DefaultMutableTreeNode potentialParentNode = (DefaultMutableTreeNode) parent.getChildAt(i);
			if (node != potentialParentNode) {
				NodeData<Environment> potentialParentData = (NodeData<Environment>) potentialParentNode.getUserObject();
				if (shouldReparent(nodeData.getData(), potentialParentData.getData())) {
					nodesToReparent.add(node);
					break;
				}
			}
		}
		return nodesToReparent;
	}

	private void reparentNode(DefaultMutableTreeNode nodeToMove, DefaultMutableTreeNode currentParent) {
		NodeData<Environment> dataToMove = (NodeData<Environment>) nodeToMove.getUserObject();
		for (int i = 0; i < currentParent.getChildCount(); i++) {
			DefaultMutableTreeNode potentialParentNode = (DefaultMutableTreeNode) currentParent.getChildAt(i);
			if (!potentialParentNode.isNodeDescendant(nodeToMove)) {
				NodeData<Environment> potentialParentData = (NodeData<Environment>) potentialParentNode.getUserObject();
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

	private Environment findEnvironmentByName(String name) {
		if (environmentManager == null) {
			log.warn("Environment manager not available for findEnvironmentByName");
			return null;
		}
		
		try {
			for (Environment env : environmentManager.getEnvironments()) {
				if (Objects.equals(env.name(), name)) {
					return env;
				}
			}
		} catch (Exception e) {
			log.warn("Failed to find environment by name: {}", e.getMessage());
		}
		return null;
	}
}