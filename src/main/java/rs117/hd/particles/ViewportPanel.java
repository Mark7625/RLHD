package rs117.hd.particles;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nullable;
import javax.swing.JPanel;
import rs117.hd.utils.ColorUtils;
import rs117.hd.utils.HDUtils;

class ViewportPanel extends JPanel
{
	enum InteractionMode
	{
		PLACE("Place"),
		REMOVE("Remove");

		final String label;

		InteractionMode(String label)
		{
			this.label = label;
		}
	}

	enum PickMode
	{
		VERTEX("verts"),
		FACE("faces");

		final String label;

		PickMode(String label)
		{
			this.label = label;
		}
	}

	@FunctionalInterface
	interface SelectionHandler
	{
		void accept(Set<Integer> indices, boolean add, boolean faces);
	}

	enum CameraPreset
	{
		TOP(0.5, Math.PI / 2 - 0.08),
		SIDE(Math.PI / 2, 0.18),
		BEHIND(Math.PI, 0.18);

		final double yaw;
		final double pitch;

		CameraPreset(double yaw, double pitch)
		{
			this.yaw = yaw;
			this.pitch = pitch;
		}
	}

	private static final Color COLOR_BACKGROUND = new Color(27, 27, 31);
	private static final Color COLOR_EDGE = new Color(62, 62, 72);
	private static final Color COLOR_VERTEX = new Color(170, 170, 180);
	private static final Color COLOR_HOVER = new Color(0, 220, 255);
	private static final Color COLOR_SELECTED = new Color(255, 152, 31);
	private static final Color COLOR_FACE_SELECTED = new Color(255, 152, 31, 90);
	private static final Color COLOR_FACE_HOVER = new Color(0, 220, 255, 70);
	private static final Color COLOR_TEXT = new Color(200, 200, 205);
	private static final int HIT_RADIUS = 10;

	private static final int TEXTURED_FACE_HSL = 90;

	private final SelectionHandler onBoxSelected;

	private InteractionMode interactionMode = InteractionMode.PLACE;
	private PickMode pickMode = PickMode.VERTEX;

	private String emptyMessage = "No model snapshot. Log in and press Refresh.";
	private ModelSnapshot snapshot;
	private int pieceFilter = -1;

	private float[] overrideX;
	private float[] overrideY;
	private float[] overrideZ;

	private double yaw = 0.5;
	private double pitch = 0.25;
	private double zoom = 1.0;

	private float centerX, centerY, centerZ;
	private float fitRadius = 1;

	private int[] screenX;
	private int[] screenY;
	private float[] viewDepth;
	private float[] viewRotX;
	private float[] viewRotY;
	private boolean[] vertexVisible;

	private int hoverVertex = -1;
	private int hoverFace = -1;
	private Set<Integer> selectedVertices = new HashSet<>();
	private Set<Integer> selectedFaces = new HashSet<>();
	private boolean labelAll;
	private boolean showFaceColors = false;
	private boolean showWireframe = true;
	private boolean showVertices = true;

	private int lastDragX, lastDragY;
	private boolean dragged;

	private boolean boxSelecting;
	private boolean boxRemove;
	private int boxStartX, boxStartY, boxEndX, boxEndY;

	private BufferedImage colorBuffer;
	private int[] colorBufferPixels;
	private float[] depthBuffer;

	ViewportPanel(SelectionHandler onBoxSelected)
	{
		this.onBoxSelected = onBoxSelected;
		setBackground(COLOR_BACKGROUND);
		setFocusable(true);

		MouseAdapter mouse = new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				requestFocusInWindow();
				lastDragX = e.getX();
				lastDragY = e.getY();
				dragged = false;
				boxSelecting = e.isShiftDown() || e.isControlDown();
				boxRemove = e.isControlDown();
				boxStartX = boxEndX = e.getX();
				boxStartY = boxEndY = e.getY();
			}

