package rs117.hd.particles;

/**
 * Particle silhouette. Default is the soft round glow; Diamond warps the
 * shared billboard disc's vertices into a four-point diamond. Warping keeps
 * the mesh topology intact, so the batch canvas is unaffected.
 */
enum Shape
{
	DEFAULT("Default"),
	DIAMOND("Diamond");

	private final String label;

	Shape(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
