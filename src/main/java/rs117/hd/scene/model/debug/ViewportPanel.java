package rs117.hd.scene.model.debug;

import java.awt.Dimension;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Path2D;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.swing.JPanel;
import rs117.hd.scene.model.ModelSnapshot;

public class ViewportPanel extends JPanel {
	public enum PickAction {
		ADD,
		REMOVE,
		CHANGE
	}

	public enum PickTarget {
		FACE,
		POINT
	}

	public static final class Pick {
		public final PickTarget target;
		public final int globalIndex;
		public final float bary0;
		public final float bary1;
		public final float bary2;

		private Pick(PickTarget target, int globalIndex, float bary0, float bary1, float bary2) {
			this.target = target;
			this.globalIndex = globalIndex;
			this.bary0 = bary0;
			this.bary1 = bary1;
			this.bary2 = bary2;
		}

		public static Pick face(int globalFace, float bary0, float bary1, float bary2) {
			return new Pick(PickTarget.FACE, globalFace, bary0, bary1, bary2);
		}

		public static Pick point(int globalVertex) {
			return new Pick(PickTarget.POINT, globalVertex, 1f, 0f, 0f);
		}
	}

	public enum EditMode {
		PLACE,
		DELETE
	}

	private static final Color BG_TOP = new Color(48, 48, 52);
	private static final Color BG_BOTTOM = new Color(22, 22, 26);
	private static final Color GRID_MAJOR = new Color(70, 70, 78);
	private static final Color GRID_MINOR = new Color(42, 42, 48);
	private static final Color COLOR_EDGE = new Color(130, 135, 145);
	private static final Color COLOR_EDGE_DIM = new Color(55, 58, 66);
	private static final Color COLOR_VERTEX = new Color(160, 165, 175);
	private static final Color COLOR_HOVER = new Color(66, 180, 255);
	private static final Color COLOR_DELETE = new Color(255, 90, 90);
	private static final Color COLOR_SELECTED = new Color(255, 160, 48);
	private static final Color COLOR_HUD = new Color(210, 210, 215);
	private static final Color COLOR_HUD_DIM = new Color(130, 130, 138);
	private static final Color AXIS_X = new Color(220, 70, 70);
	private static final Color AXIS_Y = new Color(110, 210, 90);
	private static final Color AXIS_Z = new Color(80, 140, 240);
	private static final int HIT_RADIUS = 10;
	private static final int GIZMO_SIZE = 72;
	private static final int TOOLBAR_TOP = 12;

	@FunctionalInterface
	public interface TriConsumer<A, B, C> {
		void accept(A a, B b, C c);
	}

	private final BiConsumer<Pick, PickAction> onPickClicked;
	private final TriConsumer<Set<Integer>, Boolean, PickTarget> onBoxSelected;
	@Nullable
	private Consumer<Pick> onHoverChanged;
	@Nullable
	private Consumer<EditMode> onEditModeChanged;
	@Nullable
	private Consumer<PickTarget> onPickTargetChanged;

	private ModelSnapshot snapshot;
	private int pieceFilter = -1;
	private EditMode editMode = EditMode.PLACE;
	private PickTarget pickTarget = PickTarget.FACE;
	private boolean shaded = true;
	private boolean showGrid = false;
	private boolean showGizmo = true;

	private double yaw = 0.65;
	private double pitch = 0.35;
	private double zoom = 1.0;
	private double panX;
	private double panY;

	private float centerX, centerY, centerZ;
	private float fitRadius = 1;

	private int[] screenX;
	private int[] screenY;
	private float[] screenZ;
	private boolean[] vertexVisible;
	@Nullable
	private float[] faceShade;

	@Nullable
	private Pick hoverPick;
	private Set<Integer> selectedFaces = new HashSet<>();
	private Set<Integer> selectedVertices = new HashSet<>();
	private boolean labelAll;

	@Nullable
	private float[] overrideX;
	@Nullable
	private float[] overrideY;
	@Nullable
	private float[] overrideZ;

	private int lastDragX, lastDragY;
	private boolean dragged;
	private boolean orbitDrag;
	private boolean panDrag;

	private boolean boxSelecting;
	private boolean boxRemove;
	private int boxStartX, boxStartY, boxEndX, boxEndY;

	@Nullable
	private JPanel floatingToolbar;

