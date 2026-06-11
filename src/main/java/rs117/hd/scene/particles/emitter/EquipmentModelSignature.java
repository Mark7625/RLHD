/*
 * Copyright (c) 2025, Mark7625 (https://github.com/Mark7625/)
 * All rights reserved.
 */
package rs117.hd.scene.particles.emitter;

import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nullable;
import net.runelite.api.Model;

/**
 * Locates a worn equipment submesh inside a combined player model.
 * Face-index topology matching is used so detection works on posed/animated models.
 */
final class EquipmentModelSignature {
	private static final int MAX_SAMPLE_FACES = 12;

	final int vertexCount;
	private final float[] relativeX;
	private final float[] relativeY;
	private final float[] relativeZ;

	private EquipmentModelSignature(int vertexCount, float[] relativeX, float[] relativeY, float[] relativeZ) {
		this.vertexCount = vertexCount;
		this.relativeX = relativeX;
		this.relativeY = relativeY;
		this.relativeZ = relativeZ;
	}

	@Nullable
	static EquipmentModelSignature fromModel(@Nullable Model model) {
		if (model == null)
			return null;

		int count = model.getVerticesCount();
		if (count <= 0)
			return null;

		int signatureCount = Math.min(count, 24);
		float[] vx = model.getVerticesX();
		float[] vy = model.getVerticesY();
		float[] vz = model.getVerticesZ();

		float originX = vx[0];
		float originY = vy[0];
		float originZ = vz[0];

		float[] relX = new float[signatureCount];
		float[] relY = new float[signatureCount];
		float[] relZ = new float[signatureCount];
		for (int i = 0; i < signatureCount; i++) {
			relX[i] = vx[i] - originX;
			relY[i] = vy[i] - originY;
			relZ[i] = vz[i] - originZ;
		}
		return new EquipmentModelSignature(signatureCount, relX, relY, relZ);
	}

	/**
	 * Validates a slot-based vertex offset using face topology (animation-safe).
	 */
	static int resolveSlotOffset(@Nullable Model playerModel, @Nullable Model wearModel, int slotBasedOffset) {
		if (playerModel == null || wearModel == null || slotBasedOffset < 0)
			return -1;

		int wearCount = wearModel.getVerticesCount();
		int playerCount = playerModel.getVerticesCount();
		if (wearCount <= 0 || slotBasedOffset + wearCount > playerCount)
			return -1;

		return matchesFaceTopology(playerModel, wearModel, slotBasedOffset) ? slotBasedOffset : -1;
	}

	/**
	 * Finds the wear-model submesh inside a posed player model by matching triangle indices.
	 */
	static int findSubmeshOffsetByFaces(@Nullable Model playerModel, @Nullable Model wearModel, int searchStartHint) {
		if (playerModel == null || wearModel == null)
			return -1;

		int wearVerts = wearModel.getVerticesCount();
		int playerVerts = playerModel.getVerticesCount();
		if (wearVerts <= 0 || playerVerts < wearVerts)
			return -1;

		int maxOffset = playerVerts - wearVerts;
		int hint = Math.max(0, searchStartHint);

		int fromHint = findSubmeshOffsetByFacesInRange(playerModel, wearModel, hint, maxOffset);
		if (fromHint >= 0)
			return fromHint;

		if (hint > 0) {
			int beforeHint = findSubmeshOffsetByFacesInRange(playerModel, wearModel, 0, hint - 1);
			if (beforeHint >= 0)
				return beforeHint;
		}

		return -1;
	}

	private static int findSubmeshOffsetByFacesInRange(
		Model playerModel,
		Model wearModel,
		int fromOffset,
		int toOffset
	) {
		if (fromOffset > toOffset)
			return -1;

		int wearVerts = wearModel.getVerticesCount();
		int[] wf1 = wearModel.getFaceIndices1();
		int[] wf2 = wearModel.getFaceIndices2();
		int[] wf3 = wearModel.getFaceIndices3();
		int[] pf1 = playerModel.getFaceIndices1();
		int[] pf2 = playerModel.getFaceIndices2();
		int[] pf3 = playerModel.getFaceIndices3();
		if (wf1 == null || wf2 == null || wf3 == null || pf1 == null || pf2 == null || pf3 == null)
			return -1;

		int wearFaces = wearModel.getFaceCount();
		int playerFaces = playerModel.getFaceCount();
		if (wearFaces <= 0 || playerFaces <= 0)
			return -1;

		int sampleFaces = Math.min(wearFaces, MAX_SAMPLE_FACES);
		Set<Long> playerTriangles = buildTriangleSet(pf1, pf2, pf3, playerFaces);

		int w0 = wf1[0];
		int w1 = wf2[0];
		int w2 = wf3[0];

		for (int pf = 0; pf < playerFaces; pf++) {
			int pa = pf1[pf];
			int pb = pf2[pf];
			int pc = pf3[pf];
			int[] candidateOffsets = {
				pa - w0, pb - w0, pc - w0,
				pa - w1, pb - w1, pc - w1,
				pa - w2, pb - w2, pc - w2
			};
			for (int offset : candidateOffsets) {
				if (offset < fromOffset || offset > toOffset)
					continue;
				if (offset < 0 || offset + wearVerts > playerModel.getVerticesCount())
					continue;
				if (matchesFaceTopology(wf1, wf2, wf3, sampleFaces, playerTriangles, offset))
					return offset;
			}
		}
		return -1;
	}

	private static boolean matchesFaceTopology(Model playerModel, Model wearModel, int offset) {
		int[] wf1 = wearModel.getFaceIndices1();
		int[] wf2 = wearModel.getFaceIndices2();
		int[] wf3 = wearModel.getFaceIndices3();
		int[] pf1 = playerModel.getFaceIndices1();
		int[] pf2 = playerModel.getFaceIndices2();
		int[] pf3 = playerModel.getFaceIndices3();
		if (wf1 == null || wf2 == null || wf3 == null || pf1 == null || pf2 == null || pf3 == null)
			return false;

		int sampleFaces = Math.min(wearModel.getFaceCount(), MAX_SAMPLE_FACES);
		Set<Long> playerTriangles = buildTriangleSet(pf1, pf2, pf3, playerModel.getFaceCount());
		return matchesFaceTopology(wf1, wf2, wf3, sampleFaces, playerTriangles, offset);
	}

	private static boolean matchesFaceTopology(
		int[] wf1,
		int[] wf2,
		int[] wf3,
		int sampleFaces,
		Set<Long> playerTriangles,
		int offset
	) {
		for (int f = 0; f < sampleFaces; f++) {
			if (!playerTriangles.contains(packTriangle(wf1[f] + offset, wf2[f] + offset, wf3[f] + offset)))
				return false;
		}
		return true;
	}

	private static Set<Long> buildTriangleSet(int[] f1, int[] f2, int[] f3, int faceCount) {
		Set<Long> triangles = new HashSet<>(faceCount * 2);
		for (int f = 0; f < faceCount; f++)
			triangles.add(packTriangle(f1[f], f2[f], f3[f]));
		return triangles;
	}

	private static long packTriangle(int a, int b, int c) {
		int x = a, y = b, z = c;
		if (x > y) {
			int t = x;
			x = y;
			y = t;
		}
		if (y > z) {
			int t = y;
			y = z;
			z = t;
		}
		if (x > y) {
			int t = x;
			x = y;
			y = t;
		}
		return ((long) x << 42) | ((long) y << 21) | z;
	}
}
