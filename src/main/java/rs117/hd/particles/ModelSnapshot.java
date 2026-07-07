package rs117.hd.particles;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import lombok.Getter;
import net.runelite.api.Model;

/**
 * An immutable copy of a posed model's geometry, decomposed into connected
 * mesh pieces (groups of faces that share edges). Vertex indices match the
 * live model, so a vertex picked here can be used directly as an emitter anchor.
 *
 * Each piece carries a topology signature that is stable across model
 * recomposition: when equipment changes, a piece's global vertex indices shift
 * but its internal structure does not, so the signature (face wiring expressed
 * in piece-local vertex ranks) re-identifies the same piece in the new
 * composite. Emitters are stored per signature with piece-local indices and
 * mapped back to global indices against whatever model is currently worn.
 */
@Getter
class ModelSnapshot
{
	private final int vertexCount;
	private final int faceCount;
	private final float[] verticesX;
	private final float[] verticesY;
	private final float[] verticesZ;
	private final int[] faceIndices1;
	private final int[] faceIndices2;
	private final int[] faceIndices3;
	/** Per-face HSL at corner vertices (see {@link Model#getFaceColors1()} etc.). */
	private final int[] faceColors1;
	private final int[] faceColors2;
	private final int[] faceColors3;
	/** Base recolour before lighting, when the model exposes it. */
	private final short[] unlitFaceColors;
	/** Vanilla texture id per face, or null. */
	private final short[] faceTextures;
	/** Per-face transparency bytes, or null when the model has none. */
	private final byte[] faceTransparencies;
	private final List<Piece> pieces;

	/**
	 * Piece index per vertex, -1 for vertices not referenced by any face.
	 */
	private final int[] vertexToPiece;

	@Getter
	static class Piece
	{
		private final String signature;
		private final int[] faces;
		/**
		 * Global vertex indices in first-appearance order over the piece's face
		 * stream; the position of a vertex in this array is its piece-local
		 * index. Appearance order (unlike global index order) is unaffected by
		 * the engine welding a vertex into another model's index range, so
		 * local indices and signatures survive e.g. head models touching the
		 * cape when a face-revealing helmet is worn.
		 */
		private final int[] vertices;
		/**
		 * Signatures this piece would produce under the three reversed-winding
		 * visit orders. A mirrored placement of the same mesh (some wall
		 * orientations flip the model, reversing triangle winding) hashes as
		 * one of these, so a profile authored on either handedness matches
		 * both. Field-confirmed 2026-07: same-ID wall torches produced 9v8f
		 * pieces with different hashes per orientation.
		 */
		private final String[] mirrorSignatures;
		/**
		 * First-appearance vertex orders for each mirror variant, so a
		 * profile matched through a mirror signature maps its piece-local
		 * indices onto the mirrored counterpart vertices.
		 */
		private final int[][] mirrorVertices;
		// Lookup: sorted globals with their parallel local indices
		private final int[] sortedVertices;
		private final int[] sortedToLocal;

		Piece(String signature, int[] faces, int[] vertices, String[] mirrorSignatures, int[][] mirrorVertices)
		{
			this.signature = signature;
			this.faces = faces;
			this.vertices = vertices;
			this.mirrorSignatures = mirrorSignatures;
			this.mirrorVertices = mirrorVertices;

			sortedVertices = vertices.clone();
			Arrays.sort(sortedVertices);
			sortedToLocal = new int[vertices.length];
			for (int local = 0; local < vertices.length; local++)
			{
				sortedToLocal[Arrays.binarySearch(sortedVertices, vertices[local])] = local;
			}
		}

		/**
		 * @return the piece-local index of a global vertex, or -1
		 */
		int localIndexOf(int globalVertex)
		{
			int pos = Arrays.binarySearch(sortedVertices, globalVertex);
			return pos < 0 ? -1 : sortedToLocal[pos];
		}

		/**
		 * Does this signature identify the piece, in either handedness?
		 */
		boolean matchesSignature(String sig)
		{
			if (signature.equals(sig))
			{
				return true;
			}
			for (String mirror : mirrorSignatures)
			{
				if (mirror.equals(sig))
				{
					return true;
				}
			}
			return false;
		}

