package rs117.hd.particles;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
class EmitterProfile
{
	static final String TARGET_PLAYER = "player";
	static final String TARGET_PROJECTILE = "projectile";
	static final String TARGET_OBJECT = "object";
	static final String TARGET_NPC = "npc";
	static final String TARGET_GRAPHIC = "graphic";
	static final String TARGET_WEATHER = "weather";

	private String name;

	private String targetType = TARGET_PLAYER;

	private int projectileId = -1;

	private int objectId = -1;

	private int npcId = -1;

	private int graphicId = -1;

	private List<String> weatherAreas = new ArrayList<>();

	private float weatherParticlesPerTile = 10f;

	private float weatherDensityScale = 1f;

	private List<String> globalEffectors = new ArrayList<>();

	private List<String> localEffectorFilter = new ArrayList<>();

	private List<String> embeddedEffectors = new ArrayList<>();

	private String signature;
	private boolean enabled = true;

	private boolean wip = false;

	private Set<Integer> vertices = new HashSet<>();

	private Set<Integer> faces = new HashSet<>();

	private Set<Integer> itemIds = new HashSet<>();

	private Set<Integer> animationIds = new HashSet<>();

	private String animFrames = "";

	private int animFrameStart = -1;
	private int animFrameEnd = -1;

	private String definitionId;

	private Map<String, Integer> particles = new LinkedHashMap<>();

	private int color = 0x96FF981F;

	private boolean colorFade = false;

	private int colorEnd = 0x96FF981F;

	private int colorFadeStart = 0;

	private Shape shape = Shape.DEFAULT;

	private int size = 6;

	private int sizeJitter = 0;
	private int particlesPerSecond = 24;

	private int trailDensity = 0;
	private int lifetimeMs = 600;

	private int riseSpeed = 12;

	private int spreadSpeed = 6;

	private int gravity = 0;

	private int windX = 0;
	private int windY = 0;
	private int windZ = 0;

	private int drag = 0;

	private int vortex = 0;

	private int emitScale = 100;

	private int stretch = 0;

	private int stretchRamp = 0;

	private int spawnJitter = 6;

	private int featherStrength = 0;

	private int interpolation = 0;

	private int depthBias = 0;

	private int movementLifetime = 100;

	private boolean dynamicLifetime = false;

	private boolean feather = false;

	private int offsetX = 0;
	private int offsetY = 0;
	private int offsetZ = 0;

	private String textureFile = "";

	private int flipbookColumns = 0;

	private int flipbookRows = 0;

	private String flipbookMode = null;
	private float rotationSpeed = 0f;
	private boolean useEnvironmentLight = false;
	private boolean uniformColorVariation = true;
	private int scaleStartPercent = 100;
	private int scaleEndPercent = 100;

	EmitterProfile()
	{
	}

	EmitterProfile(String name)
	{
		this.name = name;
	}

	EmitterProfile copy()
	{
		EmitterProfile c = new EmitterProfile(name);
		c.targetType = targetType;
		c.projectileId = projectileId;
		c.objectId = objectId;
		c.npcId = npcId;
		c.graphicId = graphicId;
		c.signature = signature;
		c.enabled = enabled;
		c.wip = wip;
		c.vertices = new HashSet<>(vertices);
		c.faces = new HashSet<>(faces);
		c.definitionId = definitionId;
		c.particles = particles == null ? new LinkedHashMap<>() : new LinkedHashMap<>(particles);
		c.itemIds = new HashSet<>(itemIds);
		c.animationIds = new HashSet<>(animationIds);
		c.animFrames = animFrames;
		c.emitScale = emitScale;
		c.featherStrength = featherStrength;
		c.interpolation = interpolation;
		c.depthBias = depthBias;
		c.offsetX = offsetX;
		c.offsetY = offsetY;
		c.offsetZ = offsetZ;
		c.weatherAreas = weatherAreas == null ? new ArrayList<>() : new ArrayList<>(weatherAreas);
		c.weatherParticlesPerTile = weatherParticlesPerTile;
		c.weatherDensityScale = weatherDensityScale;
		c.globalEffectors = globalEffectors == null ? new ArrayList<>() : new ArrayList<>(globalEffectors);
		c.localEffectorFilter = localEffectorFilter == null ? new ArrayList<>() : new ArrayList<>(localEffectorFilter);
		c.embeddedEffectors = embeddedEffectors == null ? new ArrayList<>() : new ArrayList<>(embeddedEffectors);
		return c;
	}

