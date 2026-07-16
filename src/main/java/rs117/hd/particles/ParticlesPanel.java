package rs117.hd.particles;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;
import javax.swing.border.Border;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import net.runelite.client.util.LinkBrowser;

class ParticlesPanel extends PluginPanel
{
	enum Category
	{
		ALL("All", false),
		PLAYER("Player", false),
		WORLD("World", false),
		GRAPHIC("Gfx", false),
		NPC("NPC", false),
		PROJECTILE("Proj", true),
		WEATHER("Weather", false);

		private final String label;

		private final boolean wip;

		Category(String label, boolean wip)
		{
			this.label = label;
			this.wip = wip;
		}

		boolean matches(EmitterProfile profile)
		{
			switch (this)
			{
				case PLAYER:
					return EmitterProfile.TARGET_PLAYER.equals(profile.getTargetType());
				case PROJECTILE:
					return profile.isProjectileTarget();
				case WORLD:
					return profile.isObjectTarget();
				case NPC:
					return profile.isNpcTarget();
				case GRAPHIC:
					return profile.isGraphicTarget();
				case WEATHER:
					return profile.isWeatherTarget();
				default:
					return true;
			}
		}

		static boolean isWip(EmitterProfile profile)
		{
			if (profile.isWip())
			{
				return true;
			}
			return categoryWip(profile);
		}

		static boolean categoryWip(EmitterProfile profile)
		{
			for (Category value : values())
			{
				if (value.wip && value.matches(profile))
				{
					return true;
				}
			}
			return false;
		}
	}

	static boolean effectiveEnabled(EmitterProfile profile)
	{
		return profile != null && profile.isEnabled();
	}

	static boolean effectiveWip(EmitterProfile profile)
	{
		return profile != null && Category.isWip(profile);
	}

	@FunctionalInterface
	interface BulkToggle
	{
		void accept(Set<String> keys, boolean enabled);
	}

	private static final Icon PUBLISHED_ICON = markIcon(true);
	private static final Icon WIP_ICON = markIcon(false);

	private final boolean developerMode;
	private final BiConsumer<String, Boolean> onToggleProfile;
	private final BiConsumer<String, Boolean> onToggleWip;
	private final BulkToggle onToggleMany;
	private final BiConsumer<String, EmitterProfile> onPasteStyle;
	private final Consumer<String> onDeleteProfile;
	private final Consumer<String> onRenameProfile;
	private final Consumer<String> onEditProfile;
	private final JPanel profileList = new JPanel();
	private final IconTextField searchBar = new IconTextField();

	private Category category = Category.ALL;
	private Map<String, EmitterProfile> profiles = Map.of();
	private Set<String> presentSignatures = Set.of();

	@Nullable
	private EmitterProfile copiedStyle;