			@Override
			public void mouseDragged(MouseEvent e)
			{
				int dx = e.getX() - lastDragX;
				int dy = e.getY() - lastDragY;
				if (Math.abs(dx) + Math.abs(dy) > 2)
				{
					dragged = true;
				}
				if (boxSelecting)
				{
					boxEndX = e.getX();
					boxEndY = e.getY();
				}
				else
				{
					yaw += dx * 0.012;
					pitch = Math.max(-Math.PI / 2, Math.min(Math.PI / 2, pitch + dy * 0.012));
				}
				lastDragX = e.getX();
				lastDragY = e.getY();
				repaint();
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				if (boxSelecting)
				{
					finishBoxSelection();
					return;
				}
				if (!dragged)
				{
					boolean add = interactionMode == InteractionMode.PLACE;
					if (pickMode == PickMode.FACE)
					{
						if (hoverFace != -1)
						{
							onBoxSelected.accept(Set.of(hoverFace), add, true);
						}
					}
					else if (hoverVertex != -1)
					{
						onBoxSelected.accept(Set.of(hoverVertex), add, false);
					}
				}
			}

			@Override
			public void mouseMoved(MouseEvent e)
			{
				if (pickMode == PickMode.FACE)
				{
					int previous = hoverFace;
					hoverFace = findFaceAt(e.getX(), e.getY());
					hoverVertex = -1;
					if (hoverFace != previous)
					{
						repaint();
					}
				}
				else
				{
					int previous = hoverVertex;
					hoverVertex = findVertexAt(e.getX(), e.getY());
					hoverFace = -1;
					if (hoverVertex != previous)
					{
						repaint();
					}
				}
			}

