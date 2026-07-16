package rs117.hd.particles;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import rs117.hd.particles.effector.ActiveEffectorState;
import rs117.hd.particles.effector.EffectorApplier;
import rs117.hd.particles.effector.EffectorDefinitionManager;

public class ParticleSystem
{

	private static final int MAX_PARTICLES = 8192;

	static final float WEATHER_SPAWN_ABOVE_GROUND = 1600f;

	static final float WEATHER_SPAWN_ABOVE_CAMERA = 900f;

	static final float WEATHER_SPAWN_MIN_ABOVE_GROUND = 960f;
	static final float WEATHER_SPAWN_MAX_ABOVE_GROUND = 2200f;

	static final float WEATHER_GROUND_FADE_HEIGHT = 448f;
	static final float WEATHER_GROUND_FADE_END = 64f;

	@Getter
	private final List<Particle> particles = new ArrayList<>();
	private final Deque<Particle> pool = new ArrayDeque<>();

	@Nullable
	Particle spawn(float x, float y, float z, float velX, float velY, float velZ,
		float lifetime, ParticleStyle style, int sizeVariant, float sizeScale,
		float wobblePhase, float wobbleFreq, float wobbleAmp, int flipbookFrame)
	{
		return spawn(x, y, z, velX, velY, velZ, lifetime, style, sizeVariant, sizeScale,
			wobblePhase, wobbleFreq, wobbleAmp, flipbookFrame, false);
	}

	@Nullable
	Particle spawn(float x, float y, float z, float velX, float velY, float velZ,
		float lifetime, ParticleStyle style, int sizeVariant, float sizeScale,
		float wobblePhase, float wobbleFreq, float wobbleAmp, int flipbookFrame,
		boolean groundClip)
	{
		return spawn(x, y, z, velX, velY, velZ, lifetime, style, sizeVariant, sizeScale,
			wobblePhase, wobbleFreq, wobbleAmp, flipbookFrame, groundClip, 0);
	}

	@Nullable
	Particle spawn(float x, float y, float z, float velX, float velY, float velZ,
		float lifetime, ParticleStyle style, int sizeVariant, float sizeScale,
		float wobblePhase, float wobbleFreq, float wobbleAmp, int flipbookFrame,
		boolean groundClip, int groundPlane)
	{
		if (particles.size() >= MAX_PARTICLES)
		{
			return null;
		}
		Particle p = pool.pollFirst();
		if (p == null)
		{
			p = new Particle();
		}
		p.reset(x, y, z, velX, velY, velZ,
			lifetime, style, sizeVariant, sizeScale, wobblePhase, wobbleFreq, wobbleAmp, flipbookFrame,
			groundClip, groundPlane);
		particles.add(p);
		return p;
	}

	void clipGround(int worldView, Client client, Consumer<Particle> onDeath)
	{
		WorldView wv = client.getWorldView(worldView);
		if (wv == null)
		{
			return;
		}
		for (int i = particles.size() - 1; i >= 0; i--)
		{
			Particle p = particles.get(i);
			if (!p.isGroundClip())
			{
				continue;
			}
			int x = (int) p.getX();
			int y = (int) p.getY();
			int sceneX = x >> 7;
			int sceneY = y >> 7;
			if (sceneX < 0 || sceneX >= wv.getSizeX() || sceneY < 0 || sceneY >= wv.getSizeY())
			{
				continue;
			}
			LocalPoint lp = new LocalPoint(x, y, worldView);
			int ground = Perspective.getTileHeight(client, lp, p.getGroundPlane());
			float above = ground - p.getZ();
			float fade = weatherGroundFade(above);
			p.setGroundProximityFade(fade);

			if (fade <= 0f)
			{
				onDeath.accept(p);
				int last = particles.size() - 1;
				particles.set(i, particles.get(last));
				particles.remove(last);
				pool.addFirst(p);
			}
		}
	}

	private static float weatherGroundFade(float aboveGround)
	{
		if (aboveGround >= WEATHER_GROUND_FADE_HEIGHT)
		{
			return 1f;
		}
		if (aboveGround <= WEATHER_GROUND_FADE_END)
		{
			return 0f;
		}
		float t = (aboveGround - WEATHER_GROUND_FADE_END)
			/ (WEATHER_GROUND_FADE_HEIGHT - WEATHER_GROUND_FADE_END);
		return t * t;
	}

	void update(float dt, @Nullable Map<String, List<ActiveEffectorState>> activeEffectorsById,
		@Nullable EffectorDefinitionManager effectorDefinitions, Consumer<Particle> onDeath)
	{
		for (int i = particles.size() - 1; i >= 0; i--)
		{
			Particle p = particles.get(i);
			boolean despawn = EffectorApplier.apply(p, dt, activeEffectorsById, effectorDefinitions);
			if (despawn)
			{
				onDeath.accept(p);
				int last = particles.size() - 1;
				particles.set(i, particles.get(last));
				particles.remove(last);
				pool.addFirst(p);
				continue;
			}
			p.updateAfterEffectors(dt);
			if (p.isDead())
			{
				onDeath.accept(p);
				int last = particles.size() - 1;
				particles.set(i, particles.get(last));
				particles.remove(last);
				pool.addFirst(p);
			}
		}
	}

	void update(float dt, Consumer<Particle> onDeath)
	{
		update(dt, null, null, onDeath);
	}

	void clear(Consumer<Particle> onDeath)
	{
		for (Particle p : particles)
		{
			onDeath.accept(p);
			pool.addFirst(p);
		}
		particles.clear();
	}
}