	ParticlesPanel(boolean developerMode, Runnable openViewer, Runnable onExport,
		BiConsumer<String, Boolean> onToggleProfile,
		BiConsumer<String, Boolean> onToggleWip, BulkToggle onToggleMany,
		BiConsumer<String, EmitterProfile> onPasteStyle, Consumer<String> onDeleteProfile,
		Consumer<String> onRenameProfile, Consumer<String> onEditProfile)
	{
		this.developerMode = developerMode;
		this.onToggleProfile = onToggleProfile;
		this.onToggleWip = onToggleWip;
		this.onToggleMany = onToggleMany;
		this.onPasteStyle = onPasteStyle;
		this.onDeleteProfile = onDeleteProfile;
		this.onRenameProfile = onRenameProfile;
		this.onEditProfile = onEditProfile;

		setLayout(new BorderLayout(0, 8));
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		profileList.setLayout(new BoxLayout(profileList, BoxLayout.Y_AXIS));

		JButton enableAll = new JButton("Enable all");
		enableAll.setToolTipText("Turn on every preset in the selected tab");
		enableAll.addActionListener(e -> onToggleMany.accept(keysInTab(), true));

		JButton disableAll = new JButton("Disable all");
		disableAll.setToolTipText("Turn off every preset in the selected tab");
		disableAll.addActionListener(e -> onToggleMany.accept(keysInTab(), false));

		JPanel controls = new JPanel();
		controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));

		searchBar.setIcon(IconTextField.Icon.SEARCH);

		searchBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchBar.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		searchBar.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				render();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				render();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				render();
			}
		});
		searchBar.addClearListener(this::render);

		JPanel topRow = new JPanel(new BorderLayout());
		topRow.add(searchBar, BorderLayout.CENTER);
		topRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		controls.add(topRow);
		controls.add(Box.createVerticalStrut(6));

		if (developerMode)
		{
			JButton open = new JButton("Vertex picker");
			open.addActionListener(e -> openViewer.run());
			JButton export = new JButton("Export bundle");
			export.setToolTipText("Mirror the current emitters and definitions into emitters.json + definitions.json");
			export.addActionListener(e -> onExport.run());
			JPanel openRow = new JPanel(new GridLayout(1, 2, 4, 0));
			openRow.add(open);
			openRow.add(export);
			openRow.setAlignmentX(Component.LEFT_ALIGNMENT);
			controls.add(openRow);
			controls.add(Box.createVerticalStrut(6));
		}

		MaterialTabGroup tabGroup = new MaterialTabGroup();

		tabGroup.setLayout(new GridLayout(0, 3, 4, 4));
		for (Category value : Category.values())
		{
			MaterialTab tab = new MaterialTab(value.label, tabGroup, new JPanel());
			tab.setHorizontalAlignment(SwingConstants.CENTER);
			tab.setOnSelectEvent(() ->
			{
				category = value;
				render();
				return true;
			});
			tabGroup.addTab(tab);
			if (value == Category.ALL)
			{
				tabGroup.select(tab);
			}
		}
		tabGroup.setAlignmentX(Component.LEFT_ALIGNMENT);
		controls.add(tabGroup);
		controls.add(Box.createVerticalStrut(6));

		JPanel bulkRow = new JPanel(new GridLayout(1, 2, 4, 0));
		bulkRow.add(enableAll);
		bulkRow.add(disableAll);
		bulkRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		controls.add(bulkRow);

		JPanel north = new JPanel(new BorderLayout(0, 8));
		north.add(controls, BorderLayout.NORTH);
		add(north, BorderLayout.NORTH);
		add(profileList, BorderLayout.CENTER);

		JLabel suggest = new JLabel("<html>Learn how to submit suggestions <u>here</u></html>");
		suggest.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		suggest.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		suggest.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		suggest.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				LinkBrowser.browse("https://runelite.net/plugin-hub/show/particles");
			}
		});
		add(suggest, BorderLayout.SOUTH);
	}

	private Set<String> keysInTab()
	{
		Set<String> keys = new HashSet<>();
		for (Map.Entry<String, EmitterProfile> entry : profiles.entrySet())
		{
			EmitterProfile profile = entry.getValue();
			if (category.matches(profile) && (developerMode || !Category.isWip(profile)))
			{
				keys.add(entry.getKey());
			}
		}
		return keys;
	}

	void rebuild(Map<String, EmitterProfile> profiles, Set<String> presentSignatures)
	{
		this.profiles = profiles;
		this.presentSignatures = presentSignatures;
		render();
	}

	private void render()
	{
		profileList.removeAll();

		if (!developerMode && category.wip)
		{
			JLabel comingSoon = new JLabel("Coming soon!");
			comingSoon.setForeground(Color.GRAY);
			comingSoon.setAlignmentX(Component.LEFT_ALIGNMENT);
			profileList.add(comingSoon);
			profileList.revalidate();
			profileList.repaint();
			return;
		}

		String query = searchBar.getText() == null ? "" : searchBar.getText().trim().toLowerCase();
		List<Map.Entry<String, EmitterProfile>> items = new ArrayList<>();

		for (Map.Entry<String, EmitterProfile> entry : profiles.entrySet())
		{
			EmitterProfile profile = entry.getValue();
			if (!category.matches(profile) || (!developerMode && Category.isWip(profile))
				|| !matchesSearch(profile, query))
			{
				continue;
			}
			items.add(entry);
		}

		Comparator<Map.Entry<String, EmitterProfile>> byName = Comparator.comparing(entry ->
			entry.getValue().getName() == null ? "" : entry.getValue().getName().toLowerCase());
		items.sort(byName);

		if (!items.isEmpty())
		{
			String count = items.size() + (items.size() == 1 ? " item" : " items");
			if (developerMode && category.wip)
			{
				count += " (WIP category)";
			}
			JLabel header = new JLabel(count);
			header.setAlignmentX(Component.LEFT_ALIGNMENT);
			profileList.add(header);
			profileList.add(Box.createVerticalStrut(6));
		}

		for (Map.Entry<String, EmitterProfile> entry : items)
		{
			profileList.add(buildRow(entry.getKey(), entry.getValue()));
		}

		profileList.revalidate();
		profileList.repaint();
	}

	private JPanel buildRow(String profileKey, EmitterProfile profile)
	{
		boolean worn = EmitterProfile.TARGET_PLAYER.equals(profile.getTargetType())
			&& presentSignatures.contains(profile.getSignature());
		String text = profile.getName()
			+ (profile.isProjectileTarget() && developerMode ? " [proj " + profile.getProjectileId() + "]" : "")
			+ (profile.isObjectTarget() && developerMode ? " [obj " + profile.getObjectId() + "]" : "")
			+ (profile.isNpcTarget() && developerMode ? " [npc " + profile.getNpcId() + "]" : "")
			+ (profile.isGraphicTarget() && developerMode ? " [gfx " + profile.getGraphicId() + "]" : "")
			+ (profile.isWeatherTarget() && developerMode ? " [weather]" : "");

		JCheckBox toggle = new JCheckBox(text, profile.isEnabled());

		toggle.setToolTipText(EmitterProfile.TARGET_PLAYER.equals(profile.getTargetType())
			? "<html>" + text + "<br>" + (worn
				? "On the model you are wearing now"
				: "Not on your current model") + "</html>"
			: text);
		toggle.addActionListener(e -> onToggleProfile.accept(profileKey, toggle.isSelected()));

		JPanel row = new JPanel(new BorderLayout());
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.add(toggle, BorderLayout.CENTER);

		if (developerMode)
		{
			JPanel west = new JPanel();
			west.setLayout(new BoxLayout(west, BoxLayout.X_AXIS));

			JCheckBox wipMark = new JCheckBox();
			wipMark.setIcon(WIP_ICON);
			wipMark.setSelectedIcon(PUBLISHED_ICON);
			wipMark.setSelected(!profile.isWip());
			wipMark.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6));
			wipMark.setToolTipText("Ship this profile. Uncheck to mark it work-in-progress: it stays saved but is hidden and disabled for non-developer users.");
			wipMark.addActionListener(e -> onToggleWip.accept(profileKey, !wipMark.isSelected()));
			west.add(wipMark);
			row.add(west, BorderLayout.WEST);

			JButton edit = new JButton("e");
			edit.setMargin(new Insets(0, 4, 0, 4));
			edit.setToolTipText("Edit this profile in the vertex picker");
			edit.addActionListener(e -> onEditProfile.accept(profileKey));

			JButton rename = new JButton("~");
			rename.setMargin(new Insets(0, 4, 0, 4));
			rename.setToolTipText("Rename this profile");
			rename.addActionListener(e -> onRenameProfile.accept(profileKey));

			JButton delete = new JButton("x");
			delete.setMargin(new Insets(0, 4, 0, 4));
			delete.setToolTipText("Delete this profile");
			delete.addActionListener(e -> onDeleteProfile.accept(profileKey));

			JPanel buttons = new JPanel();
			buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
			buttons.add(edit);
			buttons.add(rename);
			buttons.add(delete);
			row.add(buttons, BorderLayout.EAST);

			JPopupMenu menu = new JPopupMenu();
			JMenuItem copyStyle = new JMenuItem("Copy style");
			copyStyle.addActionListener(e -> copiedStyle = profile.copy());
			JMenuItem pasteStyle = new JMenuItem("Paste style");
			pasteStyle.addActionListener(e ->
			{
				if (copiedStyle != null)
				{
					onPasteStyle.accept(profileKey, copiedStyle);
				}
			});
			menu.add(copyStyle);
			menu.add(pasteStyle);
			menu.addPopupMenuListener(new PopupMenuListener()
			{
				@Override
				public void popupMenuWillBecomeVisible(PopupMenuEvent e)
				{
					pasteStyle.setEnabled(copiedStyle != null);
				}

				@Override
				public void popupMenuWillBecomeInvisible(PopupMenuEvent e)
				{
				}

				@Override
				public void popupMenuCanceled(PopupMenuEvent e)
				{
				}
			});
			row.setComponentPopupMenu(menu);
			toggle.setComponentPopupMenu(menu);
		}

		return row;
	}

	private static Icon markIcon(boolean published)
	{
		int s = 14;
		BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(110, 110, 110));
		g.drawRect(1, 1, s - 3, s - 3);
		if (published)
		{
			g.setColor(new Color(220, 45, 45));
			g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.drawLine(3, 7, 6, 10);
			g.drawLine(6, 10, 11, 3);
		}
		g.dispose();
		return new ImageIcon(img);
	}

	private static boolean matchesSearch(EmitterProfile profile, String query)
	{
		if (query.isEmpty())
		{
			return true;
		}
		if (profile.getName() != null && profile.getName().toLowerCase().contains(query))
		{
			return true;
		}
		if (profile.isProjectileTarget() && String.valueOf(profile.getProjectileId()).contains(query))
		{
			return true;
		}
		if (profile.isObjectTarget() && String.valueOf(profile.getObjectId()).contains(query))
		{
			return true;
		}
		if (profile.isNpcTarget() && String.valueOf(profile.getNpcId()).contains(query))
		{
			return true;
		}
		if (profile.isGraphicTarget() && String.valueOf(profile.getGraphicId()).contains(query))
		{
			return true;
		}
		if (profile.isWeatherTarget() && profile.getWeatherAreas() != null)
		{
			for (String area : profile.getWeatherAreas())
			{
				if (area != null && area.toLowerCase().contains(query))
				{
					return true;
				}
			}
		}
		for (int id : profile.getItemIds())
		{
			if (String.valueOf(id).contains(query))
			{
				return true;
			}
		}
		for (int id : profile.getAnimationIds())
		{
			if (String.valueOf(id).contains(query))
			{
				return true;
			}
		}
		return false;
	}
}
