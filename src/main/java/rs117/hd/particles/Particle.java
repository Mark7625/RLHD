package rs117.hd.particles;

import java.util.List;
import javax.annotation.Nullable;
import lombok.Getter;

public class Particle
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

	private float wobblePhase;
	private float wobbleFreq;
	private float wobbleAmp;
	private float age;

	@Getter
	private ParticleStyle style;

	@Getter
	private int sizeVariant;

	@Getter
	private float sizeScale;

	@Getter
	private int flipbookFrame = -1;

	private boolean groundClip;
	private int groundPlane;

	private float groundProximityFade = 1f;
	private float yaw;

	private int spawnColorArgb;

	private int effectorSeed;
	@Nullable
	private List<String> globalEffectors;
	@Nullable
	private List<String> localEffectorFilter;
	@Nullable
	private List<String> embeddedEffectors;
	private boolean helixLatched;
	private float helixT;
	private boolean pathGuided;

	public boolean isGroundClip()
	{
		return groundClip;
	}

	public float getGroundProximityFade()
	{
		return groundProximityFade;
	}

	public void setGroundProximityFade(float fade)
	{
		groundProximityFade = fade;
	}

	public int getGroundPlane()
	{
		return groundPlane;
	}

	public float getYaw()
	{
		return yaw;
	}

	public int getSpawnColorArgb()
	{
		return spawnColorArgb;
	}

	public void setSpawnState(float yaw, int spawnColorArgb)
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
		this.effectorSeed = Float.floatToIntBits(x) ^ Float.floatToIntBits(y) ^ Float.floatToIntBits(z);
		this.globalEffectors = null;
		this.localEffectorFilter = null;
		this.embeddedEffectors = null;
		this.helixLatched = false;
		this.helixT = 0f;
		this.pathGuided = false;
	}

	public void setEffectorLists(
		@Nullable List<String> globalEffectors,
		@Nullable List<String> localEffectorFilter,
		@Nullable List<String> embeddedEffectors)
	{
		this.globalEffectors = globalEffectors;
		this.localEffectorFilter = localEffectorFilter;
		this.embeddedEffectors = embeddedEffectors;
	}

	@Nullable
	public List<String> getGlobalEffectors()
	{
		return globalEffectors;
	}

	@Nullable
	public List<String> getLocalEffectorFilter()
	{
		return localEffectorFilter;
	}

	@Nullable
	public List<String> getEmbeddedEffectors()
	{
		return embeddedEffectors;
	}

	public int getEffectorSeed()
	{
		return effectorSeed;
	}

	public float getAge()
	{
		return age;
	}

	public void setVelocity(float velX, float velY, float velZ)
	{
		this.velX = velX;
		this.velY = velY;
		this.velZ = velZ;
	}

	public void setPosition(float x, float y, float z)
	{
		this.x = x;
		this.y = y;
		this.z = z;
	}

	public void addPosition(float dx, float dy, float dz)
	{
		this.x += dx;
		this.y += dy;
		this.z += dz;
	}

	public void setSpawnColorArgb(int argb)
	{
		this.spawnColorArgb = argb;
	}

	public boolean isHelixLatched()
	{
		return helixLatched;
	}

	public float getHelixT()
	{
		return helixT;
	}

	public void setHelix(boolean latched, float t)
	{
		this.helixLatched = latched;
		this.helixT = t;
	}

	public void clearHelix()
	{
		helixLatched = false;
		helixT = 0f;
	}

	public void markPathGuided()
	{
		pathGuided = true;
	}

	void update(float dt)
	{
		age += dt;
		pathGuided = false;
		float rot = style.getRotationSpeed();
		if (rot != 0f)
		{
			yaw += rot * dt;
		}

		velZ += style.getGravity() * dt;

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

		z += (velZ - style.getWindZ()) * dt;
	}

	public void updateAfterEffectors(float dt)
	{
		boolean guided = pathGuided;
		pathGuided = false;
		age += dt;
		if (guided)
		{
			return;
		}
		float rot = style.getRotationSpeed();
		if (rot != 0f)
		{
			yaw += rot * dt;
		}
		velZ += style.getGravity() * dt;
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
		z += (velZ - style.getWindZ()) * dt;
	}

	boolean isDead()
	{

		if (groundClip)
		{
			return false;
		}
		return age >= lifetime;
	}

	void kill()
	{

		if (groundClip)
		{
			return;
		}
		age = lifetime;
	}

	float lifeFraction()
	{
		return Math.max(0f, 1f - age / lifetime);
	}

	float renderAgeFraction()
	{
		if (groundClip)
		{
			return 0.5f;
		}
		return 1f - lifeFraction();
	}

	float displayLifeFraction()
	{
		if (groundClip)
		{
			return 1f;
		}
		return lifeFraction();
	}
}
