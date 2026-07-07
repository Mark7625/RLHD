package rs117.hd.particles;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Point;
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
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
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

/**
 * Sidebar panel: lists saved emitter profiles with search and category
 * filters. Related profiles can be grouped into collapsible folders; end users
 * see and toggle a folder as one row, while developers expand it to edit its
 * members. Authoring controls (vertex picker, edit/rename/delete, folder
 * drag-and-drop) only appear in developer mode; end users see read-only presets
 * with enable/disable toggles.
 */
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
		/**
		 * Work-in-progress category: outside developer mode its tab shows a
		 * Coming soon placeholder, its profiles vanish from the sidebar, and
		 * they are force-disabled at emission time. Flip to false when the
		 * category is ready to ship.
		 */
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

		/**
		 * True when the profile is individually marked work-in-progress or
		 * falls into any WIP category, so it is hidden and force-disabled
		 * outside developer mode.
		 */
		static boolean isWip(EmitterProfile profile)
		{
			if (profile.isWip())
			{
				return true;
			}
			return categoryWip(profile);
		}

		/**
		 * True when the profile's category (not the profile itself) is WIP -
		 * used to hide a whole folder for shipped users.
		 */
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

	/**
	 * A profile's effective enabled state, folding in its folder's preference
	 * (the folder gates every member). Static and pure over an explicit folders
	 * map so it runs against an atomic snapshot without tearing against live
	 * store state.
	 */
	static boolean effectiveEnabled(EmitterProfile profile, Map<String, ProfileFolder> folders)
	{
		if (!profile.isEnabled())
		{
			return false;
		}
		String folderId = profile.getFolderId();
		if (folderId == null)
		{
			return true;
		}
		ProfileFolder folder = folders.get(folderId);
		return folder == null || folder.isEnabled();
	}

	/**
	 * A profile's effective WIP state: its own or its category's WIP, plus its
	 * folder's WIP (which hides the whole group for shipped users).
	 */
	static boolean effectiveWip(EmitterProfile profile, Map<String, ProfileFolder> folders)
	{
		if (Category.isWip(profile))
		{
			return true;
		}
		String folderId = profile.getFolderId();
		if (folderId == null)
		{
			return false;
		}
		ProfileFolder folder = folders.get(folderId);
		return folder != null && folder.isWip();
	}

	/**
	 * The folder mutations the sidebar fires, bundled so the constructor stays
	 * readable. create groups two loose profiles; addMember joins an existing
	 * folder; removeMember orphans one child; the rest map to the folder's own
	 * settings.
	 */
	static final class FolderActions
	{
		final BiConsumer<String, Boolean> toggleEnabled;
		final BiConsumer<String, Boolean> toggleWip;
		final Consumer<String> rename;
		final Consumer<String> dissolve;
		final Consumer<String> removeMember;
		final BiConsumer<String, String> create;
		final BiConsumer<String, String> addMember;

		FolderActions(BiConsumer<String, Boolean> toggleEnabled, BiConsumer<String, Boolean> toggleWip,
			Consumer<String> rename, Consumer<String> dissolve, Consumer<String> removeMember,
			BiConsumer<String, String> create, BiConsumer<String, String> addMember)
		{
			this.toggleEnabled = toggleEnabled;
			this.toggleWip = toggleWip;
			this.rename = rename;
			this.dissolve = dissolve;
			this.removeMember = removeMember;
			this.create = create;
			this.addMember = addMember;
		}
	}

	/**
	 * Bulk enable/disable over the rendered rows: loose profile keys and folder
	 * ids handled separately (foldered children are never touched).
	 */
	interface BulkToggle
	{
		void accept(Set<String> looseKeys, Set<String> folderIds, boolean enabled);
	}

	/**
	 * Dev-only ship/WIP mark icons: a red check for shipped, an empty box for
	 * work-in-progress. Drawn so the check is red regardless of the theme.
	 */
	private static final Icon PUBLISHED_ICON = markIcon(true);
	private static final Icon WIP_ICON = markIcon(false);

	// Client-property keys stashed on rows so a drop reads identity off the
	// live component under the cursor, never a stale rebuilt row reference.
	private static final String DRAG_KEY = "particles.dragKey";
	private static final String DRAG_CAT = "particles.dragCat";
	private static final String DROP_KIND = "particles.dropKind";
	private static final String DROP_KEY = "particles.dropKey";
	private static final String DROP_CAT = "particles.dropCat";
	private static final String DROP_FOLDER = "particles.dropFolder";
	private static final int DRAG_THRESHOLD = 5;

	private final boolean developerMode;
	private final BiConsumer<String, Boolean> onToggleProfile;
	private final BiConsumer<String, Boolean> onToggleWip;
	private final BulkToggle onToggleMany;
	private final BiConsumer<String, EmitterProfile> onPasteStyle;
	private final Consumer<String> onDeleteProfile;
	private final Consumer<String> onRenameProfile;
	private final Consumer<String> onEditProfile;
	private final FolderActions folderActions;
	private final JPanel profileList = new JPanel();
	private final IconTextField searchBar = new IconTextField();

	private Category category = Category.ALL;
	private Map<String, EmitterProfile> profiles = Map.of();
	private Map<String, ProfileFolder> folders = Map.of();
	private Set<String> presentSignatures = Set.of();
	/**
	 * Folder ids the developer has expanded; kept across re-renders so editing
	 * a child doesn't collapse the folder.
	 */
	private final Set<String> expanded = new HashSet<>();
	/**
	 * Style clipboard for the right-click copy/paste flow; a detached copy
	 * so later edits or deletion of the source don't change what pastes.
	 */
	@Nullable
	private EmitterProfile copiedStyle;

	// Drag state lives on the panel, not on rows (rows are rebuilt each render)
	@Nullable
	private String dragKey;
	@Nullable
	private String dragCategory;
	@Nullable
	private Point pressScreen;
	private boolean dragging;
	private boolean pendingRender;
	@Nullable
	private JComponent dropTarget;
	@Nullable
	private Border dropTargetBorder;

	/**
	 * One shared recognizer attached to every drag handle; it reads the dragged
	 * profile's identity off the handle's client properties at press time.
	 */
	private final MouseAdapter dragAdapter = new MouseAdapter()
	{
		@Override
		public void mousePressed(MouseEvent e)
		{
			JComponent handle = (JComponent) e.getComponent();
			dragKey = (String) handle.getClientProperty(DRAG_KEY);
			dragCategory = (String) handle.getClientProperty(DRAG_CAT);
			pressScreen = e.getLocationOnScreen();
			dragging = false;
		}

		@Override
		public void mouseDragged(MouseEvent e)
		{
			if (dragKey == null)
			{
				return;
			}
			if (!dragging)
			{
				Point now = e.getLocationOnScreen();
				if (Math.hypot(now.x - pressScreen.x, now.y - pressScreen.y) < DRAG_THRESHOLD)
				{
					return;
				}
				dragging = true;
			}
			hoverDropTarget(e);
		}

		@Override
		public void mouseReleased(MouseEvent e)
		{
			boolean wasDragging = dragging;
			dragging = false;
			if (wasDragging)
			{
				performDrop(e);
			}
			dragKey = null;
			dragCategory = null;
			clearHighlight();
			if (pendingRender)
			{
				pendingRender = false;
				render();
			}
		}
	};

	ParticlesPanel(boolean developerMode, Runnable openViewer, Runnable onExport,
		BiConsumer<String, Boolean> onToggleProfile,
		BiConsumer<String, Boolean> onToggleWip, BulkToggle onToggleMany,
		BiConsumer<String, EmitterProfile> onPasteStyle, Consumer<String> onDeleteProfile,
		Consumer<String> onRenameProfile, Consumer<String> onEditProfile, FolderActions folderActions)
	{
		this.developerMode = developerMode;
		this.onToggleProfile = onToggleProfile;
		this.onToggleWip = onToggleWip;
		this.onToggleMany = onToggleMany;
		this.onPasteStyle = onPasteStyle;
		this.onDeleteProfile = onDeleteProfile;
		this.onRenameProfile = onRenameProfile;
		this.onEditProfile = onEditProfile;
		this.folderActions = folderActions;

		setLayout(new BorderLayout(0, 8));
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		profileList.setLayout(new BoxLayout(profileList, BoxLayout.Y_AXIS));

		JButton enableAll = new JButton("Enable all");
		enableAll.setToolTipText("Turn on every preset in the selected tab");
		enableAll.addActionListener(e -> onToggleMany.accept(looseKeysInTab(), folderIdsInTab(), true));

		JButton disableAll = new JButton("Disable all");
		disableAll.setToolTipText("Turn off every preset in the selected tab");
		disableAll.addActionListener(e -> onToggleMany.accept(looseKeysInTab(), folderIdsInTab(), false));

		JPanel controls = new JPanel();
		controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));

		searchBar.setIcon(IconTextField.Icon.SEARCH);
		// IconTextField paints no background until its focus/hover listeners
		// first fire; give it one up front like the core plugin panels do
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

		// Search bar across the full top row
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
			export.setToolTipText("Mirror the current emitters, definitions and folders into emitters.json + definitions.json + folders.json");
			export.addActionListener(e -> onExport.run());
			JPanel openRow = new JPanel(new GridLayout(1, 2, 4, 0));
			openRow.add(open);
			openRow.add(export);
			openRow.setAlignmentX(Component.LEFT_ALIGNMENT);
			controls.add(openRow);
			controls.add(Box.createVerticalStrut(6));
		}

		MaterialTabGroup tabGroup = new MaterialTabGroup();
		// Three equal tabs per row, wrapping like the button grid above
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

		// Under the profile list: a link to the hub page's suggestions
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

	/**
	 * Loose (ungrouped) profile keys the current tab shows, for the bulk
	 * buttons. Foldered children are excluded - the folder is bulk-toggled.
	 */
	private Set<String> looseKeysInTab()
	{
		Set<String> keys = new HashSet<>();
		for (Map.Entry<String, EmitterProfile> entry : profiles.entrySet())
		{
			EmitterProfile profile = entry.getValue();
			if (profile.getFolderId() == null && category.matches(profile)
				&& (developerMode || !Category.isWip(profile)))
			{
				keys.add(entry.getKey());
			}
		}
		return keys;
	}

	/**
	 * Folder ids the current tab shows, for the bulk buttons.
	 */
	private Set<String> folderIdsInTab()
	{
		Set<String> ids = new HashSet<>();
		for (ProfileFolder folder : folders.values())
		{
			EmitterProfile rep = representativeOf(folder.getId());
			if (rep != null && category.matches(rep)
				&& (developerMode || !(folder.isWip() || Category.categoryWip(rep))))
			{
				ids.add(folder.getId());
			}
		}
		return ids;
	}

	/**
	 * Update the profile and folder data and re-render. Must be called on the
	 * Swing EDT.
	 *
	 * @param presentSignatures signatures of pieces on the currently worn model
	 */
	void rebuild(Map<String, EmitterProfile> profiles, Map<String, ProfileFolder> folders,
		Set<String> presentSignatures)
	{
		this.profiles = profiles;
		this.folders = folders;
		this.presentSignatures = presentSignatures;
		render();
	}

	/**
	 * Re-render the list through the current category tab and search text.
	 * Suppressed while a drag is in flight so rows aren't rebuilt out from
	 * under the gesture; the deferred render runs when the drag ends.
	 */
	private void render()
	{
		if (dragging)
		{
			pendingRender = true;
			return;
		}
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
		List<Item> items = new ArrayList<>();

		for (Map.Entry<String, EmitterProfile> entry : profiles.entrySet())
		{
			EmitterProfile profile = entry.getValue();
			if (profile.getFolderId() != null || !category.matches(profile)
				|| (!developerMode && Category.isWip(profile)) || !matchesSearch(profile, query))
			{
				continue;
			}
			items.add(Item.profile(entry.getKey(), profile));
		}

		for (ProfileFolder folder : folders.values())
		{
			EmitterProfile rep = representativeOf(folder.getId());
			if (rep == null || !category.matches(rep)
				|| (!developerMode && (folder.isWip() || Category.categoryWip(rep)))
				|| !folderMatchesSearch(folder, query))
			{
				continue;
			}
			items.add(Item.folder(folder));
		}

		Comparator<Item> byName = Comparator.comparing(item -> item.name == null ? "" : item.name.toLowerCase());
		// Developers author folders, so group them ahead of loose profiles;
		// end users see identical rows, so keep them interleaved alphabetically
		items.sort(developerMode
			? Comparator.<Item>comparingInt(item -> item.folder ? 0 : 1).thenComparing(byName)
			: byName);

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

		for (Item item : items)
		{
			profileList.add(item.folder
				? buildFolderRow(item.folderObj, query)
				: buildRow(item.key, item.profile));
		}

		profileList.revalidate();
		profileList.repaint();
	}

	private JPanel buildRow(String profileKey, EmitterProfile profile)
	{
		boolean inFolder = profile.getFolderId() != null;
		// Signature presence only means worn for player profiles; trivial
		// fragment signatures collide across unrelated models
		boolean worn = EmitterProfile.TARGET_PLAYER.equals(profile.getTargetType())
			&& presentSignatures.contains(profile.getSignature());
		String text = profile.getName()
			+ (profile.isProjectileTarget() && developerMode ? " [proj " + profile.getProjectileId() + "]" : "")
			+ (profile.isObjectTarget() && developerMode ? " [obj " + profile.getObjectId() + "]" : "")
			+ (profile.isNpcTarget() && developerMode ? " [npc " + profile.getNpcId() + "]" : "")
			+ (profile.isGraphicTarget() && developerMode ? " [gfx " + profile.getGraphicId() + "]" : "")
			+ (profile.isWeatherTarget() && developerMode ? " [weather]" : "");

		JCheckBox toggle = new JCheckBox(text, profile.isEnabled());
		// Rows truncate in the narrow sidebar; the tooltip carries the full
		// name and tag, plus the worn state where that concept applies
		toggle.setToolTipText(EmitterProfile.TARGET_PLAYER.equals(profile.getTargetType())
			? "<html>" + text + "<br>" + (worn
				? "On the model you are wearing now"
				: "Not on your current model") + "</html>"
			: text);
		toggle.addActionListener(e -> onToggleProfile.accept(profileKey, toggle.isSelected()));

		JPanel row = new JPanel(new BorderLayout());
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.add(toggle, BorderLayout.CENTER);

		// Drop metadata: dropping onto a loose profile groups the two; onto a
		// child joins that child's folder
		row.putClientProperty(DROP_KIND, inFolder ? "child" : "loose");
		row.putClientProperty(DROP_KEY, profileKey);
		row.putClientProperty(DROP_CAT, profile.getTargetType());
		if (inFolder)
		{
			row.putClientProperty(DROP_FOLDER, profile.getFolderId());
		}

		if (developerMode)
		{
			JPanel west = new JPanel();
			west.setLayout(new BoxLayout(west, BoxLayout.X_AXIS));
			west.add(dragHandle(profileKey, profile.getTargetType()));

			// A ship/work-in-progress mark: a red check means shipped, unchecked
			// marks it WIP so it stays saved but vanishes for shipped users
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
			if (inFolder)
			{
				JMenuItem removeFromFolder = new JMenuItem("Remove from folder");
				removeFromFolder.addActionListener(e -> folderActions.removeMember.accept(profileKey));
				menu.add(removeFromFolder);
			}
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

		if (inFolder)
		{
			row.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 0));
		}
		return row;
	}

	/**
	 * A folder row: for shipped users a single enable toggle indistinguishable
	 * from a profile row; for developers a header (WIP mark, enable toggle,
	 * expand, rename, delete, Dissolve) that expands to reveal member rows.
	 */
	private JPanel buildFolderRow(ProfileFolder folder, String query)
	{
		String folderId = folder.getId();
		if (!developerMode)
		{
			String label = folder.getName() + " (" + childrenOf(folderId).size() + ")";
			JCheckBox toggle = new JCheckBox(label, folder.isEnabled());
			toggle.setToolTipText(label);
			toggle.addActionListener(e -> folderActions.toggleEnabled.accept(folderId, toggle.isSelected()));
			JPanel row = new JPanel(new BorderLayout());
			row.setAlignmentX(Component.LEFT_ALIGNMENT);
			row.add(toggle, BorderLayout.CENTER);
			return row;
		}

		EmitterProfile rep = representativeOf(folderId);
		String cat = rep == null ? "" : rep.getTargetType();

		List<Map.Entry<String, EmitterProfile>> children = childrenOf(folderId);
		boolean searching = !query.isEmpty();
		boolean anyChildMatch = false;
		for (Map.Entry<String, EmitterProfile> child : children)
		{
			if (matchesSearch(child.getValue(), query))
			{
				anyChildMatch = true;
				break;
			}
		}
		boolean showChildren = searching ? anyChildMatch : expanded.contains(folderId);

		JPanel header = new JPanel(new BorderLayout());
		header.setAlignmentX(Component.LEFT_ALIGNMENT);
		header.putClientProperty(DROP_KIND, "folder");
		header.putClientProperty(DROP_KEY, folderId);
		header.putClientProperty(DROP_CAT, cat);
		header.putClientProperty(DROP_FOLDER, folderId);

		// WIP mark then enable toggle, matching the order on profile rows so the
		// red marks and preference checks line up into uniform columns
		JPanel west = new JPanel();
		west.setLayout(new BoxLayout(west, BoxLayout.X_AXIS));
		JCheckBox wipMark = new JCheckBox();
		wipMark.setIcon(WIP_ICON);
		wipMark.setSelectedIcon(PUBLISHED_ICON);
		wipMark.setSelected(!folder.isWip());
		wipMark.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6));
		wipMark.setToolTipText("Ship this folder. Uncheck to mark the whole group work-in-progress.");
		wipMark.addActionListener(e -> folderActions.toggleWip.accept(folderId, !wipMark.isSelected()));
		west.add(wipMark);
		JCheckBox enable = new JCheckBox();
		enable.setSelected(folder.isEnabled());
		enable.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6));
		enable.setToolTipText("Folder enabled - gates every member");
		enable.addActionListener(e -> folderActions.toggleEnabled.accept(folderId, enable.isSelected()));
		west.add(enable);
		header.add(west, BorderLayout.WEST);

		JLabel name = new JLabel((showChildren ? "▼ " : "▶ ") + folder.getName()
			+ "  (" + children.size() + ")");
		name.setToolTipText("Click to " + (showChildren ? "collapse" : "expand") + " this folder");
		name.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		name.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (expanded.contains(folderId))
				{
					expanded.remove(folderId);
				}
				else
				{
					expanded.add(folderId);
				}
				render();
			}
		});
		header.add(name, BorderLayout.CENTER);

		JButton rename = new JButton("~");
		rename.setMargin(new Insets(0, 4, 0, 4));
		rename.setToolTipText("Rename this folder");
		rename.addActionListener(e -> folderActions.rename.accept(folderId));
		JButton delete = new JButton("x");
		delete.setMargin(new Insets(0, 4, 0, 4));
		delete.setToolTipText("Dissolve this folder (members return to loose rows)");
		delete.addActionListener(e -> folderActions.dissolve.accept(folderId));
		JPanel buttons = new JPanel();
		buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
		buttons.add(rename);
		buttons.add(delete);
		header.add(buttons, BorderLayout.EAST);

		JPopupMenu menu = new JPopupMenu();
		JMenuItem dissolve = new JMenuItem("Dissolve folder");
		dissolve.addActionListener(e -> folderActions.dissolve.accept(folderId));
		menu.add(dissolve);
		header.setComponentPopupMenu(menu);
		name.setComponentPopupMenu(menu);

		JPanel container = new JPanel();
		container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
		container.setAlignmentX(Component.LEFT_ALIGNMENT);
		container.add(header);
		if (showChildren)
		{
			children.sort(Comparator.comparing(entry ->
				entry.getValue().getName() == null ? "" : entry.getValue().getName().toLowerCase()));
			for (Map.Entry<String, EmitterProfile> child : children)
			{
				if (searching && !matchesSearch(child.getValue(), query))
				{
					continue;
				}
				container.add(buildRow(child.getKey(), child.getValue()));
			}
		}
		return container;
	}

	// --- Folder helpers ------------------------------------------------------

	private List<Map.Entry<String, EmitterProfile>> childrenOf(String folderId)
	{
		List<Map.Entry<String, EmitterProfile>> children = new ArrayList<>();
		for (Map.Entry<String, EmitterProfile> entry : profiles.entrySet())
		{
			if (folderId.equals(entry.getValue().getFolderId()))
			{
				children.add(entry);
			}
		}
		return children;
	}

	@Nullable
	private EmitterProfile representativeOf(String folderId)
	{
		for (EmitterProfile profile : profiles.values())
		{
			if (folderId.equals(profile.getFolderId()))
			{
				return profile;
			}
		}
		return null;
	}

	private boolean folderMatchesSearch(ProfileFolder folder, String query)
	{
		if (query.isEmpty())
		{
			return true;
		}
		if (folder.getName() != null && folder.getName().toLowerCase().contains(query))
		{
			return true;
		}
		for (Map.Entry<String, EmitterProfile> child : childrenOf(folder.getId()))
		{
			if (matchesSearch(child.getValue(), query))
			{
				return true;
			}
		}
		return false;
	}

	// --- Drag and drop -------------------------------------------------------

	private JLabel dragHandle(String key, String targetType)
	{
		JLabel handle = new JLabel("≡");
		handle.setToolTipText("Drag onto another profile or a folder to group them");
		handle.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
		handle.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 6));
		handle.putClientProperty(DRAG_KEY, key);
		handle.putClientProperty(DRAG_CAT, targetType);
		handle.addMouseListener(dragAdapter);
		handle.addMouseMotionListener(dragAdapter);
		return handle;
	}

	@Nullable
	private JComponent dropTargetAt(MouseEvent e)
	{
		Point p = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), profileList);
		Component c = SwingUtilities.getDeepestComponentAt(profileList, p.x, p.y);
		while (c != null && !(c instanceof JComponent && ((JComponent) c).getClientProperty(DROP_KIND) != null))
		{
			c = c.getParent();
		}
		return (JComponent) c;
	}

	private void hoverDropTarget(MouseEvent e)
	{
		JComponent target = dropTargetAt(e);
		highlight(target, target != null && isValidDrop(target));
	}

	private boolean isValidDrop(JComponent target)
	{
		if (dragKey == null || dragCategory == null
			|| !dragCategory.equals(target.getClientProperty(DROP_CAT)))
		{
			return false;
		}
		// Dropping a profile onto itself, or onto its own folder, is a no-op
		if ("loose".equals(target.getClientProperty(DROP_KIND)))
		{
			return !dragKey.equals(target.getClientProperty(DROP_KEY));
		}
		return true;
	}

	private void performDrop(MouseEvent e)
	{
		JComponent target = dropTargetAt(e);
		if (target == null || !isValidDrop(target))
		{
			return;
		}
		String kind = (String) target.getClientProperty(DROP_KIND);
		if ("loose".equals(kind))
		{
			folderActions.create.accept((String) target.getClientProperty(DROP_KEY), dragKey);
		}
		else if ("folder".equals(kind))
		{
			folderActions.addMember.accept((String) target.getClientProperty(DROP_KEY), dragKey);
		}
		else if ("child".equals(kind))
		{
			folderActions.addMember.accept((String) target.getClientProperty(DROP_FOLDER), dragKey);
		}
	}

	private void highlight(@Nullable JComponent target, boolean valid)
	{
		if (target == dropTarget)
		{
			return;
		}
		clearHighlight();
		if (target != null)
		{
			dropTarget = target;
			dropTargetBorder = target.getBorder();
			target.setBorder(BorderFactory.createLineBorder(
				valid ? new Color(80, 180, 80) : new Color(180, 80, 80), 2));
		}
	}

	private void clearHighlight()
	{
		if (dropTarget != null)
		{
			dropTarget.setBorder(dropTargetBorder);
			dropTarget = null;
			dropTargetBorder = null;
		}
	}

	/**
	 * One rendered row: a loose/child profile or a folder, sorted together.
	 */
	private static final class Item
	{
		final String name;
		final String key;
		final boolean folder;
		final EmitterProfile profile;
		final ProfileFolder folderObj;

		private Item(String name, String key, boolean folder, EmitterProfile profile, ProfileFolder folderObj)
		{
			this.name = name;
			this.key = key;
			this.folder = folder;
			this.profile = profile;
			this.folderObj = folderObj;
		}

		static Item profile(String key, EmitterProfile profile)
		{
			return new Item(profile.getName(), key, false, profile, null);
		}

		static Item folder(ProfileFolder folder)
		{
			return new Item(folder.getName(), folder.getId(), true, null, folder);
		}
	}

	/**
	 * A 14px checkbox glyph: a grey box, plus a red check when published.
	 */
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
