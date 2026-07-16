package rs117.hd.particles;
public final class ParticleColors
{
	private ParticleColors()
	{
	}
	public static int hexToArgb(String hex)
	{
		if (hex == null || hex.isEmpty())
		{
			return 0;
		}
		String s = hex.startsWith("#") ? hex.substring(1) : hex;
		try
		{
			long value;
			switch (s.length())
			{
				case 3:
					value = Integer.parseInt("" + s.charAt(0) + s.charAt(0)
						+ s.charAt(1) + s.charAt(1)
						+ s.charAt(2) + s.charAt(2), 16);
					return (int) (0xFF000000L | value);
				case 4:
					value = Integer.parseInt("" + s.charAt(0) + s.charAt(0)
						+ s.charAt(1) + s.charAt(1)
						+ s.charAt(2) + s.charAt(2)
						+ s.charAt(3) + s.charAt(3), 16);
					return (int) (((value & 0xFF) << 24) | (value >> 8));
				case 6:
					value = Long.parseLong(s, 16);
					return (int) (0xFF000000L | value);
				case 8:
					value = Long.parseLong(s, 16);
					return (int) (((value & 0xFF) << 24) | (value >> 8));
				default:
					return 0;
			}
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}
}