	boolean isProjectileTarget()
	{
		return TARGET_PROJECTILE.equals(targetType);
	}

	boolean isObjectTarget()
	{
		return TARGET_OBJECT.equals(targetType);
	}

	boolean isNpcTarget()
	{
		return TARGET_NPC.equals(targetType);
	}

	boolean isGraphicTarget()
	{
		return TARGET_GRAPHIC.equals(targetType);
	}

	boolean isWeatherTarget()
	{
		return TARGET_WEATHER.equals(targetType);
	}

	Map<String, Integer> resolvedParticles()
	{
		LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
		if (particles != null)
		{
			for (Map.Entry<String, Integer> entry : particles.entrySet())
			{
				if (entry.getKey() == null || entry.getKey().isEmpty())
				{
					continue;
				}
				int weight = entry.getValue() == null ? 1 : entry.getValue();
				if (weight > 0)
				{
					result.put(entry.getKey(), weight);
				}
			}
		}
		if (result.isEmpty() && definitionId != null && !definitionId.isEmpty())
		{
			result.put(definitionId, 1);
		}
		return result;
	}

	void setParticles(Map<String, Integer> particles)
	{
		this.particles = particles == null ? new LinkedHashMap<>() : new LinkedHashMap<>(particles);
		syncPrimaryDefinitionId();
	}

	void setDefinitionId(String definitionId)
	{
		this.definitionId = definitionId;
		if (definitionId == null || definitionId.isEmpty())
		{
			return;
		}
		if (particles == null)
		{
			particles = new LinkedHashMap<>();
		}
		if (particles.isEmpty())
		{
			particles.put(definitionId, 1);
		}
		else if (!particles.containsKey(definitionId))
		{
			LinkedHashMap<String, Integer> next = new LinkedHashMap<>();
			next.put(definitionId, 1);
			next.putAll(particles);
			particles = next;
		}
	}

	private void syncPrimaryDefinitionId()
	{
		if (particles != null && !particles.isEmpty())
		{
			definitionId = particles.keySet().iterator().next();
		}
	}

	void copyStyleFrom(EmitterProfile other)
	{
		definitionId = other.definitionId;
		particles = other.particles == null ? new LinkedHashMap<>() : new LinkedHashMap<>(other.particles);
		color = other.color;
		colorFade = other.colorFade;
		colorEnd = other.colorEnd;
		colorFadeStart = other.colorFadeStart;
		shape = other.shape;
		size = other.size;
		sizeJitter = other.sizeJitter;
		particlesPerSecond = other.particlesPerSecond;
		trailDensity = other.trailDensity;
		lifetimeMs = other.lifetimeMs;
		riseSpeed = other.riseSpeed;
		spreadSpeed = other.spreadSpeed;
		gravity = other.gravity;
		windX = other.windX;
		windY = other.windY;
		windZ = other.windZ;
		drag = other.drag;
		vortex = other.vortex;
		emitScale = other.emitScale;
		stretch = other.stretch;
		stretchRamp = other.stretchRamp;
		spawnJitter = other.spawnJitter;
		featherStrength = other.featherStrength;
		interpolation = other.interpolation;
		depthBias = other.depthBias;
		movementLifetime = other.movementLifetime;
		offsetX = other.offsetX;
		offsetY = other.offsetY;
		offsetZ = other.offsetZ;
		textureFile = other.textureFile;
		flipbookColumns = other.flipbookColumns;
		flipbookRows = other.flipbookRows;
		flipbookMode = other.flipbookMode;
		rotationSpeed = other.rotationSpeed;
		useEnvironmentLight = other.useEnvironmentLight;
		uniformColorVariation = other.uniformColorVariation;
		scaleStartPercent = other.scaleStartPercent;
		scaleEndPercent = other.scaleEndPercent;
	}

	void copyStyleFrom(ParticleDefinition other)
	{
		other.applyToProfile(this);
	}
}
