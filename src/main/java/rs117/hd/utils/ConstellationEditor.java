package rs117.hd.utils;

import com.google.gson.Gson;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.HdPlugin;
import rs117.hd.config.ConstellationMode;
import rs117.hd.scene.daylight_cycle.ConstellationDefinition;
import rs117.hd.scene.daylight_cycle.StarField;

import static rs117.hd.utils.ResourcePath.path;

@Slf4j
public class ConstellationEditor {
	private static JFrame frame;

	public static void open(Gson gson) {
		SwingUtilities.invokeLater(() -> {
			if (frame != null) {
				frame.toFront();
				frame.requestFocus();
				return;
			}

			ConstellationEditor editor = new ConstellationEditor(gson);
			frame = editor.buildFrame();
			frame.addWindowListener(new java.awt.event.WindowAdapter() {
				@Override
				public void windowClosed(java.awt.event.WindowEvent e) {
					frame = null;
				}
			});
			frame.setVisible(true);
		});
	}

	private static class Shape {
		String name;
		ConstellationMode mode;
		float scale;
		final List<float[]> points = new ArrayList<>();
		final List<int[]> edges = new ArrayList<>();

		static Shape fromDefinition(ConstellationDefinition def) {
			Shape shape = new Shape();
			shape.name = def.name;
			shape.mode = def.mode == ConstellationMode.OFF ? ConstellationMode.FUN : def.mode;
			shape.scale = def.scale <= 0 ? 1 : def.scale;
			for (float[] p : def.points)
				shape.points.add(new float[] { p[0], p[1] });
			for (int[] e : def.edges)
				shape.edges.add(new int[] { e[0], e[1] });
			return shape;
		}

		ConstellationDefinition toDefinition() {
			ConstellationDefinition def = new ConstellationDefinition();
			def.name = name;
			def.mode = mode;
			def.scale = scale;
			def.points = points.toArray(new float[0][]);
			def.edges = edges.toArray(new int[0][]);
			return def;
		}

		@Override
		public String toString() {
			return "[" + (mode == ConstellationMode.REALISTIC ? "Realistic" : "Fun") + "] " + name;
		}
	}

	private final Gson gson;
	private final DefaultListModel<Shape> shapeListModel = new DefaultListModel<>();
	private final JList<Shape> shapeList = new JList<>(shapeListModel);
	private final ShapeCanvas canvas = new ShapeCanvas();

	private final JTextField nameField = new JTextField();
	private final JComboBox<ConstellationMode> modeCombo =
		new JComboBox<>(new ConstellationMode[] { ConstellationMode.REALISTIC, ConstellationMode.FUN });
	private final JSpinner scaleSpinner = new JSpinner(new SpinnerNumberModel(1.0, 0.1, 5.0, 0.05));
	private final JLabel statusLabel = new JLabel(" ");
	private final JLabel infoLabel = new JLabel(" ");

	private Shape current;

	private ConstellationEditor(Gson gson) {
		this.gson = gson;
	}

	private JFrame buildFrame() {
		JFrame f = new JFrame("117 HD - Constellation Editor");
		try {
			f.setIconImage(path(HdPlugin.class, "logo.png").loadImage());
		} catch (IOException ex) {
			log.debug("Unable to load HD logo for constellation editor icon", ex);
		}
		f.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

		f.add(buildLeftPanel(), BorderLayout.WEST);
		f.add(buildCanvasPanel(), BorderLayout.CENTER);
		f.add(buildRightPanel(), BorderLayout.EAST);
		f.add(buildBottomPanel(), BorderLayout.SOUTH);

		f.setSize(980, 680);
		f.setLocationRelativeTo(null);

		reload();

		return f;
	}

