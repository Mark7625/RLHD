package rs117.hd.scene.daylight_cycle;

import rs117.hd.config.ConstellationMode;

public class ConstellationDefinition {
	public String name = "Unnamed";

	// REALISTIC constellations are picked when Constellations is set to Realistic. FUN
	// constellations are only picked in Fun mode, on top of all the REALISTIC ones, letting
	// users layer extra custom shapes on top of the normal constellations by adding more
	// FUN entries.
	public ConstellationMode mode = ConstellationMode.FUN;

	// Angular size multiplier, applied on top of the default constellation size.
	public float scale = 1;

	// Points in a local, unrotated 2D tangent-plane coordinate space, roughly within [-1, 1].
	public float[][] points = {};

	// Pairs of indices into `points`, each describing one line connecting two points.
	public int[][] edges = {};

	public void normalize() {
		if (mode == ConstellationMode.OFF)
			throw new RuntimeException("mode must be REALISTIC or FUN, not OFF");

		if (points.length < 2)
			throw new RuntimeException("requires at least 2 points");

		for (float[] point : points)
			if (point == null || point.length != 2)
				throw new RuntimeException("each point must be an [x, y] pair");

		for (int[] edge : edges) {
			if (edge == null || edge.length != 2)
				throw new RuntimeException("each edge must be an [a, b] index pair");
			for (int index : edge)
				if (index < 0 || index >= points.length)
					throw new RuntimeException("edge index out of range: " + index);
		}

		if (!(scale > 0))
			scale = 1;
	}
}
