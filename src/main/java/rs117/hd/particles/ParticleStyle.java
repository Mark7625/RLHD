package rs117.hd.particles;

import java.util.Set;
import javax.annotation.Nullable;
import lombok.Getter;
import net.runelite.api.ModelData;

@Getter
public class ParticleStyle
{
	static final int FADE_STEPS = 12;

	static final float[] SIZE_MULTIPLIERS = {0.65f, 1.0f, 1.4f};

	static final int MIN_SIZE = 2;

	private final ModelData[][] templates;

	private final int[][] litColors1;
	private final int[][] litColors2;
	private final int[][] litColors3;

	private final boolean colorGradient;
	private final float lifetimeSec;
	private final float particlesPerSecond;

	private final float trailDensity;
	private final float riseSpeed;
	private final float spreadSpeed;

	private final int sizeJitter;

	private final float gravity;

	private final float windX;
	private final float windY;
	private final float windZ;

	private final float dragPerSec;

	private final float vortex;

	private final float emitScale;

	private final float stretchFactor;

	private final float stretchRampStart;

	private final int baseSize;
	private final float spawnJitter;
	private final int featherStrength;

	private final int interpolation;

	private final float depthBias;

	private final float movementLifetimeScale;

	private final float offsetX;
	private final float offsetY;
	private final float offsetZ;
	private final Set<Integer> animationIds;

	private final int[] animFrameRanges;
	private final int startArgb;
	private final int endArgb;
	private final float colorFadeStart;
	@Nullable
	private final String textureFile;
	private final int flipbookColumns;
	private final int flipbookRows;
	@Nullable
	private final String flipbookMode;
	private final float rotationSpeed;
	private final boolean useEnvironmentLight;
	private final boolean uniformColorVariation;
	private final float scaleStartMul;
	private final float scaleEndMul;

	ParticleStyle(ModelData[][] templates, int[][] litColors1, int[][] litColors2, int[][] litColors3,
		ParticleDefinition definition, EmitterProfile emitter)
	{
		this.templates = templates;
		this.litColors1 = litColors1;
		this.litColors2 = litColors2;
		this.litColors3 = litColors3;
		this.colorGradient = definition.isColorFade() && definition.getColorEnd() != definition.getColor();
		this.startArgb = definition.getColor();
		this.endArgb = definition.getColorEnd();
		this.colorFadeStart = Math.max(0, Math.min(100, definition.getColorFadeStart())) / 100f;
		this.lifetimeSec = definition.getLifetimeMs() / 1000f;
		this.particlesPerSecond = definition.getParticlesPerSecond();
		this.trailDensity = definition.getTrailDensity();
		this.riseSpeed = definition.getRiseSpeed();
		this.spreadSpeed = definition.getSpreadSpeed();
		this.sizeJitter = Math.max(0, definition.getSizeJitter());
		this.gravity = definition.getGravity();
		this.windX = definition.getWindX();
		this.windY = definition.getWindY();
		this.windZ = definition.getWindZ();
		this.dragPerSec = Math.max(0, definition.getDrag()) / 100f;
		this.vortex = definition.getVortex();
		this.emitScale = Math.max(0, emitter.getEmitScale()) / 100f;
		this.stretchFactor = 1f + Math.max(0, definition.getStretch()) / 100f;
		this.stretchRampStart = 1f - Math.max(0, Math.min(100, definition.getStretchRamp())) / 100f;
		this.baseSize = definition.getSize();
		this.spawnJitter = definition.getSpawnJitter();
		this.featherStrength = emitter.getFeatherStrength();
		this.interpolation = Math.max(0, Math.min(4, emitter.getInterpolation()));
		this.depthBias = emitter.getDepthBias();
		this.movementLifetimeScale = Math.max(10, Math.min(100, definition.getMovementLifetime())) / 100f;
		this.offsetX = emitter.getOffsetX();
		this.offsetY = emitter.getOffsetY();
		this.offsetZ = emitter.getOffsetZ();
		this.animationIds = Set.copyOf(emitter.getAnimationIds());
		this.animFrameRanges = parseFrameRanges(emitter.getAnimFrames());
		String tex = definition.getTextureFile();
		this.textureFile = tex == null || tex.isEmpty() ? null : tex;
		this.flipbookColumns = Math.max(0, definition.getFlipbookColumns());
		this.flipbookRows = Math.max(0, definition.getFlipbookRows());
		String mode = definition.getFlipbookMode();
		this.flipbookMode = mode == null || mode.isEmpty() ? null : mode;
		this.rotationSpeed = definition.getRotationSpeed();
		this.useEnvironmentLight = definition.isUseEnvironmentLight();
		this.uniformColorVariation = definition.isUniformColorVariation();
		this.scaleStartMul = Math.max(0, definition.getScaleStartPercent()) / 100f;
		this.scaleEndMul = Math.max(0, definition.getScaleEndPercent()) / 100f;
	}

	boolean hasFlipbook()
	{
		return flipbookColumns > 0 && flipbookRows > 0 && flipbookMode != null;
	}

