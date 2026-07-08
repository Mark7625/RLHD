package rs117.hd.particles;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

/**
 * Emitter placement and gating for one target (player mesh piece, projectile,
 * object, NPC, or graphic). Particle look-and-motion lives in a referenced
 * {@link ParticleDefinition}.
 */
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
	/**
	 * What this profile emits from: a player model piece or a projectile.
	 */
	private String targetType = TARGET_PLAYER;
	/**
	 * Projectile (graphic) ID for projectile profiles; -1 otherwise.
	 */
	private int projectileId = -1;
	/**
	 * Scenery object ID for object profiles; -1 otherwise. Object profiles
	 * also carry a piece signature and vertices - the ID says which object,
	 * the signature says which piece of its model.
	 */
	private int objectId = -1;
	/**
	 * NPC ID for NPC profiles; -1 otherwise. Like objects: ID plus piece
	 * signature plus vertices.
	 */
	private int npcId = -1;
	/**
	 * Spot anim / graphics object ID for graphic profiles; -1 otherwise.
	 * Point-based: emits at any graphics object with this ID and on any
	 * actor playing it as a spot anim. No vertices.
	 */
	private int graphicId = -1;
	/**
	 * Named scene areas ({@code areas.json}) for weather profiles. Spawns
	 * procedurally across the area volume instead of mesh vertices.
	 */
	private List<String> weatherAreas = new ArrayList<>();
	/**
	 * Target alive particles per tile of inscribed area footprint; scaled to
	 * the global particle budget at runtime.
	 */
	private float weatherParticlesPerTile = 10f;
	/** Extra multiplier on weather density, applied after the global density slider. */
	private float weatherDensityScale = 1f;
	/**
	 * Topology signature of the mesh piece this profile attaches to. Multiple
	 * profiles may share a signature (e.g. one per recolored item variant).
	 * Null for non-player targets.
	 */
	private String signature;
	private boolean enabled = true;
	/**
	 * Author's work-in-progress mark: the profile stays saved but is hidden and
	 * force-disabled for shipped (non-developer) users, like a WIP category but
	 * per profile. Defaults false (shippable), so existing profiles and presets
	 * ship normally without a migration.
	 */
	private boolean wip = false;
	/**
	 * Id of the {@link ProfileFolder} this profile belongs to, or null when
	 * ungrouped. Folder membership; the folder's own settings live on the
	 * folder. Absent in old configs deserializes to null (ungrouped).
	 */
	private String folderId = null;
	private Set<Integer> vertices = new HashSet<>();
	/**
	 * Piece-local face indices (rank in the piece's ascending face list) to
	 * emit across. Empty = vertex-only placement. Survives model recomposition
	 * the same way {@link #vertices} do.
	 */
	private Set<Integer> faces = new HashSet<>();
	/**
	 * Item IDs gating this profile: only active while one of these items is
	 * worn. Empty = active on any item with this mesh. Distinguishes recolored
	 * variants that share the same model.
	 */
	private Set<Integer> itemIds = new HashSet<>();
	/**
	 * Animation IDs gating this profile: only emit while the player's action
	 * or pose animation is one of these. Empty = no animation gate. Combines
	 * with the item gate (both must pass).
	 */
	private Set<Integer> animationIds = new HashSet<>();
	/**
	 * Optional frame windows within a matched action animation, e.g.
	 * "9-13, 15-19" or "7"; blank = all frames. Ignored for pose animation
	 * matches.
	 */
	private String animFrames = "";
	/**
	 * Legacy single frame window, migrated into {@link #animFrames} on load.
	 */
	private int animFrameStart = -1;
	private int animFrameEnd = -1;

	/**
	 * Id of the {@link ParticleDefinition} that supplies look-and-motion.
	 * Migrated from inline style fields on load when absent.
	 */
	private String definitionId;

	// Legacy inline style fields: still deserialized from old presets.json and
	// kept in sync for export compatibility; runtime reads the definition map.
	/**
	 * Particle color as ARGB; alpha is the peak opacity of the life envelope.
	 */
	private int color = 0x96FF981F;
	/**
	 * Opt-in for colour-over-life. Off by default so a profile is a single
	 * constant colour unless the author explicitly enables the end colour.
	 */
	private boolean colorFade = false;
	/**
	 * End color as ARGB for colour-over-life: while {@link #colorFade} is on,
	 * particles fade from {@link #color} to this by death (fire cooling, magic
	 * settling). The RGB is interpolated; it shares the start's peak opacity.
	 */
	private int colorEnd = 0x96FF981F;
	/**
	 * Life percent at which the fade to {@link #colorEnd} begins: the colour
	 * holds at {@link #color} until here, then ramps to the end by death.
	 * 0 fades across the whole life; higher delays the shift to late in life.
	 */
	private int colorFadeStart = 0;
	/**
	 * Particle silhouette, baked as a per-face alpha mask over the shared
	 * disc geometry. Null on profiles saved before shapes existed, migrated
	 * to DEFAULT on load.
	 */
	private Shape shape = Shape.DEFAULT;
	/**
	 * Particle diameter in local units (a tile is 128).
	 */
	private int size = 6;
	/**
	 * Per-particle random size spread in local units around {@link #size}
	 * (0 = every particle the base size). Each particle draws a size in
	 * [size - sizeJitter, size + sizeJitter], floored at 2 (the minimum
	 * particle size). Realized as the low/base/high pre-baked size variants.
	 */
	private int sizeJitter = 0;
	private int particlesPerSecond = 24;
	/**
	 * Particles per tile of emitter movement, spread evenly along each
	 * anchor's path since the last tick. Spatially uniform emission for
	 * ribbon-like weapon trails; 0 = off. Combine with rate 0 for a pure
	 * trail that only emits while the anchor moves.
	 */
	private int trailDensity = 0;
	private int lifetimeMs = 600;
	/**
	 * Upward drift in local units per second.
	 */
	private int riseSpeed = 12;
	/**
	 * Horizontal drift and wobble speed in local units per second.
	 */
	private int spreadSpeed = 6;
	/**
	 * Downward acceleration in local units per second squared; 0 = constant
	 * velocity. Turns drift into falling: drops start slow and speed up, the
	 * signature of a blood or water drip.
	 */
	private int gravity = 0;
	/**
	 * Steady wind that carries every particle, in local units per second. X is
	 * east (+) / west (-), Y is north (+) / south (-), Z is up (+) / down (-).
	 * World-aligned, so it blows the same compass direction no matter which way
	 * the wearer faces. Leaning flames, drifting smoke, blowing snow, updrafts.
	 */
	private int windX = 0;
	private int windY = 0;
	private int windZ = 0;
	/**
	 * Air resistance: each particle sheds this percent of its own velocity per
	 * second, so rises, spreads, and falls settle over life instead of coasting
	 * forever (0 = none). Paired with gravity it yields a terminal fall speed.
	 */
	private int drag = 0;
	/**
	 * Radial velocity away from (+) or toward (-) the emitter's vertex centroid,
	 * in local units per second (0 = none). Positive blows particles outward
	 * like a burst; negative sucks them toward the centre like an implosion.
	 * Applies to vertex-ring emitters; single-point targets have no centre.
	 */
	private int vortex = 0;
	/**
	 * Scales the emitter's vertices about their centroid before emitting, as a
	 * percent (100 = unchanged). Below 100 draws the emit points together,
	 * above 100 pushes them apart - like scaling/fattening vertices in a mesh
	 * editor. Applies to vertex-ring emitters; no effect on single-point ones.
	 */
	private int emitScale = 100;
	/**
	 * Elongate the particle this percent along its screen-projected velocity
	 * (0 = round). Makes fast drops read as falling streaks. Auto-capped so
	 * the stretched billboard stays inside the render bounds.
	 */
	private int stretch = 0;
	/**
	 * How much of the particle's life it holds its round shape before
	 * stretching to the full amount (0 = stretched the whole time; 100 =
	 * starts round and elongates late, like a droplet letting go). The ramp
	 * is late-biased over the remaining life.
	 */
	private int stretchRamp = 0;
	/**
	 * Random spawn offset around the emitter vertex in local units.
	 */
	private int spawnJitter = 6;
	/**
	 * Feathered emission strength: spawn along a line chained through the
	 * emitter vertices, smoothed by averaging each point over this many
	 * neighbors to either side. 0 = off, 1 = light rounding, higher values
	 * cut across jagged notches into a soft continuous band.
	 */
	private int featherStrength = 0;
	/**
	 * Extra emitter points inserted between each pair of mesh-adjacent
	 * picked vertices (1 roughly doubles the emitter count). Densifies
	 * emission on low-poly meshes without feathering's smoothing.
	 */
	private int interpolation = 0;
	/**
	 * Nudge particles this many local units toward the camera at render
	 * time. Garment faces that win by render priority (a cape over a skirt)
	 * would otherwise swallow particles by plain depth; a small bias lets
	 * particles ride on top the way their host piece does.
	 */
	private int depthBias = 0;
	/**
	 * Percent of normal particle lifetime while the wearer's walk or run
	 * pose animation is playing (10-100). Shortening it keeps fast-moving
	 * plumes tight instead of smearing a tile behind the player.
	 */
	private int movementLifetime = 100;
	/**
	 * Legacy flag, migrated into {@link #movementLifetime} on load.
	 */
	private boolean dynamicLifetime = false;
	/**
	 * Legacy on/off flag, migrated into {@link #featherStrength} on load.
	 */
	private boolean feather = false;
	/**
	 * Fixed emit offset from the vertex in model space (rotates with the
	 * player's facing), local units. X is sideways, Y is forward/back,
	 * Z is up (positive).
	 */
	private int offsetX = 0;
	private int offsetY = 0;
	private int offsetZ = 0;
	/**
	 * PNG filename from the particles textures folder, or empty for the default
	 * procedural soft disc.
	 */
	private String textureFile = "";
	/**
	 * Flipbook grid columns; 0 disables flipbook animation.
	 */
	private int flipbookColumns = 0;
	/**
	 * Flipbook grid rows; 0 disables flipbook animation.
	 */
	private int flipbookRows = 0;
	/**
	 * Flipbook frame selection: null/empty = off, "order" = advance over life,
	 * "random" = pick one frame at spawn.
	 */
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
		c.folderId = folderId;
		c.vertices = new HashSet<>(vertices);
		c.faces = new HashSet<>(faces);
		c.definitionId = definitionId;
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

	/**
	 * Copy particle style fields from another profile (legacy) or definition
	 * source during paste / migration.
	 */
	void copyStyleFrom(EmitterProfile other)
	{
		definitionId = other.definitionId;
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
