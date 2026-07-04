package rs117.hd.scene.model;

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
public class ModelSnapshot {
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
	private final List<Piece> pieces;
	private final int[] vertexToPiece;

	@Getter
	public static class Piece {
		private final String meshKey;
		private final int[] faces;
		private final int[] vertices;
		private final int[] sortedVertices;
		private final int[] sortedToLocal;
		private final int[] sortedFaces;
		private final int[] sortedToLocalFace;

		Piece(String meshKey, int[] faces, int[] vertices) {
			this.meshKey = meshKey;
			this.faces = faces;
			this.vertices = vertices;

			sortedVertices = vertices.clone();
			Arrays.sort(sortedVertices);
			sortedToLocal = new int[vertices.length];
			for (int local = 0; local < vertices.length; local++) {
				sortedToLocal[Arrays.binarySearch(sortedVertices, vertices[local])] = local;
			}

			sortedFaces = faces.clone();
			Arrays.sort(sortedFaces);
			sortedToLocalFace = new int[faces.length];
			for (int local = 0; local < faces.length; local++) {
				sortedToLocalFace[Arrays.binarySearch(sortedFaces, faces[local])] = local;
			}
		}

		public int localIndexOf(int globalVertex) {
			int pos = Arrays.binarySearch(sortedVertices, globalVertex);
			return pos < 0 ? -1 : sortedToLocal[pos];
		}

		public int localFaceIndex(int globalFace) {
			int pos = Arrays.binarySearch(sortedFaces, globalFace);
			return pos < 0 ? -1 : sortedToLocalFace[pos];
		}
	}

	public static ModelSnapshot capture(Model model) {
		int faceCount = model.getFaceCount();
		return new ModelSnapshot(
			model.getVerticesCount(),
			faceCount,
			model.getVerticesX().clone(),
			model.getVerticesY().clone(),
			model.getVerticesZ().clone(),
			model.getFaceIndices1().clone(),
			model.getFaceIndices2().clone(),
			model.getFaceIndices3().clone(),
			cloneFaceColors(model.getFaceColors1(), faceCount),
			cloneFaceColors(model.getFaceColors2(), faceCount),
			cloneFaceColors(model.getFaceColors3(), faceCount)
		);
	}

	private static int[] cloneFaceColors(@Nullable int[] colors, int faceCount) {
		if (colors == null || colors.length < faceCount)
			return new int[faceCount];
		return Arrays.copyOf(colors, faceCount);
	}

	private ModelSnapshot(
		int vertexCount,
		int faceCount,
		float[] verticesX,
		float[] verticesY,
		float[] verticesZ,
		int[] faceIndices1,
		int[] faceIndices2,
		int[] faceIndices3,
		int[] faceColors1,
		int[] faceColors2,
		int[] faceColors3
	) {
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
		this.vertexToPiece = new int[vertexCount];
		this.pieces = decompose();
	}

	public Piece pieceContaining(int vertex) {
		if (vertex < 0 || vertex >= vertexCount || vertexToPiece[vertex] < 0)
			return null;
		return pieces.get(vertexToPiece[vertex]);
	}

	@Nullable
	public Piece pieceForFace(int globalFace, int preferredPieceIndex) {
		if (preferredPieceIndex >= 0 && preferredPieceIndex < pieces.size()) {
			Piece preferred = pieces.get(preferredPieceIndex);
			if (preferred.localFaceIndex(globalFace) >= 0)
				return preferred;
		}
		for (Piece piece : pieces) {
			if (piece.localFaceIndex(globalFace) >= 0)
				return piece;
		}
		return null;
	}

	@Nullable
	public Piece pieceForVertex(int globalVertex, int preferredPieceIndex) {
		if (preferredPieceIndex >= 0 && preferredPieceIndex < pieces.size()) {
			Piece preferred = pieces.get(preferredPieceIndex);
			if (preferred.localIndexOf(globalVertex) >= 0)
				return preferred;
		}
		return pieceContaining(globalVertex);
	}

	private List<Piece> decompose() {
		int[] parent = new int[faceCount];
		for (int i = 0; i < faceCount; i++)
			parent[i] = i;

		Map<Long, Integer> edgeToFace = new HashMap<>(faceCount * 2);
		for (int f = 0; f < faceCount; f++) {
			unionEdge(edgeToFace, parent, f, faceIndices1[f], faceIndices2[f]);
			unionEdge(edgeToFace, parent, f, faceIndices2[f], faceIndices3[f]);
			unionEdge(edgeToFace, parent, f, faceIndices1[f], faceIndices3[f]);
		}

		Map<Integer, List<Integer>> facesByRoot = new HashMap<>();
		for (int f = 0; f < faceCount; f++)
			facesByRoot.computeIfAbsent(find(parent, f), k -> new ArrayList<>()).add(f);

		int[] label = new int[vertexCount];
		int[] stamp = new int[vertexCount];
		int stampValue = 0;

		List<Piece> result = new ArrayList<>();
		for (Map.Entry<Integer, List<Integer>> entry : facesByRoot.entrySet()) {
			int[] faces = entry.getValue().stream().mapToInt(Integer::intValue).toArray();
			stampValue++;

			List<Integer> appearance = new ArrayList<>();
			long hash = 1125899906842597L;
			for (int f : faces) {
				hash = 31 * hash + labelOf(faceIndices1[f], label, stamp, stampValue, appearance);
				hash = 31 * hash + labelOf(faceIndices2[f], label, stamp, stampValue, appearance);
				hash = 31 * hash + labelOf(faceIndices3[f], label, stamp, stampValue, appearance);
				hash = 31 * hash + faceColors1[f];
				hash = 31 * hash + faceColors2[f];
				hash = 31 * hash + faceColors3[f];
			}

			int[] verts = appearance.stream().mapToInt(Integer::intValue).toArray();
			String meshKey = verts.length + "v" + faces.length + "f-" + Long.toHexString(hash);
			result.add(new Piece(meshKey, faces, verts));
		}
		result.sort(Comparator.comparingInt((Piece p) -> p.faces.length).reversed());

		Arrays.fill(vertexToPiece, -1);
		for (int i = 0; i < result.size(); i++) {
			for (int v : result.get(i).vertices) {
				if (vertexToPiece[v] == -1)
					vertexToPiece[v] = i;
			}
		}
		return result;
	}

	private static int labelOf(int vertex, int[] label, int[] stamp, int stampValue, List<Integer> appearance) {
		if (stamp[vertex] != stampValue) {
			stamp[vertex] = stampValue;
			label[vertex] = appearance.size();
			appearance.add(vertex);
		}
		return label[vertex];
	}

	private static void unionEdge(Map<Long, Integer> edgeToFace, int[] parent, int face, int a, int b) {
		long key = a < b ? ((long) a << 32) | b : ((long) b << 32) | a;
		Integer other = edgeToFace.putIfAbsent(key, face);
		if (other != null)
			union(parent, face, other);
	}

	private static int find(int[] parent, int i) {
		int root = i;
		while (parent[root] != root)
			root = parent[root];
		while (parent[i] != root) {
			int next = parent[i];
			parent[i] = root;
			i = next;
		}
		return root;
	}

	private static void union(int[] parent, int a, int b) {
		parent[find(parent, a)] = find(parent, b);
	}
}
