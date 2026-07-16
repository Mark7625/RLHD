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

	private final int[] faceColors1;
	private final int[] faceColors2;
	private final int[] faceColors3;

	private final short[] unlitFaceColors;

	private final short[] faceTextures;

	private final byte[] faceTransparencies;
	private final List<Piece> pieces;

	private final int[] vertexToPiece;

	@Getter
	static class Piece
	{
		private final String signature;
		private final int[] faces;

		private final int[] vertices;

		private final String[] mirrorSignatures;

		private final int[][] mirrorVertices;

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

		int localIndexOf(int globalVertex)
		{
			int pos = Arrays.binarySearch(sortedVertices, globalVertex);
			return pos < 0 ? -1 : sortedToLocal[pos];
		}

		int localFaceIndexOf(int globalFace)
		{
			int pos = Arrays.binarySearch(faces, globalFace);
			return pos < 0 ? -1 : pos;
		}

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

	static ModelSnapshot capture(Model model)
	{
		return capture(model, false);
	}

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

	Piece pieceContaining(int vertex)
	{
		if (vertex < 0 || vertex >= vertexCount || vertexToPiece[vertex] < 0)
		{
			return null;
		}
		return pieces.get(vertexToPiece[vertex]);
	}

	@Nullable
	Piece pieceContainingFace(int face)
	{
		if (face < 0 || face >= faceCount)
		{
			return null;
		}
		for (Piece piece : pieces)
		{
			if (piece.localFaceIndexOf(face) >= 0)
			{
				return piece;
			}
		}
		return null;
	}

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

		Map<Integer, List<Integer>> facesByRoot = new HashMap<>();
		for (int f = 0; f < faceCount; f++)
		{
			facesByRoot.computeIfAbsent(find(parent, f), k -> new ArrayList<>()).add(f);
		}

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