		/**
		 * The local-to-global vertex order under whichever labeling produced
		 * this signature; the direct order when it isn't a mirror match.
		 */
		int[] verticesFor(String sig)
		{
			if (signature.equals(sig))
			{
				return vertices;
			}
			for (int m = 0; m < mirrorSignatures.length; m++)
			{
				if (mirrorSignatures[m].equals(sig))
				{
					return mirrorVertices[m];
				}
			}
			return vertices;
		}
	}

	/**
	 * Copy geometry from a model. Face colour arrays are omitted; use
	 * {@link #captureForViewer(Model)} when the dev viewer needs them.
	 */
	static ModelSnapshot capture(Model model)
	{
		return capture(model, false);
	}

	/** Geometry plus per-face colour data for the dev viewer. */
	static ModelSnapshot captureForViewer(Model model)
	{
		return capture(model, true);
	}

	private static ModelSnapshot capture(Model model, boolean includeFaceData)
	{
		return new ModelSnapshot(
			model.getVerticesCount(),
			model.getFaceCount(),
			model.getVerticesX().clone(),
			model.getVerticesY().clone(),
			model.getVerticesZ().clone(),
			model.getFaceIndices1().clone(),
			model.getFaceIndices2().clone(),
			model.getFaceIndices3().clone(),
			includeFaceData ? cloneOrNull(model.getFaceColors1()) : null,
			includeFaceData ? cloneOrNull(model.getFaceColors2()) : null,
			includeFaceData ? cloneOrNull(model.getFaceColors3()) : null,
			includeFaceData ? cloneOrNull(model.getUnlitFaceColors()) : null,
			includeFaceData ? cloneOrNull(model.getFaceTextures()) : null,
			includeFaceData ? cloneOrNull(model.getFaceTransparencies()) : null);
	}

	@Nullable
	private static int[] cloneOrNull(int[] src)
	{
		return src != null ? src.clone() : null;
	}

	@Nullable
	private static short[] cloneOrNull(short[] src)
	{
		return src != null ? src.clone() : null;
	}

	@Nullable
	private static byte[] cloneOrNull(byte[] src)
	{
		return src != null ? src.clone() : null;
	}

	private ModelSnapshot(int vertexCount, int faceCount,
		float[] verticesX, float[] verticesY, float[] verticesZ,
		int[] faceIndices1, int[] faceIndices2, int[] faceIndices3,
		int[] faceColors1, int[] faceColors2, int[] faceColors3,
		short[] unlitFaceColors, short[] faceTextures, byte[] faceTransparencies)
	{
		this.vertexCount = vertexCount;
		this.faceCount = faceCount;
		this.verticesX = verticesX;
		this.verticesY = verticesY;
		this.verticesZ = verticesZ;
		this.faceIndices1 = faceIndices1;
		this.faceIndices2 = faceIndices2;
		this.faceIndices3 = faceIndices3;
		this.faceColors1 = faceColors1;
		this.faceColors2 = faceColors2;
		this.faceColors3 = faceColors3;
		this.unlitFaceColors = unlitFaceColors;
		this.faceTextures = faceTextures;
		this.faceTransparencies = faceTransparencies;
		this.vertexToPiece = new int[vertexCount];
		this.pieces = decompose();
	}

	/**
	 * @return the piece a vertex belongs to, or null
	 */
	Piece pieceContaining(int vertex)
	{
		if (vertex < 0 || vertex >= vertexCount || vertexToPiece[vertex] < 0)
		{
			return null;
		}
		return pieces.get(vertexToPiece[vertex]);
	}

