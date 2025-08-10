package rs117.hd.utils.tooling.props.impl.render;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import net.runelite.client.plugins.config.ConfigPlugin;
import net.runelite.client.util.ImageUtil;

public class CollapsiblePanel extends JPanel {
    private static final ImageIcon SECTION_EXPAND_ICON;
    private static final ImageIcon SECTION_RETRACT_ICON;

    static {
        BufferedImage sectionRetractIcon = ImageUtil.loadImageResource(ConfigPlugin.class, "/util/arrow_right.png");
        sectionRetractIcon = ImageUtil.luminanceOffset(sectionRetractIcon, -121);
        SECTION_EXPAND_ICON = new ImageIcon(sectionRetractIcon);
        final BufferedImage sectionExpandIcon = ImageUtil.rotateImage(sectionRetractIcon, Math.PI / 2);
        SECTION_RETRACT_ICON = new ImageIcon(sectionExpandIcon);
    }

    private final JButton toggleButton;
    private final JPanel contentPanel;
    private boolean expanded = true;

    public CollapsiblePanel(String title) {
        setLayout(new BorderLayout());
        setOpaque(false);
        setAlignmentX(Component.LEFT_ALIGNMENT);

        toggleButton = new JButton(title, SECTION_RETRACT_ICON);
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
        contentPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

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
        // Ensure the component is left-aligned
        if (comp instanceof JComponent) {
            ((JComponent) comp).setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        contentPanel.add(comp);
    }

    public void setExpanded(boolean expand) {
        expanded = expand;
        contentPanel.setVisible(expanded);
        toggleButton.setIcon(expanded ? SECTION_RETRACT_ICON : SECTION_EXPAND_ICON);
        
        // Force revalidation of this panel and all parent containers
        revalidate();
        repaint();
        
        // Ensure parent containers also revalidate to handle layout changes
        Container parent = getParent();
        while (parent != null) {
            parent.revalidate();
            parent = parent.getParent();
        }
    }

    @Override
    public Dimension getPreferredSize() {
        if (!expanded) {
            // When collapsed, only return the size needed for the toggle button
            Dimension buttonSize = toggleButton.getPreferredSize();
            return new Dimension(buttonSize.width, buttonSize.height);
        }
        return super.getPreferredSize();
    }

    @Override
    public Dimension getMinimumSize() {
        if (!expanded) {
            // When collapsed, only return the minimum size needed for the toggle button
            Dimension buttonSize = toggleButton.getMinimumSize();
            return new Dimension(buttonSize.width, buttonSize.height);
        }
        return super.getMinimumSize();
    }

    @Override
    public Dimension getMaximumSize() {
        if (!expanded) {
            // When collapsed, allow the panel to shrink to minimum size
            Dimension buttonSize = toggleButton.getMaximumSize();
            return new Dimension(buttonSize.width, buttonSize.height);
        }
        return super.getMaximumSize();
    }

    public boolean isExpanded() {
        return expanded;
    }

    public JPanel getContentPanel() {
        return contentPanel;
    }
} 