package rs117.hd.scene.lights.debug;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.border.Border;
import net.runelite.client.ui.ColorScheme;

/** Shared styling for the lights dev editor. */
public final class DevUi {
	public static final int LABEL_WIDTH = 108;
	public static final int FIELD_HEIGHT = 30;
	public static final int LEFT_WIDTH = 440;
	public static final float BODY_FONT_SIZE = 13f;
	public static final float SEGMENT_FONT_SIZE = 13f;

	private static final Border CARD_BORDER = BorderFactory.createCompoundBorder(
		BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
		BorderFactory.createEmptyBorder(8, 10, 8, 10));

	private DevUi() {}

	public static void themeFrame(JComponent root) {
		root.setBackground(ColorScheme.DARK_GRAY_COLOR);
		root.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
	}

	public static void themePanel(JPanel panel) {
		panel.setOpaque(true);
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		panel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
	}

	public static JLabel titleLabel(String text) {
		JLabel label = new JLabel(text);
		label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		return label;
	}

	public static JLabel subtitleLabel(String text) {
		JLabel label = new JLabel(text);
		label.setFont(label.getFont().deriveFont(Font.PLAIN, 11f));
		label.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		return label;
	}

	public static JLabel mutedLabel(String text) {
		JLabel label = new JLabel(text);
		label.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		return label;
	}

	public static JPanel card() {
		JPanel card = new JPanel(new BorderLayout(0, 4));
		themePanel(card);
		card.setBorder(CARD_BORDER);
		return card;
	}

	public static Font bodyFont(Font base) {
		return base.deriveFont(BODY_FONT_SIZE);
	}

	public static void styleMainTabs(javax.swing.JTabbedPane tabs) {
		tabs.setFont(bodyFont(tabs.getFont()));
	}

	public static void styleCombo(javax.swing.JComboBox<?> combo) {
		combo.setFont(bodyFont(combo.getFont()));
		sizeField(combo);
	}

	public static void styleList(javax.swing.JList<?> list) {
		list.setFont(bodyFont(list.getFont()));
	}

	public static void styleToolbarButton(JButton button) {
		compactButton(button);
		button.setFont(bodyFont(button.getFont()));
	}

	public static void applyListRendererFont(javax.swing.DefaultListCellRenderer renderer) {
		renderer.setFont(bodyFont(renderer.getFont()));
	}

	public static void compactButton(javax.swing.AbstractButton button) {
		button.setFocusPainted(false);
		button.setMargin(new java.awt.Insets(4, 8, 4, 8));
	}

	public static JPanel segmentBar(javax.swing.AbstractButton... buttons) {
		JPanel bar = new JPanel(new GridLayout(1, buttons.length, 0, 0));
		bar.setOpaque(false);
		bar.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(0, 0, 4, 0)));
		for (javax.swing.AbstractButton button : buttons) {
			compactButton(button);
			bar.add(button);
		}
		return bar;
	}

	public static void styleSegmentButton(javax.swing.AbstractButton button, boolean selected) {
		button.setBackground(selected ? ColorScheme.MEDIUM_GRAY_COLOR : ColorScheme.DARKER_GRAY_COLOR);
		button.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		Font font = button.getFont().deriveFont(SEGMENT_FONT_SIZE);
		button.setFont(selected ? font.deriveFont(Font.BOLD) : font.deriveFont(Font.PLAIN));
	}

	public static void sizeField(JComponent field) {
		Dimension size = new Dimension(field.getPreferredSize().width, FIELD_HEIGHT);
		field.setPreferredSize(size);
		field.setMinimumSize(new Dimension(40, FIELD_HEIGHT));
	}

	public static JComponent formRow(String label, JComponent field) {
		sizeField(field);
		JPanel row = new JPanel(new BorderLayout(8, 0));
		row.setOpaque(false);
		row.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, FIELD_HEIGHT + 6));

		JLabel l = new JLabel(label);
		l.setFont(bodyFont(l.getFont()));
		l.setPreferredSize(new Dimension(LABEL_WIDTH, FIELD_HEIGHT));
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		row.add(l, BorderLayout.WEST);
		row.add(field, BorderLayout.CENTER);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		return row;
	}

	public static JComponent vectorRow(String label, JComponent x, JComponent y, JComponent z) {
		sizeField(x);
		sizeField(y);
		sizeField(z);
		JPanel trio = new JPanel(new GridLayout(1, 3, 6, 0));
		trio.setOpaque(false);
		trio.add(x);
		trio.add(y);
		trio.add(z);
		return formRow(label, trio);
	}

	public static Section section(String title, boolean expanded, Runnable onToggle, JComponent... rows) {
		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setOpaque(false);
		content.setBorder(BorderFactory.createEmptyBorder(2, 4, 6, 0));
		for (JComponent row : rows)
			content.add(row);
		content.setVisible(expanded);

		JLabel chevron = new JLabel(expanded ? "▾" : "▸", SwingConstants.CENTER);
		chevron.setPreferredSize(new Dimension(16, 20));
		chevron.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		JLabel titleLabel = new JLabel(title);
		titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, BODY_FONT_SIZE));
		titleLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		JPanel header = new JPanel(new BorderLayout(6, 0));
		header.setOpaque(true);
		header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		header.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(6, 8, 6, 8)));
		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		header.add(chevron, BorderLayout.WEST);
		header.add(titleLabel, BorderLayout.CENTER);
		header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
		header.setAlignmentX(Component.LEFT_ALIGNMENT);

		MouseAdapter toggle = new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				boolean open = !content.isVisible();
				content.setVisible(open);
				chevron.setText(open ? "▾" : "▸");
				if (onToggle != null)
					onToggle.run();
			}
		};
		header.addMouseListener(toggle);

		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setOpaque(false);
		panel.add(header);
		panel.add(content);
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		return new Section(panel, content);
	}

	public static JScrollPane scroll(JComponent body) {
		JPanel wrap = new JPanel();
		wrap.setLayout(new BoxLayout(wrap, BoxLayout.Y_AXIS));
		themePanel(wrap);
		wrap.setBorder(BorderFactory.createEmptyBorder(4, 2, 8, 2));
		wrap.add(body);
		wrap.add(Box.createVerticalGlue());

		JScrollPane scroll = new JScrollPane(wrap,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		return scroll;
	}

	public static final class Section {
		public final JPanel root;
		public final JPanel content;

		Section(JPanel root, JPanel content) {
			this.root = root;
			this.content = content;
		}
	}
}