			@Override
			public void mouseWheelMoved(MouseWheelEvent e)
			{
				zoom = Math.max(0.2, Math.min(25.0, zoom * Math.pow(1.12, -e.getWheelRotation())));
				repaint();
			}
		};
		addMouseListener(mouse);
		addMouseMotionListener(mouse);
		addMouseWheelListener(mouse);
	}

	void setSnapshot(@Nullable ModelSnapshot snapshot)
	{
		this.snapshot = snapshot;
		if (snapshot == null)
		{
			repaint();
			return;
		}
		this.pieceFilter = -1;
		this.hoverVertex = -1;
		this.hoverFace = -1;
		this.overrideX = null;
		this.overrideY = null;
		this.overrideZ = null;
		screenX = new int[snapshot.getVertexCount()];
		screenY = new int[snapshot.getVertexCount()];
		vertexVisible = new boolean[snapshot.getVertexCount()];
		fit();
		repaint();
	}

	void setEmptyMessage(String message)
	{
		emptyMessage = message == null || message.isEmpty()
			? "No model snapshot. Log in and press Refresh."
			: message;
		repaint();
	}

	void setPieceFilter(int pieceIndex)
	{
		this.pieceFilter = pieceIndex;
		this.hoverVertex = -1;
		this.hoverFace = -1;
		fit();
		repaint();
	}

	void setSelectedVertices(Set<Integer> vertices)
	{
		this.selectedVertices = new HashSet<>(vertices);
		repaint();
	}

	void setSelectedFaces(Set<Integer> faces)
	{
		this.selectedFaces = new HashSet<>(faces);
		repaint();
	}

	void setLabelAll(boolean labelAll)
	{
		this.labelAll = labelAll;
		repaint();
	}

	boolean isShowFaceColors()
	{
		return showFaceColors;
	}

	void setShowFaceColors(boolean showFaceColors)
	{
		if (this.showFaceColors != showFaceColors)
		{
			this.showFaceColors = showFaceColors;
			repaint();
		}
	}

	boolean isShowWireframe()
	{
		return showWireframe;
	}

	void setShowWireframe(boolean showWireframe)
	{
		if (this.showWireframe != showWireframe)
		{
			this.showWireframe = showWireframe;
			repaint();
		}
	}

	boolean isShowVertices()
	{
		return showVertices;
	}

	void setShowVertices(boolean showVertices)
	{
		if (this.showVertices != showVertices)
		{
			this.showVertices = showVertices;
			repaint();
		}
	}

	InteractionMode getInteractionMode()
	{
		return interactionMode;
	}

	void setInteractionMode(InteractionMode mode)
	{
		if (interactionMode != mode)
		{
			interactionMode = mode;
			repaint();
		}
	}

	PickMode getPickMode()
	{
		return pickMode;
	}

	void setPickMode(PickMode mode)
	{
		if (pickMode != mode)
		{
			pickMode = mode;
			hoverVertex = -1;
			hoverFace = -1;
			repaint();
		}
	}

	void setCameraPreset(CameraPreset preset)
	{
		yaw = preset.yaw;
		pitch = preset.pitch;
		repaint();
	}

	void setPositionOverride(float[] xs, float[] ys, float[] zs)
	{

		if (snapshot != null && xs != null && xs.length < snapshot.getVertexCount())
		{
			return;
		}
		overrideX = xs;
		overrideY = ys;
		overrideZ = zs;
		repaint();
	}

	private void fit()
	{
		if (snapshot == null)
		{
			return;
		}

		updateVisibility();

		float sx = 0, sy = 0, sz = 0;
		int n = 0;
		for (int v = 0; v < snapshot.getVertexCount(); v++)
		{
			if (!vertexVisible[v])
			{
				continue;
			}
			sx += snapshot.getVerticesX()[v];
			sy += snapshot.getVerticesY()[v];
			sz += snapshot.getVerticesZ()[v];
			n++;
		}
		if (n == 0)
		{
			return;
		}
		centerX = sx / n;
		centerY = sy / n;
		centerZ = sz / n;

		float maxDistSq = 1;
		for (int v = 0; v < snapshot.getVertexCount(); v++)
		{
			if (!vertexVisible[v])
			{
				continue;
			}
			float dx = snapshot.getVerticesX()[v] - centerX;
			float dy = snapshot.getVerticesY()[v] - centerY;
			float dz = snapshot.getVerticesZ()[v] - centerZ;
			float distSq = dx * dx + dy * dy + dz * dz;
			if (distSq > maxDistSq)
			{
				maxDistSq = distSq;
			}
		}
		fitRadius = (float) Math.sqrt(maxDistSq);
		zoom = 1.0;
	}

	private void updateVisibility()
	{
		if (pieceFilter < 0 || pieceFilter >= snapshot.getPieces().size())
		{
			for (int v = 0; v < vertexVisible.length; v++)
			{
				vertexVisible[v] = true;
			}
			return;
		}

		for (int v = 0; v < vertexVisible.length; v++)
		{
			vertexVisible[v] = false;
		}
		for (int v : snapshot.getPieces().get(pieceFilter).getVertices())
		{
			vertexVisible[v] = true;
		}
	}

	private void finishBoxSelection()
	{
		boxSelecting = false;
		int x0 = Math.min(boxStartX, boxEndX);
		int x1 = Math.max(boxStartX, boxEndX);
		int y0 = Math.min(boxStartY, boxEndY);
		int y1 = Math.max(boxStartY, boxEndY);
		repaint();

		if (snapshot == null || (x1 - x0 < 4 && y1 - y0 < 4))
		{
			return;
		}

		Set<Integer> inside = new HashSet<>();
		if (pickMode == PickMode.FACE)
		{
			int[] f1 = snapshot.getFaceIndices1();
			int[] f2 = snapshot.getFaceIndices2();
			int[] f3 = snapshot.getFaceIndices3();
			for (int f = 0; f < snapshot.getFaceCount(); f++)
			{
				int a = f1[f], b = f2[f], c = f3[f];
				if (!vertexVisible[a])
				{
					continue;
				}
				int cx = (screenX[a] + screenX[b] + screenX[c]) / 3;
				int cy = (screenY[a] + screenY[b] + screenY[c]) / 3;
				if (cx >= x0 && cx <= x1 && cy >= y0 && cy <= y1)
				{
					inside.add(f);
				}
			}
			if (!inside.isEmpty())
			{
				onBoxSelected.accept(inside, !boxRemove, true);
			}
			return;
		}

		for (int v = 0; v < snapshot.getVertexCount(); v++)
		{
			if (vertexVisible[v]
				&& screenX[v] >= x0 && screenX[v] <= x1
				&& screenY[v] >= y0 && screenY[v] <= y1)
			{
				inside.add(v);
			}
		}
		if (!inside.isEmpty())
		{
			onBoxSelected.accept(inside, !boxRemove, false);
		}
	}

	private int findVertexAt(int x, int y)
	{
		if (snapshot == null)
		{
			return -1;
		}
		int best = -1;
		int bestDistSq = HIT_RADIUS * HIT_RADIUS;
		for (int v = 0; v < snapshot.getVertexCount(); v++)
		{
			if (!vertexVisible[v])
			{
				continue;
			}
			int dx = screenX[v] - x;
			int dy = screenY[v] - y;
			int distSq = dx * dx + dy * dy;
			if (distSq < bestDistSq)
			{
				bestDistSq = distSq;
				best = v;
			}
		}
		return best;
	}

	private int findFaceAt(int x, int y)
	{
		if (snapshot == null)
		{
			return -1;
		}
		int[] f1 = snapshot.getFaceIndices1();
		int[] f2 = snapshot.getFaceIndices2();
		int[] f3 = snapshot.getFaceIndices3();
		int best = -1;
		float bestDepth = Float.NEGATIVE_INFINITY;
		for (int f = 0; f < snapshot.getFaceCount(); f++)
		{
			int a = f1[f], b = f2[f], c = f3[f];
			if (!vertexVisible[a])
			{
				continue;
			}
			if (!pointInTriangle(x, y, screenX[a], screenY[a], screenX[b], screenY[b], screenX[c], screenY[c]))
			{
				continue;
			}
			float depth = (viewDepth[a] + viewDepth[b] + viewDepth[c]) / 3f;
			if (depth > bestDepth)
			{
				bestDepth = depth;
				best = f;
			}
		}
		return best;
	}

	private static boolean pointInTriangle(int px, int py,
		int x0, int y0, int x1, int y1, int x2, int y2)
	{
		float area = edge(x1, y1, x2, y2, x0, y0);
		if (area == 0)
		{
			return false;
		}
		float w0 = edge(x1, y1, x2, y2, px, py) / area;
		float w1 = edge(x2, y2, x0, y0, px, py) / area;
		float w2 = edge(x0, y0, x1, y1, px, py) / area;
		return w0 >= 0 && w1 >= 0 && w2 >= 0;
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		super.paintComponent(g);
		Graphics2D g2 = (Graphics2D) g;

		if (snapshot == null)
		{
			g2.setColor(COLOR_TEXT);
			g2.drawString(emptyMessage, 20, 30);
			return;
		}

		project();

		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		if (showFaceColors)
		{
			paintFaceColors(g2);
		}

		g2.setStroke(new BasicStroke(1f));

		int[] f1 = snapshot.getFaceIndices1();
		int[] f2 = snapshot.getFaceIndices2();
		int[] f3 = snapshot.getFaceIndices3();

		if (showWireframe)
		{
			g2.setColor(COLOR_EDGE);
			for (int f = 0; f < snapshot.getFaceCount(); f++)
			{
				int a = f1[f], b = f2[f], c = f3[f];
				if (!vertexVisible[a])
				{
					continue;
				}
				g2.drawLine(screenX[a], screenY[a], screenX[b], screenY[b]);
				g2.drawLine(screenX[b], screenY[b], screenX[c], screenY[c]);
				g2.drawLine(screenX[c], screenY[c], screenX[a], screenY[a]);
			}
		}

		paintFaceHighlights(g2, f1, f2, f3);

		if (showVertices)
		{
			g2.setColor(COLOR_VERTEX);
			for (int v = 0; v < snapshot.getVertexCount(); v++)
			{
				if (vertexVisible[v])
				{
					g2.fillRect(screenX[v] - 1, screenY[v] - 1, 3, 3);
				}
			}
		}

		for (int v : selectedVertices)
		{
			if (v >= 0 && v < snapshot.getVertexCount() && vertexVisible[v])
			{
				drawMarkedVertex(g2, v, COLOR_SELECTED, labelAll);
			}
		}
		if (pickMode == PickMode.VERTEX && hoverVertex != -1 && !selectedVertices.contains(hoverVertex))
		{
			drawMarkedVertex(g2, hoverVertex, COLOR_HOVER, true);
		}

		if (boxSelecting)
		{
			int x0 = Math.min(boxStartX, boxEndX);
			int y0 = Math.min(boxStartY, boxEndY);
			int w = Math.abs(boxEndX - boxStartX);
			int h = Math.abs(boxEndY - boxStartY);
			Color boxColor = boxRemove ? COLOR_SELECTED : COLOR_HOVER;
			g2.setColor(new Color(boxColor.getRed(), boxColor.getGreen(), boxColor.getBlue(), 40));
			g2.fillRect(x0, y0, w, h);
			g2.setColor(boxColor);
			g2.drawRect(x0, y0, w, h);
		}

		g2.setColor(COLOR_TEXT);
		g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 12f));
		int emitterCount = selectedVertices.size() + selectedFaces.size();
		String status = snapshot.getVertexCount() + " vertices, " + snapshot.getFaceCount() + " faces"
			+ (pieceFilter >= 0 ? "  |  piece " + (pieceFilter + 1) : "")
			+ (showFaceColors ? "  |  colors" : "")
			+ (!showWireframe ? "  |  no wire" : "")
			+ (!showVertices ? "  |  no verts" : "")
			+ "  |  emitters: " + emitterCount
			+ "  |  " + pickMode.label
			+ "  |  " + interactionMode.label.toLowerCase()
			+ " (P/D)  |  F=faces T=verts  |  shift+drag add, ctrl+drag remove  |  drag orbits";
		g2.drawString(status, 10, getHeight() - 10);
	}

	private void paintFaceHighlights(Graphics2D g2, int[] f1, int[] f2, int[] f3)
	{
		for (int f : selectedFaces)
		{
			if (f >= 0 && f < snapshot.getFaceCount())
			{
				fillFace(g2, f1[f], f2[f], f3[f], COLOR_FACE_SELECTED);
			}
		}
		if (pickMode == PickMode.FACE && hoverFace != -1 && !selectedFaces.contains(hoverFace))
		{
			fillFace(g2, f1[hoverFace], f2[hoverFace], f3[hoverFace], COLOR_FACE_HOVER);
			g2.setColor(COLOR_HOVER);
			g2.setStroke(new BasicStroke(1.5f));
			int a = f1[hoverFace], b = f2[hoverFace], c = f3[hoverFace];
			g2.drawLine(screenX[a], screenY[a], screenX[b], screenY[b]);
			g2.drawLine(screenX[b], screenY[b], screenX[c], screenY[c]);
			g2.drawLine(screenX[c], screenY[c], screenX[a], screenY[a]);
			g2.setStroke(new BasicStroke(1f));
			g2.drawString("f" + hoverFace,
				(screenX[a] + screenX[b] + screenX[c]) / 3 + 6,
				(screenY[a] + screenY[b] + screenY[c]) / 3 - 4);
		}
	}

	private void fillFace(Graphics2D g2, int a, int b, int c, Color fill)
	{
		if (!vertexVisible[a])
		{
			return;
		}
		g2.setColor(fill);
		g2.fillPolygon(
			new int[]{screenX[a], screenX[b], screenX[c]},
			new int[]{screenY[a], screenY[b], screenY[c]},
			3);
	}

	private void paintFaceColors(Graphics2D g2)
	{
		if (snapshot.getFaceColors1() == null || snapshot.getFaceColors1().length < snapshot.getFaceCount())
		{
			return;
		}

		int w = getWidth();
		int h = getHeight();
		if (w <= 0 || h <= 0)
		{
			return;
		}
		ensureColorBuffer(w, h);
		int background = COLOR_BACKGROUND.getRGB();
		Arrays.fill(colorBufferPixels, background);
		Arrays.fill(depthBuffer, Float.NEGATIVE_INFINITY);

		int[] f1 = snapshot.getFaceIndices1();
		int[] f2 = snapshot.getFaceIndices2();
		int[] f3 = snapshot.getFaceIndices3();
		int[] cornerArgb = new int[3];

		for (int face = 0; face < snapshot.getFaceCount(); face++)
		{
			int a = f1[face], b = f2[face], c = f3[face];
			if (!vertexVisible[a] || !resolveFaceCornerColors(face, cornerArgb))
			{
				continue;
			}

			fillGouraudTriangle(
				colorBufferPixels, depthBuffer, w, h,
				screenX[a], screenY[a], viewDepth[a], cornerArgb[0],
				screenX[b], screenY[b], viewDepth[b], cornerArgb[1],
				screenX[c], screenY[c], viewDepth[c], cornerArgb[2]);
		}

		g2.drawImage(colorBuffer, 0, 0, null);
	}

	private void ensureColorBuffer(int w, int h)
	{
		int pixels = w * h;
		if (colorBuffer == null || colorBuffer.getWidth() != w || colorBuffer.getHeight() != h)
		{
			colorBuffer = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
			colorBufferPixels = ((DataBufferInt) colorBuffer.getRaster().getDataBuffer()).getData();
			depthBuffer = new float[pixels];
		}
	}

	private boolean resolveFaceCornerColors(int face, int[] outArgb)
	{
		int alpha = faceAlpha(face);
		if (alpha <= 0)
		{
			return false;
		}

		short[] textures = snapshot.getFaceTextures();
		if (textures != null && face < textures.length && textures[face] != -1)
		{
			int argb = hslToArgb(TEXTURED_FACE_HSL, alpha);
			outArgb[0] = outArgb[1] = outArgb[2] = argb;
			return argb != 0;
		}

		short[] unlit = snapshot.getUnlitFaceColors();
		if (unlit != null && face < unlit.length)
		{
			int argb = hslToArgb(unlit[face] & 0xFFFF, alpha);
			outArgb[0] = outArgb[1] = outArgb[2] = argb;
			return argb != 0;
		}

		int[] colors1 = snapshot.getFaceColors1();
		int[] colors2 = snapshot.getFaceColors2();
		int[] colors3 = snapshot.getFaceColors3();
		if (colors1 == null || face >= colors1.length)
		{
			return false;
		}

		int hsl1 = colors1[face];
		int hsl2 = colors2 != null && face < colors2.length ? colors2[face] : hsl1;
		int hsl3 = colors3 != null && face < colors3.length ? colors3[face] : hsl1;
		if (colors3 != null && face < colors3.length && colors3[face] == -1)
		{
			hsl2 = hsl3 = hsl1;
		}

		outArgb[0] = hslToArgb(hsl1, alpha);
		outArgb[1] = hslToArgb(hsl2, alpha);
		outArgb[2] = hslToArgb(hsl3, alpha);
		return outArgb[0] != 0 || outArgb[1] != 0 || outArgb[2] != 0;
	}

	private int faceAlpha(int face)
	{
		byte[] transparencies = snapshot.getFaceTransparencies();
		if (transparencies == null || face >= transparencies.length)
		{
			return 255;
		}
		int alpha = 255 - (transparencies[face] & 0xFF);

		return alpha <= 0 ? 0 : 255;
	}

	private static int hslToArgb(int hsl, int alpha)
	{
		if (hsl == HDUtils.HIDDEN_HSL || alpha <= 0)
		{
			return 0;
		}
		float[] srgb = ColorUtils.packedHslToSrgb(hsl & 0xFFFF);
		int rgb = ColorUtils.packSrgb(srgb);
		return (alpha << 24) | (rgb & 0xFFFFFF);
	}

	private static void fillGouraudTriangle(
		int[] pixels, float[] depths, int width, int height,
		int x0, int y0, float z0, int argb0,
		int x1, int y1, float z1, int argb1,
		int x2, int y2, float z2, int argb2)
	{
		float area = edge(x1, y1, x2, y2, x0, y0);
		if (area == 0)
		{
			return;
		}

		if (area < 0)
		{
			int sx = x1; x1 = x2; x2 = sx;
			int sy = y1; y1 = y2; y2 = sy;
			float sz = z1; z1 = z2; z2 = sz;
			int sa = argb1; argb1 = argb2; argb2 = sa;
			area = -area;
		}

		int minX = Math.max(0, Math.min(x0, Math.min(x1, x2)));
		int maxX = Math.min(width - 1, Math.max(x0, Math.max(x1, x2)));
		int minY = Math.max(0, Math.min(y0, Math.min(y1, y2)));
		int maxY = Math.min(height - 1, Math.max(y0, Math.max(y1, y2)));
		if (minX > maxX || minY > maxY)
		{
			return;
		}

		int a0 = argb0 >>> 24, r0 = (argb0 >> 16) & 0xFF, g0 = (argb0 >> 8) & 0xFF, b0 = argb0 & 0xFF;
		int a1 = argb1 >>> 24, r1 = (argb1 >> 16) & 0xFF, g1 = (argb1 >> 8) & 0xFF, b1 = argb1 & 0xFF;
		int a2 = argb2 >>> 24, r2 = (argb2 >> 16) & 0xFF, g2 = (argb2 >> 8) & 0xFF, b2 = argb2 & 0xFF;

		for (int y = minY; y <= maxY; y++)
		{
			int row = y * width;
			for (int x = minX; x <= maxX; x++)
			{
				float w0 = edge(x1, y1, x2, y2, x, y) / area;
				float w1 = edge(x2, y2, x0, y0, x, y) / area;
				float w2 = edge(x0, y0, x1, y1, x, y) / area;
				if (w0 < 0 || w1 < 0 || w2 < 0)
				{
					continue;
				}
				float depth = w0 * z0 + w1 * z1 + w2 * z2;
				int idx = row + x;
				if (depth <= depths[idx])
				{
					continue;
				}
				depths[idx] = depth;
				int r = clamp255((int) (w0 * r0 + w1 * r1 + w2 * r2));
				int g = clamp255((int) (w0 * g0 + w1 * g1 + w2 * g2));
				int b = clamp255((int) (w0 * b0 + w1 * b1 + w2 * b2));
				pixels[idx] = 0xFF000000 | (r << 16) | (g << 8) | b;
			}
		}
	}

	private static float edge(int x0, int y0, int x1, int y1, int x, int y)
	{
		return (x - x0) * (float) (y1 - y0) - (y - y0) * (float) (x1 - x0);
	}

	private static int clamp255(int v)
	{
		return Math.max(0, Math.min(255, v));
	}

	private void drawMarkedVertex(Graphics2D g2, int v, Color color, boolean label)
	{
		g2.setColor(color);
		g2.fillOval(screenX[v] - 3, screenY[v] - 3, 7, 7);
		g2.drawOval(screenX[v] - 6, screenY[v] - 6, 13, 13);
		if (label)
		{
			g2.drawString("v" + v, screenX[v] + 9, screenY[v] - 6);
		}
	}

	private void project()
	{
		double cosYaw = Math.cos(yaw), sinYaw = Math.sin(yaw);
		double cosPitch = Math.cos(pitch), sinPitch = Math.sin(pitch);
		double scale = Math.min(getWidth(), getHeight()) * 0.45 / fitRadius * zoom;
		int halfW = getWidth() / 2;
		int halfH = getHeight() / 2;

		if (viewDepth == null || viewDepth.length != snapshot.getVertexCount())
		{
			viewDepth = new float[snapshot.getVertexCount()];
			viewRotX = new float[snapshot.getVertexCount()];
			viewRotY = new float[snapshot.getVertexCount()];
		}

		float[] vx = overrideX != null ? overrideX : snapshot.getVerticesX();
		float[] vy = overrideY != null ? overrideY : snapshot.getVerticesY();
		float[] vz = overrideZ != null ? overrideZ : snapshot.getVerticesZ();
		for (int v = 0; v < snapshot.getVertexCount(); v++)
		{
			double dx = vx[v] - centerX;
			double dy = vy[v] - centerY;
			double dz = vz[v] - centerZ;

			double rx = dx * cosYaw + dz * sinYaw;
			double rz = -dx * sinYaw + dz * cosYaw;
			double ry = dy * cosPitch - rz * sinPitch;
			viewRotX[v] = (float) rx;
			viewRotY[v] = (float) ry;
			viewDepth[v] = (float) (dy * sinPitch + rz * cosPitch);

			screenX[v] = halfW + (int) (rx * scale);
			screenY[v] = halfH + (int) (ry * scale);
		}
	}
}
