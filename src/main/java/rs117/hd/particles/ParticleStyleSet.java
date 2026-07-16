package rs117.hd.particles;

import java.util.Random;
import javax.annotation.Nullable;

final class ParticleStyleSet
{
	private final ParticleStyle[] styles;
	private final int[] weights;
	private final int totalWeight;

	ParticleStyleSet(ParticleStyle[] styles, int[] weights)
	{
		if (styles == null || styles.length == 0)
		{
			throw new IllegalArgumentException("styles");
		}
		this.styles = styles;
		this.weights = weights == null ? defaultWeights(styles.length) : weights;
		int total = 0;
		for (int i = 0; i < this.styles.length; i++)
		{
			int w = i < this.weights.length ? this.weights[i] : 1;
			total += Math.max(1, w);
		}
		this.totalWeight = Math.max(1, total);
	}

	static ParticleStyleSet of(ParticleStyle style)
	{
		return new ParticleStyleSet(new ParticleStyle[] { style }, new int[] { 1 });
	}

	ParticleStyle primary()
	{
		return styles[0];
	}

	ParticleStyle pick(Random random)
	{
		if (styles.length == 1)
		{
			return styles[0];
		}
		int roll = random.nextInt(totalWeight);
		int acc = 0;
		for (int i = 0; i < styles.length; i++)
		{
			int w = i < weights.length ? Math.max(1, weights[i]) : 1;
			acc += w;
			if (roll < acc)
			{
				return styles[i];
			}
		}
		return styles[styles.length - 1];
	}

	int size()
	{
		return styles.length;
	}

	private static int[] defaultWeights(int n)
	{
		int[] w = new int[n];
		for (int i = 0; i < n; i++)
		{
			w[i] = 1;
		}
		return w;
	}
}
