package rs117.hd.utils.tooling.props.impl.render;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

public class CollapsiblePanel extends JPanel {
    private final JButton toggleButton;
    private final JPanel contentPanel;
    private boolean expanded = true;

    public CollapsiblePanel(String title) {
        setLayout(new BorderLayout());
        setOpaque(false);

        toggleButton = new JButton("▼ " + title);
        toggleButton.setFocusPainted(false);
        toggleButton.setContentAreaFilled(false);
        toggleButton.setBorderPainted(false);
        toggleButton.setHorizontalAlignment(SwingConstants.LEFT);
        toggleButton.setFont(toggleButton.getFont().deriveFont(Font.BOLD, 14f));
        toggleButton.setForeground(Color.WHITE);
        toggleButton.setBackground(new Color(35, 35, 35));
        toggleButton.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setOpaque(false);
        contentPanel.setBorder(BorderFactory.createEmptyBorder(4, 16, 8, 8));

        toggleButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                setExpanded(!expanded);
            }
        });

        add(toggleButton, BorderLayout.NORTH);
        add(contentPanel, BorderLayout.CENTER);
    }

    public void addContent(Component comp) {
        contentPanel.add(comp);
    }

    public void setExpanded(boolean expand) {
        expanded = expand;
        contentPanel.setVisible(expanded);
        toggleButton.setText((expanded ? "▼ " : "► ") + toggleButton.getText().substring(2));
        revalidate();
        repaint();
    }

    public boolean isExpanded() {
        return expanded;
    }

    public JPanel getContentPanel() {
        return contentPanel;
    }
} 