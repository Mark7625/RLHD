package rs117.hd.particles;

import lombok.Getter;

/**
 * A single particle in local scene coordinates (128 units per tile).
 * z is the absolute scene height (negative up), anchored to the player's base
 * height at spawn so particles stay glued to the model on uneven ground.
 * Purely simulation state; rendering is done by {@link ParticleRenderer}
 * batching live particles into per-tile models.
 *
 * Instances are pooled by {@link ParticleSystem} and reinitialized via
 * {@link #reset}, so fields are mutable by design.
 */
class Particle
{
	@Getter
	private float x;
	@Getter
	private float y;
	@Getter
	private float z;

	@Getter
	private float velX;
	@Getter
	private float velY;
	@Getter
	private float velZ;
	private float lifetime;
	// Sinusoidal drift so motion meanders instead of travelling straight
	private float wobblePhase;
	private float wobbleFreq;
	private float wobbleAmp;
	private float age;

	/**
	 * The style this particle renders with.
	 */
	@Getter
	private ParticleStyle style;

	/**
	 * Which pre-baked size variant of the style this particle renders with.
	 */
	@Getter
	private int sizeVariant;

	/**
	 * Per-particle uniform size scale from the profile's size jitter (1 = the
	 * base size), applied on top of the {@link #sizeVariant} auto-variation.
	 */
	@Getter
	private float sizeScale;

	/**
	 * Random flipbook frame index at spawn, or -1 when unused.
	 */
	@Getter
	private int flipbookFrame = -1;

	/**
	 * When set, the particle is removed once its height reaches the local
	 * tile surface (weather).
	 */
	private boolean groundClip;
	private int groundPlane;
	/** 1 = fully visible; ramps to 0 near the tile surface (weather). */
	private float groundProximityFade = 1f;
	private float yaw;
	/** Non-zero = fixed spawn colour (per-particle random when uniform variation is off). */
	private int spawnColorArgb;

	boolean isGroundClip()
	{
		return groundClip;
	}

	float getGroundProximityFade()
	{
		return groundProximityFade;
	}

	void setGroundProximityFade(float fade)
	{
		groundProximityFade = fade;
	}

	int getGroundPlane()
	{
		return groundPlane;
	}

	float getYaw()
	{
		return yaw;
	}

	int getSpawnColorArgb()
	{
		return spawnColorArgb;
	}

	void setSpawnState(float yaw, int spawnColorArgb)
	{
		this.yaw = yaw;
		this.spawnColorArgb = spawnColorArgb;
	}

	void reset(float x, float y, float z, float velX, float velY, float velZ,
		float lifetime, ParticleStyle style, int sizeVariant, float sizeScale,
		float wobblePhase, float wobbleFreq, float wobbleAmp, int flipbookFrame)
	{
		reset(x, y, z, velX, velY, velZ, lifetime, style, sizeVariant, sizeScale,
			wobblePhase, wobbleFreq, wobbleAmp, flipbookFrame, false);
	}

	void reset(float x, float y, float z, float velX, float velY, float velZ,
		float lifetime, ParticleStyle style, int sizeVariant, float sizeScale,
		float wobblePhase, float wobbleFreq, float wobbleAmp, int flipbookFrame,
		boolean groundClip)
	{
		reset(x, y, z, velX, velY, velZ, lifetime, style, sizeVariant, sizeScale,
			wobblePhase, wobbleFreq, wobbleAmp, flipbookFrame, groundClip, 0);
	}

	void reset(float x, float y, float z, float velX, float velY, float velZ,
		float lifetime, ParticleStyle style, int sizeVariant, float sizeScale,
		float wobblePhase, float wobbleFreq, float wobbleAmp, int flipbookFrame,
		boolean groundClip, int groundPlane)
	{
		this.x = x;
		this.y = y;
		this.z = z;
		this.velX = velX;
		this.velY = velY;
		this.velZ = velZ;
		this.lifetime = lifetime;
		this.style = style;
		this.sizeVariant = sizeVariant;
		this.sizeScale = sizeScale;
		this.wobblePhase = wobblePhase;
		this.wobbleFreq = wobbleFreq;
		this.wobbleAmp = wobbleAmp;
		this.flipbookFrame = flipbookFrame;
		this.age = 0;
		this.groundClip = groundClip;
		this.groundPlane = groundPlane;
		this.groundProximityFade = 1f;
		this.yaw = 0f;
		this.spawnColorArgb = 0;
	}

	void update(float dt)
	{
		age += dt;
		float rot = style.getRotationSpeed();
		if (rot != 0f)
		{
			yaw += rot * dt;
		}
		// Gravity: downward acceleration on the vertical velocity (scene z is
		// negative-up, so falling means an increasing z). Constant when 0.
		velZ += style.getGravity() * dt;
		// Drag: shed a fraction of the particle's own velocity so rises,
		// spreads and falls settle over life. With gravity this converges on a
		// terminal fall speed. Wind (below) is a separate steady drift and is
		// not decayed - it is the medium moving, not the particle's momentum.
		float drag = style.getDragPerSec();
		if (drag > 0f)
		{
			float keep = Math.max(0f, 1f - drag * dt);
			velX *= keep;
			velY *= keep;
			velZ *= keep;
		}
		float t = age * wobbleFreq + wobblePhase;
		x += (velX + style.getWindX() + (float) Math.sin(t) * wobbleAmp) * dt;
		y += (velY + style.getWindY() + (float) Math.cos(t * 1.3f) * wobbleAmp) * dt;
		// Wind Z is up-positive like Rise; scene z is negative-up, so subtract.
		z += (velZ - style.getWindZ()) * dt;
	}

	boolean isDead()
	{
		// Weather falls until it hits terrain; clipGround removes it.
		if (groundClip)
		{
			return false;
		}
		return age >= lifetime;
	}

	void kill()
	{
		// Weather is removed by clipGround only; render culling must not expire it.
		if (groundClip)
		{
			return;
		}
		age = lifetime;
	}

	/**
	 * @return remaining life as 1 (just spawned) to 0 (dead)
	 */
	float lifeFraction()
	{
		return Math.max(0f, 1f - age / lifetime);
	}

	/**
	 * Age fraction 0..1 for fade envelopes and colour ramps. Weather uses a
	 * constant mid-life value so the GPU sin envelope stays at full opacity.
	 */
	float renderAgeFraction()
	{
		if (groundClip)
		{
			return 0.5f;
		}
		return 1f - lifeFraction();
	}

	/**
	 * Life fraction used for fade envelopes on the CPU template path.
	 */
	float displayLifeFraction()
	{
		if (groundClip)
		{
			return 1f;
		}
		return lifeFraction();
	}
}