	int getFlipbookFrameCount()
	{
		return flipbookColumns * flipbookRows;
	}

	float getGpuFlipbookFrame(Particle p)
	{
		if (!hasFlipbook())
		{
			return 0f;
		}
		if ("random".equalsIgnoreCase(flipbookMode) && p.getFlipbookFrame() >= 0)
		{
			return 1f + p.getFlipbookFrame();
		}
		return 1f - p.lifeFraction();
	}

	private static int[] parseFrameRanges(String text)
	{
		if (text == null || text.isEmpty())
		{
			return null;
		}
		java.util.List<Integer> bounds = new java.util.ArrayList<>();
		for (String token : text.split(","))
		{
			try
			{
				String[] parts = token.trim().split("-", 2);
				if (parts[0].isEmpty())
				{
					continue;
				}
				int start = Integer.parseInt(parts[0].trim());
				int end = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : start;
				bounds.add(Math.min(start, end));
				bounds.add(Math.max(start, end));
			}
			catch (NumberFormatException ignored)
			{
			}
		}
		if (bounds.isEmpty())
		{
			return null;
		}
		int[] ranges = new int[bounds.size()];
		for (int i = 0; i < ranges.length; i++)
		{
			ranges[i] = bounds.get(i);
		}
		return ranges;
	}

	boolean frameMatches(int frame)
	{
		if (animFrameRanges == null)
		{
			return true;
		}
		for (int i = 0; i < animFrameRanges.length; i += 2)
		{
			if (frame >= animFrameRanges[i] && frame <= animFrameRanges[i + 1])
			{
				return true;
			}
		}
		return false;
	}

	boolean animationMatches(int actionAnimation, int actionFrame, int poseAnimation)
	{
		if (animationIds.isEmpty())
		{
			return true;
		}
		if (animationIds.contains(actionAnimation))
		{
			if (animFrameRanges == null)
			{
				return true;
			}
			for (int i = 0; i < animFrameRanges.length; i += 2)
			{
				if (actionFrame >= animFrameRanges[i] && actionFrame <= animFrameRanges[i + 1])
				{
					return true;
				}
			}
			return false;
		}
		return animationIds.contains(poseAnimation);
	}

	float getGpuRadius(Particle p) {
		float sizeScale = p.getSizeScale();
		float variantRadius = baseSize * 0.5f * SIZE_MULTIPLIERS[p.getSizeVariant()];
		if (sizeScale > 1f && variantRadius > 1f) {
			sizeScale = Math.min(sizeScale, Math.max(1f, 55f / variantRadius));
		}
		return variantRadius * sizeScale * lifeScaleMul(p);
	}

	float lifeScaleMul(Particle p) {
		float ageFrac = p.renderAgeFraction();
		return scaleStartMul + (scaleEndMul - scaleStartMul) * ageFrac;
	}

	void writeGpuColor(Particle p, float[] out) {
		float ageFrac = p.renderAgeFraction();
		float envelope = (float) Math.pow(Math.sin(Math.PI * ageFrac), 0.8);
		int argb;
		if (p.getSpawnColorArgb() != 0)
		{
			argb = p.getSpawnColorArgb();
		}
		else
		{
			float fadeSpan = Math.max(0.001f, 1f - colorFadeStart);
			float fade = colorGradient ? Math.max(0f, Math.min(1f, (ageFrac - colorFadeStart) / fadeSpan)) : 0f;
			argb = colorGradient ? lerpArgb(startArgb, endArgb, fade) : startArgb;
		}
		out[0] = ((argb >> 16) & 0xFF) / 255f;
		out[1] = ((argb >> 8) & 0xFF) / 255f;
		out[2] = (argb & 0xFF) / 255f;
		out[3] = (((argb >>> 24) & 0xFF) / 255f) * envelope;
		if (p.isGroundClip())
		{
			out[3] *= p.getGroundProximityFade();
		}
	}

	private static int lerpArgb(int a, int b, float t) {
		int aa = (a >>> 24) & 0xFF, ar = (a >>> 16) & 0xFF, ag = (a >>> 8) & 0xFF, ab = a & 0xFF;
		int ba = (b >>> 24) & 0xFF, br = (b >>> 16) & 0xFF, bg = (b >>> 8) & 0xFF, bb = b & 0xFF;
		int oa = Math.round(aa + (ba - aa) * t);
		int or = Math.round(ar + (br - ar) * t);
		int og = Math.round(ag + (bg - ag) * t);
		int ob = Math.round(ab + (bb - ab) * t);
		return (oa << 24) | (or << 16) | (og << 8) | ob;
	}

	static final class StyleSet
	{
		private final ParticleStyle[] styles;
		private final int[] weights;
		private final int totalWeight;

		StyleSet(ParticleStyle[] styles, int[] weights)
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

		static StyleSet of(ParticleStyle style)
		{
			return new StyleSet(new ParticleStyle[] { style }, new int[] { 1 });
		}

		ParticleStyle primary()
		{
			return styles[0];
		}

		ParticleStyle pick(java.util.Random random)
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
}