	public ViewportPanel(
		BiConsumer<Pick, PickAction> onPickClicked,
		TriConsumer<Set<Integer>, Boolean, PickTarget> onBoxSelected
	) {
		this.onPickClicked = onPickClicked;
		this.onBoxSelected = onBoxSelected;
		setLayout(null);
		setBackground(BG_BOTTOM);

		var mouse = new java.awt.event.MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				requestFocusInWindow();
				Integer axis = hitGizmoAxis(e.getX(), e.getY());
				if (axis != null) {
					snapGizmoAxis(axis);
					return;
				}

				lastDragX = e.getX();
				lastDragY = e.getY();
				dragged = false;
				orbitDrag = false;
				panDrag = false;

				if (e.getButton() == MouseEvent.BUTTON2 || (e.getButton() == MouseEvent.BUTTON1 && e.isAltDown())) {
					if (e.isShiftDown())
						panDrag = true;
					else
						orbitDrag = true;
				} else if (editMode == EditMode.DELETE && e.getButton() == MouseEvent.BUTTON1 && !e.isAltDown()) {
					boxSelecting = true;
					boxRemove = true;
					boxStartX = boxEndX = e.getX();
					boxStartY = boxEndY = e.getY();
				} else if (editMode == EditMode.PLACE && e.isShiftDown() && e.getButton() == MouseEvent.BUTTON1 && !e.isAltDown()) {
					boxSelecting = true;
					boxRemove = false;
					boxStartX = boxEndX = e.getX();
					boxStartY = boxEndY = e.getY();
				} else if (e.getButton() == MouseEvent.BUTTON3 || (e.getButton() == MouseEvent.BUTTON2 && e.isShiftDown())) {
					panDrag = true;
				} else if (editMode == EditMode.PLACE && e.getButton() == MouseEvent.BUTTON1 && !e.isAltDown() && !e.isShiftDown()) {
					orbitDrag = true;
				}
			}

			@Override
			public void mouseDragged(MouseEvent e) {
				int dx = e.getX() - lastDragX;
				int dy = e.getY() - lastDragY;
				if (Math.abs(dx) + Math.abs(dy) > 2)
					dragged = true;

				if (orbitDrag) {
					yaw += dx * 0.012;
					pitch = clampPitch(pitch + dy * 0.012);
				} else if (panDrag) {
					panX += dx;
					panY += dy;
				} else if (boxSelecting) {
					boxEndX = e.getX();
					boxEndY = e.getY();
				}
				lastDragX = e.getX();
				lastDragY = e.getY();
				repaint();
			}

			@Override
			public void mouseReleased(MouseEvent e) {
				if (orbitDrag) {
					boolean pick = editMode == EditMode.PLACE
						&& e.getButton() == MouseEvent.BUTTON1
						&& !e.isAltDown()
						&& !e.isShiftDown()
						&& !dragged
						&& hoverPick != null;
					orbitDrag = false;
					if (pick) {
						PickAction action = e.getClickCount() >= 2 ? PickAction.CHANGE : PickAction.ADD;
						onPickClicked.accept(hoverPick, action);
					}
					return;
				}
				if (panDrag) {
					panDrag = false;
					return;
				}
				if (boxSelecting) {
					finishBoxSelection();
					return;
				}
			}

			@Override
			public void mouseMoved(MouseEvent e) {
				Pick previous = hoverPick;
				hoverPick = findPickAt(e.getX(), e.getY());
				if (!picksEqual(previous, hoverPick)) {
					if (onHoverChanged != null)
						onHoverChanged.accept(hoverPick);
					repaint();
				}
			}

