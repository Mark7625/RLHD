package rs117.hd.particles;

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
