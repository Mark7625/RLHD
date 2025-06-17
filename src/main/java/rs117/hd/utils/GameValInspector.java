package rs117.hd.utils;

import com.google.inject.Inject;
import javax.swing.tree.DefaultMutableTreeNode;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.devtools.DevToolsFrame;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;
import javax.swing.tree.*;
import java.awt.*;
import java.util.Map;
import java.util.Map.Entry;

import static rs117.hd.scene.GamevalManager.GAMEVALS;

@Slf4j
public class GameValInspector extends DevToolsFrame {
	private final Client client;
	private final ClientThread clientThread;

	private final JTextField nameSearchField;
	private final JTextField idSearchField;
	private final DefaultMutableTreeNode root;
	private final DefaultTreeModel treeModel;
	private final JTree tree;
	private final JTextArea infoArea;

	@Inject
	public GameValInspector(Client client, ClientThread clientThread) {
		this.client = client;
		this.clientThread = clientThread;

		nameSearchField = new JTextField();
		idSearchField = new JTextField();
		root = new DefaultMutableTreeNode("Game Values");
		treeModel = new DefaultTreeModel(root);
		tree = new JTree(treeModel);
		infoArea = new JTextArea();

		// Make ID field numeric-only
		setNumericOnly(idSearchField);

		setTitle("Game Value Inspector");
		setSize(670, 478);
		setMinimumSize(new Dimension(770, 578));
		setMaximumSize(new Dimension(770, 578));
		setResizable(false);
		setLayout(new BorderLayout());

		tree.setModel(treeModel);
		tree.addTreeSelectionListener(e -> {
			DefaultMutableTreeNode selected = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
			if (selected == null || selected.isRoot()) return;

			Object userObject = selected.getUserObject();
			if (!(userObject instanceof String)) return;

			infoArea.setText("Selected: " + userObject);
		});

		addSearchListeners();
		buildTree();

		JPanel searchPanel = new JPanel(new GridLayout(4, 1));
		searchPanel.add(new JLabel("Search by Name:"));
		searchPanel.add(nameSearchField);
		searchPanel.add(new JLabel("Search by ID:"));
		searchPanel.add(idSearchField);

		JPanel leftPanel = new JPanel(new BorderLayout());
		leftPanel.add(searchPanel, BorderLayout.NORTH);
		leftPanel.add(new JScrollPane(tree), BorderLayout.CENTER);

		infoArea.setEditable(false);
		JScrollPane rightScroll = new JScrollPane(infoArea);

		JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightScroll);
		splitPane.setResizeWeight(0.3);
		splitPane.setDividerLocation((int) (620 * 0.7));

		add(splitPane, BorderLayout.CENTER);
		pack();
	}

	private void buildTree() {
		root.removeAllChildren();

		for (Entry<String, Map<String, Integer>> categoryEntry : GAMEVALS.entrySet()) {
			DefaultMutableTreeNode categoryNode = new DefaultMutableTreeNode(categoryEntry.getKey());

			for (Entry<String, Integer> item : categoryEntry.getValue().entrySet()) {
				String label = item.getKey() + " (" + item.getValue() + ")";
				categoryNode.add(new DefaultMutableTreeNode(label));
			}

			root.add(categoryNode);
		}

		treeModel.reload();
	}

	private void filterTree() {
		String nameQuery = nameSearchField.getText().toLowerCase();
		String idQuery = idSearchField.getText();

		root.removeAllChildren();

		for (Entry<String, Map<String, Integer>> categoryEntry : GAMEVALS.entrySet()) {
			DefaultMutableTreeNode categoryNode = new DefaultMutableTreeNode(categoryEntry.getKey());

			for (Entry<String, Integer> item : categoryEntry.getValue().entrySet()) {
				String name = item.getKey();
				String id = String.valueOf(item.getValue());
				String label = name + " (" + id + ")";

				if (name.toLowerCase().contains(nameQuery) && id.contains(idQuery)) {
					categoryNode.add(new DefaultMutableTreeNode(label));
				}
			}

			if (categoryNode.getChildCount() > 0) {
				root.add(categoryNode);
			}
		}

		treeModel.reload();
	}

	private void addSearchListeners() {
		DocumentListener listener = new DocumentListener() {
			public void insertUpdate(DocumentEvent e) { filterTree(); }
			public void removeUpdate(DocumentEvent e) { filterTree(); }
			public void changedUpdate(DocumentEvent e) { filterTree(); }
		};

		nameSearchField.getDocument().addDocumentListener(listener);
		idSearchField.getDocument().addDocumentListener(listener);
	}

	private void setNumericOnly(JTextField field) {
		Document doc = field.getDocument();
		if (doc instanceof AbstractDocument) {
			((AbstractDocument) doc).setDocumentFilter(new DocumentFilter() {
				@Override
				public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr) throws BadLocationException {
					if (string.matches("\\d*")) {
						super.insertString(fb, offset, string, attr);
					}
				}

				@Override
				public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs) throws BadLocationException {
					if (text.matches("\\d*")) {
						super.replace(fb, offset, length, text, attrs);
					}
				}
			});
		}
	}

	@Override
	public void open() {
		buildTree();
		super.open();
	}

	@Override
	public void close() {
		super.close();
	}
}