	/**
	 * Union-find over faces joined by shared edges, yielding connected
	 * components. Edge (not vertex) connectivity matters: the engine welds
	 * identical-position vertices of different equipment models together, so
	 * a single touching point (e.g. a face-revealing helmet's head model
	 * against the cape) would otherwise fuse separate pieces into one.
	 */
	private List<Piece> decompose()
	{
		int[] parent = new int[faceCount];
		for (int i = 0; i < faceCount; i++)
		{
			parent[i] = i;
		}

		Map<Long, Integer> edgeToFace = new HashMap<>(faceCount * 2);
		for (int f = 0; f < faceCount; f++)
		{
			unionEdge(edgeToFace, parent, f, faceIndices1[f], faceIndices2[f]);
			unionEdge(edgeToFace, parent, f, faceIndices2[f], faceIndices3[f]);
			unionEdge(edgeToFace, parent, f, faceIndices1[f], faceIndices3[f]);
		}

		// Group faces by component root, in ascending face order
		Map<Integer, List<Integer>> facesByRoot = new HashMap<>();
		for (int f = 0; f < faceCount; f++)
		{
			facesByRoot.computeIfAbsent(find(parent, f), k -> new ArrayList<>()).add(f);
		}

		// Piece-local vertex labels by first appearance in the face stream;
		// stamp marks which piece a label belongs to so the arrays are shared
		int[] label = new int[vertexCount];
		int[] stamp = new int[vertexCount];
		int stampValue = 0;

		List<Piece> result = new ArrayList<>();
		for (Map.Entry<Integer, List<Integer>> entry : facesByRoot.entrySet())
		{
			int[] faces = entry.getValue().stream().mapToInt(Integer::intValue).toArray();
			stampValue++;

			List<Integer> appearance = new ArrayList<>();
			long hash = hashPass(faces, faceIndices1, faceIndices2, faceIndices3,
				label, stamp, stampValue, appearance);
			int[] verts = appearance.stream().mapToInt(Integer::intValue).toArray();
			String signature = verts.length + "v" + faces.length + "f-" + Long.toHexString(hash);

			// The three reversed-winding visit orders: a mirrored placement
			// of this mesh hashes as one of them (which one depends on the
			// index pair the engine swaps when flipping)
			String[] mirrorSignatures = new String[3];
			int[][] mirrorVertices = new int[3][];
			for (int m = 0; m < 3; m++)
			{
				int[] o1 = m == 0 ? faceIndices1 : m == 1 ? faceIndices3 : faceIndices2;
				int[] o2 = m == 0 ? faceIndices3 : m == 1 ? faceIndices2 : faceIndices1;
				int[] o3 = m == 0 ? faceIndices2 : m == 1 ? faceIndices1 : faceIndices3;
				stampValue++;
				List<Integer> mirrorAppearance = new ArrayList<>();
				long mirrorHash = hashPass(faces, o1, o2, o3, label, stamp, stampValue, mirrorAppearance);
				mirrorVertices[m] = mirrorAppearance.stream().mapToInt(Integer::intValue).toArray();
				mirrorSignatures[m] = mirrorVertices[m].length + "v" + faces.length + "f-"
					+ Long.toHexString(mirrorHash);
			}

			result.add(new Piece(signature, faces, verts, mirrorSignatures, mirrorVertices));
		}
		result.sort(Comparator.comparingInt((Piece p) -> p.faces.length).reversed());

		// A welded vertex can belong to several pieces; the first (largest)
		// piece claims it for click mapping
		Arrays.fill(vertexToPiece, -1);
		for (int i = 0; i < result.size(); i++)
		{
			for (int v : result.get(i).vertices)
			{
				if (vertexToPiece[v] == -1)
				{
					vertexToPiece[v] = i;
				}
			}
		}
		return result;
	}

	private static long hashPass(int[] faces, int[] order1, int[] order2, int[] order3,
		int[] label, int[] stamp, int stampValue, List<Integer> appearance)
	{
		long hash = 1125899906842597L;
		for (int f : faces)
		{
			hash = 31 * hash + labelOf(order1[f], label, stamp, stampValue, appearance);
			hash = 31 * hash + labelOf(order2[f], label, stamp, stampValue, appearance);
			hash = 31 * hash + labelOf(order3[f], label, stamp, stampValue, appearance);
		}
		return hash;
	}

	private static int labelOf(int vertex, int[] label, int[] stamp, int stampValue, List<Integer> appearance)
	{
		if (stamp[vertex] != stampValue)
		{
			stamp[vertex] = stampValue;
			label[vertex] = appearance.size();
			appearance.add(vertex);
		}
		return label[vertex];
	}

	private static void unionEdge(Map<Long, Integer> edgeToFace, int[] parent, int face, int a, int b)
	{
		long key = a < b ? ((long) a << 32) | b : ((long) b << 32) | a;
		Integer other = edgeToFace.putIfAbsent(key, face);
		if (other != null)
		{
			union(parent, face, other);
		}
	}

	private static int find(int[] parent, int i)
	{
		int root = i;
		while (parent[root] != root)
		{
			root = parent[root];
		}
		// Path compression
		while (parent[i] != root)
		{
			int next = parent[i];
			parent[i] = root;
			i = next;
		}
		return root;
	}

	private static void union(int[] parent, int a, int b)
	{
		parent[find(parent, a)] = find(parent, b);
	}
}