			@Override
			public void mouseWheelMoved(MouseWheelEvent e) {
				double factor = Math.pow(1.1, -e.getWheelRotation());
				zoom = Math.max(0.15, Math.min(30.0, zoom * factor));
				repaint();
			}
		};
		addMouseListener(mouse);
		addMouseMotionListener(mouse);
		addMouseWheelListener(mouse);

		setFocusable(true);
		addKeyListener(new java.awt.event.KeyAdapter() {
			@Override
			public void keyPressed(KeyEvent e) {
				switch (e.getKeyCode()) {
					case KeyEvent.VK_F:
						resetView();
						break;
					case KeyEvent.VK_1:
					case KeyEvent.VK_NUMPAD1:
						setViewFront();
						break;
					case KeyEvent.VK_3:
					case KeyEvent.VK_NUMPAD3:
						setViewRight();
						break;
					case KeyEvent.VK_7:
					case KeyEvent.VK_NUMPAD7:
						setViewTop();
						break;
					case KeyEvent.VK_9:
					case KeyEvent.VK_NUMPAD9:
						setViewBottom();
						break;
					case KeyEvent.VK_P:
						setEditMode(EditMode.PLACE);
						break;
					case KeyEvent.VK_X:
						setEditMode(EditMode.DELETE);
						break;
					case KeyEvent.VK_V:
						setPickTarget(PickTarget.POINT);
						break;
				}
			}
		});
	}

	public void setFloatingToolbar(JPanel toolbar) {
		if (floatingToolbar != null)
			remove(floatingToolbar);
		floatingToolbar = toolbar;
		if (toolbar != null) {
			toolbar.setVisible(true);
			add(toolbar);
			layoutToolbar();
		}
		repaint();
	}

	@Override
	public void doLayout() {
		layoutToolbar();
	}

	@Override
	public Dimension getPreferredSize() {
		return new Dimension(640, 480);
	}

	@Override
	public Dimension getMinimumSize() {
		return new Dimension(320, 240);
	}

	private void layoutToolbar() {
		if (floatingToolbar == null)
			return;
		floatingToolbar.validate();
		Dimension toolbarSize = floatingToolbar.getPreferredSize();
		if (toolbarSize.width <= 0 || toolbarSize.height <= 0)
			toolbarSize = new Dimension(520, 34);
		int toolbarX = Math.max(8, (getWidth() - toolbarSize.width) / 2);
		floatingToolbar.setBounds(toolbarX, TOOLBAR_TOP, toolbarSize.width, toolbarSize.height);
	}

	public void setOnHoverChanged(Consumer<Pick> onHoverChanged) {
		this.onHoverChanged = onHoverChanged;
	}

	public PickTarget getPickTarget() {
		return pickTarget;
	}

	public void setPickTarget(PickTarget pickTarget) {
		if (this.pickTarget == pickTarget)
			return;
		this.pickTarget = pickTarget;
		hoverPick = null;
		if (onHoverChanged != null)
			onHoverChanged.accept(null);
		if (onPickTargetChanged != null)
			onPickTargetChanged.accept(pickTarget);
		repaint();
	}

	public void setOnPickTargetChanged(Consumer<PickTarget> onPickTargetChanged) {
		this.onPickTargetChanged = onPickTargetChanged;
	}

	public void setOnEditModeChanged(Consumer<EditMode> onEditModeChanged) {
		this.onEditModeChanged = onEditModeChanged;
	}

	public EditMode getEditMode() {
		return editMode;
	}

	public void setEditMode(EditMode editMode) {
		if (this.editMode == editMode)
			return;
		this.editMode = editMode;
		hoverPick = null;
		if (onEditModeChanged != null)
			onEditModeChanged.accept(editMode);
		repaint();
	}

	public void setShaded(boolean shaded) {
		this.shaded = shaded;
		repaint();
	}

	public void setShowGrid(boolean showGrid) {
		this.showGrid = showGrid;
		repaint();
	}

	public void setShowGizmo(boolean showGizmo) {
		this.showGizmo = showGizmo;
		repaint();
	}

	public void resetView() {
		if (snapshot != null)
			fit();
		else {
			yaw = 0.65;
			pitch = 0.35;
			zoom = 1.0;
			panX = panY = 0;
		}
		repaint();
	}

	public void setViewFront() {
		yaw = 0;
		pitch = 0;
		repaint();
	}

	public void setViewRight() {
		yaw = Math.PI / 2;
		pitch = 0;
		repaint();
	}

	public void setViewTop() {
		yaw = 0;
		pitch = Math.PI / 2 - 0.001;
		repaint();
	}

	public void setViewBottom() {
		yaw = 0;
		pitch = -Math.PI / 2 + 0.001;
		repaint();
	}

	public void setViewBack() {
		yaw = Math.PI;
		pitch = 0;
		repaint();
	}

	public void setViewLeft() {
		yaw = -Math.PI / 2;
		pitch = 0;
		repaint();
	}

	public void setSnapshot(ModelSnapshot snapshot) {
		this.snapshot = snapshot;
		this.pieceFilter = -1;
		this.hoverPick = null;
		screenX = new int[snapshot.getVertexCount()];
		screenY = new int[snapshot.getVertexCount()];
		screenZ = new float[snapshot.getVertexCount()];
		vertexVisible = new boolean[snapshot.getVertexCount()];
		fit();
		repaint();
	}

	public void clearSnapshot() {
		this.snapshot = null;
		this.pieceFilter = -1;
		this.hoverPick = null;
		this.screenX = null;
		this.screenY = null;
		this.screenZ = null;
		this.vertexVisible = null;
		this.faceShade = null;
		this.overrideX = null;
		this.overrideY = null;
		this.overrideZ = null;
		repaint();
	}

	public void setPieceFilter(int pieceIndex) {
		this.pieceFilter = pieceIndex;
		this.hoverPick = null;
		fit();
		repaint();
	}

	public void setSelectedFaces(Set<Integer> faces) {
		this.selectedFaces = new HashSet<>(faces);
		repaint();
	}

	public void setSelectedVertices(Set<Integer> vertices) {
		this.selectedVertices = new HashSet<>(vertices);
		repaint();
	}

	public void setLabelAll(boolean labelAll) {
		this.labelAll = labelAll;
		repaint();
	}

	public void setPositionOverride(@Nullable float[] xs, @Nullable float[] ys, @Nullable float[] zs) {
		if (snapshot != null && xs != null && xs.length < snapshot.getVertexCount())
			return;
		overrideX = xs;
		overrideY = ys;
		overrideZ = zs;
		repaint();
	}

	private static double clampPitch(double pitch) {
		return Math.max(-Math.PI / 2 + 0.01, Math.min(Math.PI / 2 - 0.01, pitch));
	}

	private void fit() {
		if (snapshot == null)
			return;

		updateVisibility();
		panX = panY = 0;

		float sx = 0, sy = 0, sz = 0;
		int n = 0;
		for (int v = 0; v < snapshot.getVertexCount(); v++) {
			if (!vertexVisible[v])
				continue;
			sx += vertexX(v);
			sy += vertexY(v);
			sz += vertexZ(v);
			n++;
		}
		if (n == 0)
			return;
		centerX = sx / n;
		centerY = sy / n;
		centerZ = sz / n;

		float maxDistSq = 1;
		for (int v = 0; v < snapshot.getVertexCount(); v++) {
			if (!vertexVisible[v])
				continue;
			float dx = vertexX(v) - centerX;
			float dy = vertexY(v) - centerY;
			float dz = vertexZ(v) - centerZ;
			float distSq = dx * dx + dy * dy + dz * dz;
			if (distSq > maxDistSq)
				maxDistSq = distSq;
		}
		fitRadius = (float) Math.sqrt(maxDistSq);
		zoom = 1.0;
	}

	private float vertexX(int v) {
		return overrideX != null ? overrideX[v] : snapshot.getVerticesX()[v];
	}

	private float vertexY(int v) {
		return overrideY != null ? overrideY[v] : snapshot.getVerticesY()[v];
	}

	private float vertexZ(int v) {
		return overrideZ != null ? overrideZ[v] : snapshot.getVerticesZ()[v];
	}

	private void updateVisibility() {
		if (snapshot == null || vertexVisible == null)
			return;
		if (pieceFilter < 0 || pieceFilter >= snapshot.getPieces().size()) {
			for (int v = 0; v < vertexVisible.length; v++)
				vertexVisible[v] = true;
			return;
		}
		for (int v = 0; v < vertexVisible.length; v++)
			vertexVisible[v] = false;
		for (int v : snapshot.getPieces().get(pieceFilter).getVertices())
			vertexVisible[v] = true;
	}

	private void finishBoxSelection() {
		boxSelecting = false;
		int x0 = Math.min(boxStartX, boxEndX);
		int x1 = Math.max(boxStartX, boxEndX);
		int y0 = Math.min(boxStartY, boxEndY);
		int y1 = Math.max(boxStartY, boxEndY);
		repaint();

		if (snapshot == null)
			return;

		if (x1 - x0 < 4 && y1 - y0 < 4) {
			if (boxRemove && hoverPick != null)
				onPickClicked.accept(hoverPick, PickAction.REMOVE);
			return;
		}

		Set<Integer> inside = new HashSet<>();
		if (pickTarget == PickTarget.FACE) {
			int[] f1 = snapshot.getFaceIndices1();
			int[] f2 = snapshot.getFaceIndices2();
			int[] f3 = snapshot.getFaceIndices3();
			for (int f : visibleFaces()) {
				int a = f1[f], b = f2[f], c = f3[f];
				int cx = (screenX[a] + screenX[b] + screenX[c]) / 3;
				int cy = (screenY[a] + screenY[b] + screenY[c]) / 3;
				if (cx >= x0 && cx <= x1 && cy >= y0 && cy <= y1)
					inside.add(f);
			}
		} else {
			for (int v = 0; v < snapshot.getVertexCount(); v++) {
				if (vertexVisible[v]
					&& screenX[v] >= x0 && screenX[v] <= x1
					&& screenY[v] >= y0 && screenY[v] <= y1)
					inside.add(v);
			}
		}
		if (!inside.isEmpty())
			onBoxSelected.accept(inside, !boxRemove, pickTarget);
	}

	@Nullable
	private Pick findPickAt(int x, int y) {
		if (pickTarget == PickTarget.FACE)
			return findFaceAt(x, y);
		int vertex = findVertexIndexAt(x, y);
		return vertex < 0 ? null : Pick.point(vertex);
	}

	private int findVertexIndexAt(int x, int y) {
		if (snapshot == null || screenX == null)
			return -1;
		int best = -1;
		int bestDistSq = HIT_RADIUS * HIT_RADIUS;
		for (int v = 0; v < snapshot.getVertexCount(); v++) {
			if (!vertexVisible[v])
				continue;
			int dx = screenX[v] - x;
			int dy = screenY[v] - y;
			int distSq = dx * dx + dy * dy;
			if (distSq < bestDistSq) {
				bestDistSq = distSq;
				best = v;
			}
		}
		return best;
	}

	private static boolean picksEqual(@Nullable Pick a, @Nullable Pick b) {
		if (a == null || b == null)
			return a == b;
		return a.target == b.target && a.globalIndex == b.globalIndex;
	}

	private int[] visibleFaces() {
		if (pieceFilter >= 0 && pieceFilter < snapshot.getPieces().size())
			return snapshot.getPieces().get(pieceFilter).getFaces();
		int[] all = new int[snapshot.getFaceCount()];
		for (int f = 0; f < all.length; f++)
			all[f] = f;
		return all;
	}

	@Nullable
	private Pick findFaceAt(int x, int y) {
		if (snapshot == null || screenX == null || screenZ == null)
			return null;

		int[] f1 = snapshot.getFaceIndices1();
		int[] f2 = snapshot.getFaceIndices2();
		int[] f3 = snapshot.getFaceIndices3();
		int bestFace = -1;
		float bestDepth = Float.NEGATIVE_INFINITY;
		float bestB0 = 0, bestB1 = 0, bestB2 = 0;

		for (int f : visibleFaces()) {
			int a = f1[f], b = f2[f], c = f3[f];
			if (!vertexVisible[a] || !vertexVisible[b] || !vertexVisible[c])
				continue;
			float[] bary = screenBarycentric(
				x, y,
				screenX[a], screenY[a],
				screenX[b], screenY[b],
				screenX[c], screenY[c]
			);
			if (bary == null)
				continue;
			float depth = (screenZ[a] + screenZ[b] + screenZ[c]) / 3f;
			if (depth > bestDepth) {
				bestDepth = depth;
				bestFace = f;
				bestB0 = bary[0];
				bestB1 = bary[1];
				bestB2 = bary[2];
			}
		}
		return bestFace < 0 ? null : Pick.face(bestFace, bestB0, bestB1, bestB2);
	}

	@Nullable
	private static float[] screenBarycentric(
		int px,
		int py,
		int x0,
		int y0,
		int x1,
		int y1,
		int x2,
		int y2
	) {
		float denom = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2);
		if (Math.abs(denom) < 1e-4f)
			return null;
		float w0 = ((y1 - y2) * (px - x2) + (x2 - x1) * (py - y2)) / denom;
		float w1 = ((y2 - y0) * (px - x2) + (x0 - x2) * (py - y2)) / denom;
		float w2 = 1f - w0 - w1;
		if (w0 < -0.001f || w1 < -0.001f || w2 < -0.001f)
			return null;
		return new float[] { w0, w1, w2 };
	}

	@Override
	protected void paintComponent(Graphics g) {
		super.paintComponent(g);
		Graphics2D g2 = (Graphics2D) g;
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		g2.setPaint(new GradientPaint(0, 0, BG_TOP, 0, getHeight(), BG_BOTTOM));
		g2.fillRect(0, 0, getWidth(), getHeight());

		if (snapshot == null) {
			drawHudMessage(g2, "No model loaded — Refresh or pick from the list.");
			return;
		}

		project();
		if (showGrid)
			drawGrid(g2);
		drawWorldAxes(g2);

		if (shaded)
			drawShadedFaces(g2);
		drawWireframe(g2);
		drawVertices(g2);
		drawSelection(g2);

		if (boxSelecting)
			drawBoxSelect(g2);
		if (showGizmo)
			drawOrientationGizmo(g2);
		drawHud(g2);
	}

	private void drawHudMessage(Graphics2D g2, String message) {
		g2.setColor(COLOR_HUD_DIM);
		g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 13f));
		g2.drawString(message, 16, 28);
	}

	private void drawGrid(Graphics2D g2) {
		float step = Math.max(1f, fitRadius / 5f);
		float extent = fitRadius * 2.5f;
		int lines = (int) (extent / step) + 2;

		for (int i = -lines; i <= lines; i++) {
			float offset = i * step;
			boolean major = i % 5 == 0;
			g2.setColor(major ? GRID_MAJOR : GRID_MINOR);
			g2.setStroke(new BasicStroke(major ? 1f : 0.5f));
			drawGridLine(g2, centerX - extent, centerY, centerZ + offset, centerX + extent, centerY, centerZ + offset);
			drawGridLine(g2, centerX + offset, centerY, centerZ - extent, centerX + offset, centerY, centerZ + extent);
		}
	}

	private void drawGridLine(Graphics2D g2, float x0, float y0, float z0, float x1, float y1, float z1) {
		int[] p0 = projectPoint(x0, y0, z0);
		int[] p1 = projectPoint(x1, y1, z1);
		g2.drawLine(p0[0], p0[1], p1[0], p1[1]);
	}

	private void drawWorldAxes(Graphics2D g2) {
		float len = fitRadius * 0.35f;
		drawAxisLine(g2, centerX, centerY, centerZ, centerX + len, centerY, centerZ, AXIS_X);
		drawAxisLine(g2, centerX, centerY, centerZ, centerX, centerY + len, centerZ, AXIS_Y);
		drawAxisLine(g2, centerX, centerY, centerZ, centerX, centerY, centerZ + len, AXIS_Z);
	}

	private void drawAxisLine(Graphics2D g2, float x0, float y0, float z0, float x1, float y1, float z1, Color color) {
		int[] p0 = projectPoint(x0, y0, z0);
		int[] p1 = projectPoint(x1, y1, z1);
		g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 180));
		g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g2.drawLine(p0[0], p0[1], p1[0], p1[1]);
	}

	private void drawShadedFaces(Graphics2D g2) {
		computeFaceShades();
		int[] f1 = snapshot.getFaceIndices1();
		int[] f2 = snapshot.getFaceIndices2();
		int[] f3 = snapshot.getFaceIndices3();
		for (int f = 0; f < snapshot.getFaceCount(); f++) {
			int a = f1[f], b = f2[f], c = f3[f];
			if (!vertexVisible[a] || !vertexVisible[b] || !vertexVisible[c])
				continue;
			float shade = faceShade[f];
			int base = (int) (38 + shade * 52);
			g2.setColor(new Color(base, base + 2, base + 6));
			Path2D tri = new Path2D.Float();
			tri.moveTo(screenX[a], screenY[a]);
			tri.lineTo(screenX[b], screenY[b]);
			tri.lineTo(screenX[c], screenY[c]);
			tri.closePath();
			g2.fill(tri);
		}
	}

	private void computeFaceShades() {
		if (faceShade == null || faceShade.length != snapshot.getFaceCount())
			faceShade = new float[snapshot.getFaceCount()];

		double cosYaw = Math.cos(yaw), sinYaw = Math.sin(yaw);
		double cosPitch = Math.cos(pitch), sinPitch = Math.sin(pitch);
		double lx = 0.35, ly = 0.65, lz = 0.45;
		double len = Math.sqrt(lx * lx + ly * ly + lz * lz);
		lx /= len;
		ly /= len;
		lz /= len;

		int[] f1 = snapshot.getFaceIndices1();
		int[] f2 = snapshot.getFaceIndices2();
		int[] f3 = snapshot.getFaceIndices3();
		for (int f = 0; f < snapshot.getFaceCount(); f++) {
			int a = f1[f], b = f2[f], c = f3[f];
			float ax = vertexX(a), ay = vertexY(a), az = vertexZ(a);
			float bx = vertexX(b), by = vertexY(b), bz = vertexZ(b);
			float cx = vertexX(c), cy = vertexY(c), cz = vertexZ(c);
			float nx = (by - ay) * (cz - az) - (bz - az) * (cy - ay);
			float ny = (bz - az) * (cx - ax) - (bx - ax) * (cz - az);
			float nz = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
			double[] rn = rotateNormal(nx, ny, nz, cosYaw, sinYaw, cosPitch, sinPitch);
			double dot = rn[0] * lx + rn[1] * ly + rn[2] * lz;
			faceShade[f] = (float) Math.max(0.08, Math.min(1.0, dot));
		}
	}

	private static double[] rotateNormal(float nx, float ny, float nz, double cosYaw, double sinYaw, double cosPitch, double sinPitch) {
		double rx = nx * cosYaw + nz * sinYaw;
		double rz = -nx * sinYaw + nz * cosYaw;
		double ry = ny * cosPitch - rz * sinPitch;
		rz = ny * sinPitch + rz * cosPitch;
		double len = Math.sqrt(rx * rx + ry * ry + rz * rz);
		if (len < 1e-6)
			return new double[] { 0, 1, 0 };
		return new double[] { rx / len, ry / len, rz / len };
	}

	private void drawWireframe(Graphics2D g2) {
		int[] f1 = snapshot.getFaceIndices1();
		int[] f2 = snapshot.getFaceIndices2();
		int[] f3 = snapshot.getFaceIndices3();
		g2.setStroke(new BasicStroke(1f));
		for (int f = 0; f < snapshot.getFaceCount(); f++) {
			int a = f1[f], b = f2[f], c = f3[f];
			if (!vertexVisible[a])
				continue;
			float shade = faceShade != null ? faceShade[f] : 0.5f;
			Color edge = blend(COLOR_EDGE_DIM, COLOR_EDGE, shade);
			g2.setColor(edge);
			g2.drawLine(screenX[a], screenY[a], screenX[b], screenY[b]);
			g2.drawLine(screenX[b], screenY[b], screenX[c], screenY[c]);
			g2.drawLine(screenX[c], screenY[c], screenX[a], screenY[a]);
		}
	}

	private static Color blend(Color a, Color b, float t) {
		t = Math.max(0, Math.min(1, t));
		int r = (int) (a.getRed() + (b.getRed() - a.getRed()) * t);
		int g = (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t);
		int bl = (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t);
		return new Color(r, g, bl);
	}

	private void drawVertices(Graphics2D g2) {
		g2.setColor(COLOR_VERTEX);
		for (int v = 0; v < snapshot.getVertexCount(); v++) {
			if (vertexVisible[v])
				g2.fillRect(screenX[v] - 1, screenY[v] - 1, 2, 2);
		}
	}

	private void drawSelection(Graphics2D g2) {
		int[] f1 = snapshot.getFaceIndices1();
		int[] f2 = snapshot.getFaceIndices2();
		int[] f3 = snapshot.getFaceIndices3();
		for (int f : selectedFaces) {
			if (f < 0 || f >= snapshot.getFaceCount())
				continue;
			int a = f1[f], b = f2[f], c = f3[f];
			if (!vertexVisible[a] || !vertexVisible[b] || !vertexVisible[c])
				continue;
			drawMarkedFace(g2, f, a, b, c, COLOR_SELECTED, labelAll);
		}
		for (int v : selectedVertices) {
			if (v >= 0 && v < snapshot.getVertexCount() && vertexVisible[v])
				drawMarkedVertex(g2, v, COLOR_SELECTED, labelAll);
		}

		if (hoverPick != null) {
			Color hoverColor = editMode == EditMode.DELETE ? COLOR_DELETE : COLOR_HOVER;
			if (hoverPick.target == PickTarget.FACE) {
				int f = hoverPick.globalIndex;
				if (!selectedFaces.contains(f) && f >= 0 && f < snapshot.getFaceCount()) {
					int a = f1[f], b = f2[f], c = f3[f];
					if (vertexVisible[a] && vertexVisible[b] && vertexVisible[c])
						drawMarkedFace(g2, f, a, b, c, hoverColor, true);
				}
			} else {
				int v = hoverPick.globalIndex;
				if (!selectedVertices.contains(v) && v >= 0 && v < snapshot.getVertexCount() && vertexVisible[v])
					drawMarkedVertex(g2, v, hoverColor, true);
			}
		}
	}

	private void drawMarkedVertex(Graphics2D g2, int v, Color color, boolean label) {
		g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 220));
		g2.fillOval(screenX[v] - 4, screenY[v] - 4, 9, 9);
		g2.setColor(color);
		g2.setStroke(new BasicStroke(1.5f));
		g2.drawOval(screenX[v] - 7, screenY[v] - 7, 15, 15);
		if (label) {
			g2.setFont(g2.getFont().deriveFont(Font.BOLD, 11f));
			g2.drawString("v" + v, screenX[v] + 8, screenY[v] - 4);
		}
	}

	private void drawMarkedFace(Graphics2D g2, int face, int a, int b, int c, Color color, boolean label) {
		Path2D tri = new Path2D.Float();
		tri.moveTo(screenX[a], screenY[a]);
		tri.lineTo(screenX[b], screenY[b]);
		tri.lineTo(screenX[c], screenY[c]);
		tri.closePath();
		g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 70));
		g2.fill(tri);
		g2.setColor(color);
		g2.setStroke(new BasicStroke(2f));
		g2.draw(tri);
		if (label) {
			int cx = (screenX[a] + screenX[b] + screenX[c]) / 3;
			int cy = (screenY[a] + screenY[b] + screenY[c]) / 3;
			g2.setFont(g2.getFont().deriveFont(Font.BOLD, 11f));
			g2.drawString("f" + face, cx + 6, cy - 4);
		}
	}

	private void drawBoxSelect(Graphics2D g2) {
		int x0 = Math.min(boxStartX, boxEndX);
		int y0 = Math.min(boxStartY, boxEndY);
		int w = Math.abs(boxEndX - boxStartX);
		int h = Math.abs(boxEndY - boxStartY);
		Color boxColor = boxRemove ? COLOR_DELETE : COLOR_HOVER;
		g2.setColor(new Color(boxColor.getRed(), boxColor.getGreen(), boxColor.getBlue(), 35));
		g2.fillRect(x0, y0, w, h);
		g2.setColor(boxColor);
		g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER, 1f, new float[] { 4, 3 }, 0));
		g2.drawRect(x0, y0, w, h);
	}

	private void drawOrientationGizmo(Graphics2D g2) {
		int cx = getWidth() - GIZMO_SIZE / 2 - 12;
		int cy = GIZMO_SIZE / 2 + 12;
		double axisLen = GIZMO_SIZE * 0.32;

		double[][] axes = {
			{ 1, 0, 0, AXIS_X.getRed(), AXIS_X.getGreen(), AXIS_X.getBlue() },
			{ 0, 1, 0, AXIS_Y.getRed(), AXIS_Y.getGreen(), AXIS_Y.getBlue() },
			{ 0, 0, 1, AXIS_Z.getRed(), AXIS_Z.getGreen(), AXIS_Z.getBlue() }
		};

		int[][] projected = new int[3][3];
		for (int i = 0; i < 3; i++) {
			double[] p = rotatePoint(axes[i][0], axes[i][1], axes[i][2]);
			projected[i][0] = cx + (int) (p[0] * axisLen);
			projected[i][1] = cy - (int) (p[1] * axisLen);
			projected[i][2] = (int) (p[2] * 100);
		}

		Integer[] order = { 0, 1, 2 };
		java.util.Arrays.sort(order, (a, b) -> Integer.compare(projected[a][2], projected[b][2]));

		g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		for (int idx : order) {
			g2.setColor(new Color((int) axes[idx][3], (int) axes[idx][4], (int) axes[idx][5]));
			g2.drawLine(cx, cy, projected[idx][0], projected[idx][1]);
		}

		g2.setFont(g2.getFont().deriveFont(Font.BOLD, 10f));
		for (int i = 0; i < 3; i++) {
			String label = i == 0 ? "X" : i == 1 ? "Y" : "Z";
			g2.setColor(new Color((int) axes[i][3], (int) axes[i][4], (int) axes[i][5]));
			g2.drawString(label, projected[i][0] + (projected[i][0] >= cx ? 4 : -10), projected[i][1] + (projected[i][1] >= cy ? 12 : -4));
		}

		g2.setColor(new Color(255, 255, 255, 40));
		g2.fillOval(cx - 3, cy - 3, 7, 7);
	}

	@Nullable
	private Integer hitGizmoAxis(int mx, int my) {
		if (!showGizmo)
			return null;

		int cx = getWidth() - GIZMO_SIZE / 2 - 12;
		int cy = GIZMO_SIZE / 2 + 12;
		if (mx < cx - GIZMO_SIZE || mx > cx + GIZMO_SIZE || my < cy - GIZMO_SIZE || my > cy + GIZMO_SIZE)
			return null;

		double axisLen = GIZMO_SIZE * 0.32;
		double[][] axes = { { 1, 0, 0 }, { 0, 1, 0 }, { 0, 0, 1 } };
		for (int i = 0; i < 3; i++) {
			double[] p = rotatePoint(axes[i][0], axes[i][1], axes[i][2]);
			int px = cx + (int) (p[0] * axisLen);
			int py = cy - (int) (p[1] * axisLen);
			if (distSq(mx, my, px, py) < 196)
				return i;
			int nx = cx - (int) (p[0] * axisLen);
			int ny = cy + (int) (p[1] * axisLen);
			if (distSq(mx, my, nx, ny) < 196)
				return i + 3;
		}
		return null;
	}

	private static int distSq(int x0, int y0, int x1, int y1) {
		int dx = x0 - x1;
		int dy = y0 - y1;
		return dx * dx + dy * dy;
	}

	private void snapGizmoAxis(int axis) {
		switch (axis) {
			case 0:
				setViewRight();
				break;
			case 1:
				setViewTop();
				break;
			case 2:
				setViewFront();
				break;
			case 3:
				setViewLeft();
				break;
			case 4:
				setViewBottom();
				break;
			default:
				setViewBack();
				break;
		}
	}

	private void drawHud(Graphics2D g2) {
		g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 11f));
		FontMetrics fm = g2.getFontMetrics();
		int line = fm.getAscent() + 8;

		g2.setColor(new Color(0, 0, 0, 120));
		g2.fillRoundRect(8, 6, 230, 68, 8, 8);
		g2.setColor(COLOR_HUD);
		g2.drawString(snapshot.getVertexCount() + " verts  ·  " + snapshot.getFaceCount() + " faces", 16, line);
		line += fm.getHeight();
		g2.setColor(COLOR_HUD_DIM);
		String piece = pieceFilter >= 0 ? "piece " + (pieceFilter + 1) : "all pieces";
		String modeLabel = editMode == EditMode.DELETE ? "delete mode" : "place mode";
		String pickLabel = pickTarget == PickTarget.FACE ? "face pick" : "point pick";
		g2.drawString(piece + "  ·  " + (selectedFaces.size() + selectedVertices.size()) + " lights  ·  " + modeLabel + "  ·  " + pickLabel, 16, line);
		line += fm.getHeight();
		g2.drawString(String.format("yaw %.0f°  pitch %.0f°  zoom %.1fx", Math.toDegrees(yaw), Math.toDegrees(pitch), zoom), 16, line);

		String help = editMode == EditMode.DELETE
			? "Delete: LMB click or drag area · RMB pan · MMB orbit · P place"
			: (pickTarget == PickTarget.FACE
				? "Face: LMB click triangle · Shift+drag area · MMB orbit · V point pick · X delete"
				: "Point: LMB click vertex · Shift+drag area · MMB orbit · Face button for triangles · X delete");
		int helpW = fm.stringWidth(help);
		g2.setColor(new Color(0, 0, 0, 100));
		g2.fillRoundRect(getWidth() - helpW - 20, getHeight() - 24, helpW + 12, 18, 6, 6);
		g2.setColor(COLOR_HUD_DIM);
		g2.drawString(help, getWidth() - helpW - 14, getHeight() - 10);
	}

	private int[] projectPoint(float x, float y, float z) {
		double[] p = rotatePoint(x - centerX, y - centerY, z - centerZ);
		double scale = scale();
		int halfW = getWidth() / 2;
		int halfH = getHeight() / 2;
		return new int[] {
			halfW + (int) panX + (int) (p[0] * scale),
			halfH + (int) panY + (int) (p[1] * scale)
		};
	}

	private double[] rotatePoint(double dx, double dy, double dz) {
		double cosYaw = Math.cos(yaw), sinYaw = Math.sin(yaw);
		double cosPitch = Math.cos(pitch), sinPitch = Math.sin(pitch);
		double rx = dx * cosYaw + dz * sinYaw;
		double rz = -dx * sinYaw + dz * cosYaw;
		double ry = dy * cosPitch - rz * sinPitch;
		rz = dy * sinPitch + rz * cosPitch;
		return new double[] { rx, ry, rz };
	}

	private double scale() {
		return Math.min(getWidth(), getHeight()) * 0.42 / fitRadius * zoom;
	}

	private void project() {
		double cosYaw = Math.cos(yaw), sinYaw = Math.sin(yaw);
		double cosPitch = Math.cos(pitch), sinPitch = Math.sin(pitch);
		double s = scale();
		int halfW = getWidth() / 2;
		int halfH = getHeight() / 2;

		screenZ = new float[snapshot.getVertexCount()];
		for (int v = 0; v < snapshot.getVertexCount(); v++) {
			double dx = vertexX(v) - centerX;
			double dy = vertexY(v) - centerY;
			double dz = vertexZ(v) - centerZ;
			double rx = dx * cosYaw + dz * sinYaw;
			double rz = -dx * sinYaw + dz * cosYaw;
			double ry = dy * cosPitch - rz * sinPitch;
			rz = dy * sinPitch + rz * cosPitch;
			screenX[v] = halfW + (int) panX + (int) (rx * s);
			screenY[v] = halfH + (int) panY + (int) (ry * s);
			screenZ[v] = (float) rz;
		}
	}
}