	private JPanel buildLeftPanel() {
		JPanel panel = new JPanel(new BorderLayout());
		panel.setBorder(new EmptyBorder(8, 8, 8, 4));
		panel.setPreferredSize(new Dimension(220, 0));

		shapeList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		shapeList.addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting())
				selectShape(shapeList.getSelectedValue());
		});
		panel.add(new JScrollPane(shapeList), BorderLayout.CENTER);

		JPanel buttons = new JPanel(new java.awt.GridLayout(3, 1, 0, 4));
		JButton newButton = new JButton("New shape");
		newButton.addActionListener(e -> addNewShape());
		JButton dupeButton = new JButton("Duplicate");
		dupeButton.addActionListener(e -> duplicateShape());
		JButton deleteButton = new JButton("Delete");
		deleteButton.addActionListener(e -> deleteShape());
		buttons.add(newButton);
		buttons.add(dupeButton);
		buttons.add(deleteButton);
		panel.add(buttons, BorderLayout.SOUTH);

		return panel;
	}

	private JPanel buildCanvasPanel() {
		JPanel wrapper = new JPanel(new BorderLayout());
		infoLabel.setBorder(new EmptyBorder(4, 8, 4, 8));
		infoLabel.setText(
			"Click empty space: add point, chained to the last one  ·  Ctrl+click: add an unconnected point  ·  " +
			"Drag: move point  ·  Shift+click two points: toggle edge  ·  Delete: remove selected point"
		);
		wrapper.add(infoLabel, BorderLayout.NORTH);
		wrapper.add(canvas, BorderLayout.CENTER);
		return wrapper;
	}

	private JPanel buildRightPanel() {
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBorder(new EmptyBorder(8, 4, 8, 8));
		panel.setPreferredSize(new Dimension(240, 0));

		panel.add(fieldRow("Name", nameField));
		panel.add(Box.createVerticalStrut(6));
		panel.add(fieldRow("Mode", modeCombo));
		panel.add(Box.createVerticalStrut(6));
		panel.add(fieldRow("Scale", scaleSpinner));
		panel.add(Box.createVerticalStrut(12));

		nameField.addActionListener(e -> commitFields());
		nameField.addFocusListener(new java.awt.event.FocusAdapter() {
			@Override
			public void focusLost(java.awt.event.FocusEvent e) {
				commitFields();
			}
		});
		modeCombo.addActionListener(e -> commitFields());
		scaleSpinner.addChangeListener(e -> commitFields());

		JButton clearButton = new JButton("Clear points");
		clearButton.addActionListener(e -> {
			if (current == null)
				return;
			current.points.clear();
			current.edges.clear();
			canvas.selected = -1;
			canvas.repaint();
			updateInfoCounts();
		});
		clearButton.setAlignmentX(0);
		panel.add(clearButton);

		panel.add(Box.createVerticalGlue());
		statusLabel.setForeground(Color.LIGHT_GRAY);
		panel.add(statusLabel);

		return panel;
	}

	private JPanel fieldRow(String label, java.awt.Component field) {
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.add(new JLabel(label), BorderLayout.NORTH);
		row.add(field, BorderLayout.CENTER);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));
		row.setAlignmentX(0);
		return row;
	}

	private JPanel buildBottomPanel() {
		JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
		JButton reloadButton = new JButton("Reload from disk");
		reloadButton.addActionListener(e -> {
			if (JOptionPane.showConfirmDialog(
				frame, "Discard unsaved changes and reload from disk?", "Reload",
				JOptionPane.YES_NO_OPTION
			) == JOptionPane.YES_OPTION)
				reload();
		});
		JButton saveButton = new JButton("Save to disk");
		saveButton.addActionListener(e -> save());
		panel.add(reloadButton);
		panel.add(saveButton);
		return panel;
	}

	private void reload() {
		commitFields();
		shapeListModel.clear();
		current = null;
		canvas.shape = null;
		canvas.selected = -1;

		try {
			ConstellationDefinition[] defs = StarField.CONSTELLATIONS_PATH.loadJson(gson, ConstellationDefinition[].class);
			if (defs != null)
				for (ConstellationDefinition def : defs)
					shapeListModel.addElement(Shape.fromDefinition(def));
			setStatus("Loaded " + shapeListModel.size() + " constellations from " + StarField.CONSTELLATIONS_PATH, false);
		} catch (IOException ex) {
			log.error("Failed to load star constellations in editor", ex);
			setStatus("Failed to load: " + ex.getMessage(), true);
		}

		if (!shapeListModel.isEmpty())
			shapeList.setSelectedIndex(0);
		else
			updateInfoCounts();
	}

	private void save() {
		commitFields();

		List<ConstellationDefinition> defs = new ArrayList<>();
		for (int i = 0; i < shapeListModel.size(); i++) {
			Shape shape = shapeListModel.get(i);
			ConstellationDefinition def = shape.toDefinition();
			try {
				def.normalize();
			} catch (RuntimeException ex) {
				setStatus("Fix \"" + shape.name + "\" before saving: " + ex.getMessage(), true);
				shapeList.setSelectedValue(shape, true);
				return;
			}
			defs.add(def);
		}

		try {
			StarField.CONSTELLATIONS_PATH.writeString(gson.toJson(defs));
			setStatus("Saved " + defs.size() + " constellations to " + StarField.CONSTELLATIONS_PATH, false);
		} catch (IllegalStateException ex) {
			setStatus(
				"Can't save: not running with a file system resource path. " +
				"Launch with -Drlhd.resource-path=<path to src/main/resources> to enable saving.",
				true
			);
		} catch (IOException ex) {
			log.error("Failed to save star constellations from editor", ex);
			setStatus("Failed to save: " + ex.getMessage(), true);
		}
	}

	private void setStatus(String message, boolean error) {
		statusLabel.setText("<html><div style='width:220px'>" + message + "</div></html>");
		statusLabel.setForeground(error ? new Color(0xcc4444) : Color.LIGHT_GRAY);
	}

	private void addNewShape() {
		commitFields();
		Shape shape = new Shape();
		shape.name = "New shape";
		shape.mode = ConstellationMode.FUN;
		shape.scale = 1;
		shapeListModel.addElement(shape);
		shapeList.setSelectedValue(shape, true);
	}

	private void duplicateShape() {
		if (current == null)
			return;
		commitFields();
		ConstellationDefinition def = current.toDefinition();
		Shape copy = Shape.fromDefinition(def);
		copy.name = current.name + " copy";
		int index = shapeListModel.indexOf(current);
		shapeListModel.add(index + 1, copy);
		shapeList.setSelectedValue(copy, true);
	}

	private void deleteShape() {
		if (current == null)
			return;
		if (JOptionPane.showConfirmDialog(
			frame, "Delete \"" + current.name + "\"?", "Delete shape", JOptionPane.YES_NO_OPTION
		) != JOptionPane.YES_OPTION)
			return;
		shapeListModel.removeElement(current);
		current = null;
		canvas.shape = null;
		canvas.selected = -1;
		canvas.repaint();
		updateInfoCounts();
	}

	private void selectShape(Shape shape) {
		commitFields();
		current = shape;
		canvas.shape = shape;
		canvas.selected = -1;
		canvas.repaint();

		boolean enabled = shape != null;
		nameField.setEnabled(enabled);
		modeCombo.setEnabled(enabled);
		scaleSpinner.setEnabled(enabled);

		if (shape != null) {
			nameField.setText(shape.name);
			modeCombo.setSelectedItem(shape.mode);
			scaleSpinner.setValue((double) shape.scale);
		} else {
			nameField.setText("");
		}
		updateInfoCounts();
	}

	private void commitFields() {
		if (current == null)
			return;
		current.name = nameField.getText().trim().isEmpty() ? "Unnamed" : nameField.getText().trim();
		ConstellationMode mode = (ConstellationMode) modeCombo.getSelectedItem();
		current.mode = mode == null ? ConstellationMode.FUN : mode;
		current.scale = (float) (double) (Double) scaleSpinner.getValue();
		shapeList.repaint();
	}

	private void updateInfoCounts() {
		if (current == null) {
			infoLabel.setText("No shape selected. Click \"New shape\" to start one.");
			return;
		}
		boolean valid = current.points.size() >= 2;
		infoLabel.setText(
			"Points: " + current.points.size() + "  Edges: " + current.edges.size() +
			(valid ? "" : "  ⚠ needs at least 2 points") +
			"  ·  Click empty space: add point, chained to the last one  ·  Ctrl+click: add an unconnected point  ·  " +
			"Drag: move point  ·  Shift+click two points: toggle edge  ·  Delete: remove selected point"
		);
	}

	private class ShapeCanvas extends JPanel {
		Shape shape;
		int selected = -1;
		int dragging = -1;

		ShapeCanvas() {
			setBackground(new Color(8, 8, 16));
			setPreferredSize(new Dimension(500, 500));
			setFocusable(true);

			addMouseListener(new MouseAdapter() {
				@Override
				public void mousePressed(MouseEvent e) {
					requestFocusInWindow();
					if (shape == null)
						return;

					int hit = findPointAt(e.getX(), e.getY());
					if (hit >= 0) {
						if (e.isShiftDown() && selected >= 0 && selected != hit) {
							toggleEdge(selected, hit);
							selected = hit;
						} else {
							selected = hit;
							dragging = hit;
						}
					} else {
						int previous = selected;
						float[] p = toLogical(e.getX(), e.getY());
						shape.points.add(p);
						selected = shape.points.size() - 1;
						// Chain-connect to the last point, so plotting a sequence of points
						// draws the shape's outline as you go. Ctrl+click starts a fresh,
						// unconnected point instead, e.g. to begin a separate sub-shape.
						if (previous >= 0 && !e.isControlDown())
							shape.edges.add(new int[] { previous, selected });
					}
					repaint();
					updateInfoCounts();
				}

				@Override
				public void mouseReleased(MouseEvent e) {
					dragging = -1;
				}
			});

			addMouseMotionListener(new MouseMotionAdapter() {
				@Override
				public void mouseDragged(MouseEvent e) {
					if (shape == null || dragging < 0)
						return;
					float[] p = toLogical(e.getX(), e.getY());
					shape.points.set(dragging, p);
					repaint();
				}
			});

			addKeyListener(new KeyAdapter() {
				@Override
				public void keyPressed(KeyEvent e) {
					if (shape == null || selected < 0)
						return;
					if (e.getKeyCode() == KeyEvent.VK_DELETE || e.getKeyCode() == KeyEvent.VK_BACK_SPACE) {
						removePoint(selected);
						selected = -1;
						repaint();
						updateInfoCounts();
					}
				}
			});
		}

		private void toggleEdge(int a, int b) {
			for (int i = 0; i < shape.edges.size(); i++) {
				int[] edge = shape.edges.get(i);
				if ((edge[0] == a && edge[1] == b) || (edge[0] == b && edge[1] == a)) {
					shape.edges.remove(i);
					return;
				}
			}
			shape.edges.add(new int[] { a, b });
		}

		private void removePoint(int index) {
			shape.points.remove(index);
			shape.edges.removeIf(edge -> edge[0] == index || edge[1] == index);
			for (int[] edge : shape.edges) {
				if (edge[0] > index)
					edge[0]--;
				if (edge[1] > index)
					edge[1]--;
			}
		}

		private int findPointAt(int px, int py) {
			if (shape == null)
				return -1;
			for (int i = 0; i < shape.points.size(); i++) {
				int[] s = toScreen(shape.points.get(i));
				if (Math.abs(s[0] - px) <= 8 && Math.abs(s[1] - py) <= 8)
					return i;
			}
			return -1;
		}

		private float scalePx() {
			return Math.min(getWidth(), getHeight()) / 2.8f;
		}

		private int[] toScreen(float[] logical) {
			float s = scalePx();
			return new int[] {
				getWidth() / 2 + Math.round(logical[0] * s),
				getHeight() / 2 - Math.round(logical[1] * s),
			};
		}

		private float[] toLogical(int px, int py) {
			float s = scalePx();
			return new float[] {
				(px - getWidth() / 2f) / s,
				-(py - getHeight() / 2f) / s,
			};
		}

		@Override
		protected void paintComponent(Graphics g) {
			super.paintComponent(g);
			Graphics2D g2 = (Graphics2D) g;
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

			int cx = getWidth() / 2, cy = getHeight() / 2;
			float s = scalePx();

			g2.setColor(new Color(40, 40, 60));
			for (float gx = -2; gx <= 2; gx += 0.5f) {
				int x = cx + Math.round(gx * s);
				g2.drawLine(x, 0, x, getHeight());
			}
			for (float gy = -2; gy <= 2; gy += 0.5f) {
				int y = cy - Math.round(gy * s);
				g2.drawLine(0, y, getWidth(), y);
			}
			g2.setColor(new Color(60, 60, 90));
			g2.drawOval(cx - Math.round(s), cy - Math.round(s), Math.round(s * 2), Math.round(s * 2));
			g2.setColor(new Color(70, 70, 100));
			g2.drawLine(0, cy, getWidth(), cy);
			g2.drawLine(cx, 0, cx, getHeight());

			if (shape == null)
				return;

			g2.setColor(new Color(130, 170, 255));
			for (int[] edge : shape.edges) {
				if (edge[0] >= shape.points.size() || edge[1] >= shape.points.size())
					continue;
				int[] a = toScreen(shape.points.get(edge[0]));
				int[] b = toScreen(shape.points.get(edge[1]));
				g2.drawLine(a[0], a[1], b[0], b[1]);
			}

			for (int i = 0; i < shape.points.size(); i++) {
				int[] p = toScreen(shape.points.get(i));
				boolean isSelected = i == selected;
				g2.setColor(isSelected ? new Color(255, 210, 90) : new Color(255, 255, 255));
				int r = isSelected ? 6 : 4;
				g2.fillOval(p[0] - r, p[1] - r, r * 2, r * 2);
				g2.setColor(new Color(200, 200, 200));
				g2.drawString(String.valueOf(i), p[0] + 8, p[1] - 8);
			}
		}
	}
}
