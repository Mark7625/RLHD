package rs117.hd.particles;

import com.google.gson.Gson;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ActorSpotAnim;
import net.runelite.api.Animation;
import net.runelite.api.AnimationController;
import net.runelite.api.Client;
import net.runelite.api.DecorativeObject;
import net.runelite.api.DynamicObject;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.GraphicsObject;
import net.runelite.api.GroundObject;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.Projectile;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.DecorativeObjectDespawned;
import net.runelite.api.events.DecorativeObjectSpawned;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.GraphicsObjectCreated;
import net.runelite.api.events.GroundObjectDespawned;
import net.runelite.api.events.GroundObjectSpawned;
import net.runelite.api.events.NpcChanged;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.WallObjectDespawned;
import net.runelite.api.events.WallObjectSpawned;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import static net.runelite.api.Perspective.LOCAL_TILE_SIZE;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.renderer.zone.ZoneRenderer;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.scene.AreaManager;
import rs117.hd.scene.GamevalManager;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.areas.AABB;
import rs117.hd.scene.areas.Area;

@Slf4j
@Singleton
public class ParticlesManager implements ModelViewerFrame.Callbacks
{
	/** Must match {@link ParticlePass} billboard draw cap. */
	static final int MAX_DRAWN_PARTICLES = 2048;
	/**
	 * One enabled emitter profile resolved onto the current model: its style,
	 * global vertex indices, optional face triangles, spawn accumulator, and
	 * its slice of the shared anchor arrays.
	 */
	private static class ActiveEmitter
	{
		final ParticleStyle style;
		final int[] vertices;
		/**
		 * Global face corner triplets {@code [f0a,f0b,f0c, f1a,...]}; empty
		 * when the profile has no triangle emitters.
		 */
		final int[] faceCorners;
		/**
		 * Feathering: emitter vertices chained along mesh edges, as anchor
		 * offsets within this emitter; null when feathering is off.
		 */
		final int[][] chains;
		/**
		 * O(1) uniform sampling over all chain positions.
		 */
		final int[] sampleChainOf;
		final int[] samplePosOf;
		/**
		 * Interpolated midpoints appended after the real anchors, one set
		 * per chain segment; zero when interpolation is off.
		 */
		final int extraAnchors;
		double carry;
		int anchorStart;
		int anchorCount;
		/**
		 * Every real vertex resolved an anchor this tick, so chain indices
		 * line up for feathering and interpolation.
		 */
		boolean featherReady;
		/**
		 * Centroid of this tick's anchors, computed only when the style needs
		 * it (emit scale or vortex active). The reference point both effects
		 * are measured from.
		 */
		float cx;
		float cy;
		float cz;
		/**
		 * World positions of face corners this tick ({@code faceCorners.length}),
		 * filled during anchoring so emit can barycentric-sample triangles.
		 */
		float[] faceWorldX = EMPTY_FLOATS;
		float[] faceWorldY = EMPTY_FLOATS;
		float[] faceWorldZ = EMPTY_FLOATS;

		ActiveEmitter(ParticleStyle style, int[] vertices, int[][] chains)
		{
			this(style, vertices, EMPTY_INTS, chains);
		}

		ActiveEmitter(ParticleStyle style, int[] vertices, int[] faceCorners, int[][] chains)
		{
			this.style = style;
			this.vertices = vertices;
			this.faceCorners = faceCorners == null ? EMPTY_INTS : faceCorners;
			this.chains = chains;
			int extra = 0;
			if (chains != null && style.getInterpolation() > 0)
			{
				for (int[] chain : chains)
				{
					extra += Math.max(0, chain.length - 1);
				}
				extra *= style.getInterpolation();
			}
			this.extraAnchors = extra;
			if (chains != null)
			{
				int total = 0;
				for (int[] chain : chains)
				{
					total += chain.length;
				}
				sampleChainOf = new int[total];
				samplePosOf = new int[total];
				int k = 0;
				for (int c = 0; c < chains.length; c++)
				{
					for (int j = 0; j < chains[c].length; j++)
					{
						sampleChainOf[k] = c;
						samplePosOf[k] = j;
						k++;
					}
				}
			}
			else
			{
				sampleChainOf = null;
				samplePosOf = null;
			}
		}

		int faceCount()
		{
			return faceCorners.length / 3;
		}

		boolean hasEmitSites()
		{
			return anchorCount > 0 || faceCount() > 0;
		}
	}

	private static final int[] EMPTY_INTS = new int[0];
	private static final float[] EMPTY_FLOATS = new float[0];

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private HdPluginConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private Gson gson;

	@Inject
	private EventBus eventBus;

	@Inject
	private HdPlugin hdPlugin;

	@Inject
	private GamevalManager gamevalManager;

	@Inject
	private AreaManager areaManager;

	@Inject
	private FrameTimer frameTimer;

	/**
	 * Preview the shipped experience from a developer client: treats
	 * developer mode as off everywhere (authoring UI, WIP category gating).
	 * The dev harness always launches with developer mode on, so this is the
	 * only way to see the player view locally. Safe to ship true by accident
	 * - players already run without developer mode.
	 */
	private static final boolean PREVIEW_PLAYER_VIEW = false;

	/**
	 * Authoring tools (vertex picker, profile edit controls) only exist in
	 * developer mode; shipped users get read-only presets with toggles.
	 */
	@Inject
	@Named("developerMode")
	private boolean developerMode;

	private final Random random = new Random();

	@Getter
	private final ParticleSystem particleSystem = new ParticleSystem();

	private ParticleRenderer renderer;
	private EmitterStore store;
	private ModelViewerFrame viewerFrame;

	/**
	 * Per-player emitter state: resolution cache against that player's gear,
	 * transformed anchors, and trail history. Every player in the scene gets
	 * particles, not just the local one. Client thread.
	 */
	private static class PlayerEmitters
	{
		final List<ActiveEmitter> emitters = new ArrayList<>();
		int[] equipmentIds;
		int revision = -1;
		float[] anchorXs = new float[0];
		float[] anchorYs = new float[0];
		/**
		 * Absolute scene z (negative up), based on the same base height the
		 * engine renders the model at, so anchors stay glued on slopes.
		 */
		float[] anchorZs = new float[0];
		// Last tick's anchors: spawns spread along each anchor's movement
		// segment so fast-moving emitters lay a smooth trail, not clumps
		float[] prevXs = new float[0];
		float[] prevYs = new float[0];
		float[] prevZs = new float[0];
		/**
		 * Fractional carry of distance-based emission per anchor slot.
		 */
		float[] trailCarry = new float[0];
		/**
		 * Rate carries for point emission from the actor's active spot
		 * anims, by graphic ID. Lazily created; most actors have none.
		 */
		@Nullable
		Map<Integer, double[]> spotAnimCarries;
		int anchorCount;
		int prevCount;
		boolean rebuilt;
		int stamp;
	}

	private final Map<Player, PlayerEmitters> playerEmitters = new HashMap<>();
	private int playerStamp;
	/**
	 * Mirror of the engine's stacked-actor claim (tileLastDrawnActor): among
	 * players standing exactly at tile center, one draws per tile, with
	 * precedence local player -> the local player's player combat target ->
	 * size-1 centered NPCs -> remaining players by ascending player index.
	 * Field-validated with the hover-menu oracle (see updateStackOracle):
	 * idle stacks matched ascending Player.getId() consistently, and getId()
	 * agreed with the menu identifiers everywhere tested. This map holds the
	 * lowest-index centered player per tile; the precedence layers above it
	 * are applied in the drawn gate. Reused per tick.
	 */
	private final Map<Long, Player> tileOwners = new HashMap<>();
	/**
	 * Tiles claimed by a size-1 NPC standing at their center; the engine's
	 * NPC pass runs before non-local players, so those players aren't drawn.
	 */
	private final Set<Long> npcClaimedTiles = new HashSet<>();
	/**
	 * The drawn NPC per stacked tile: among size-1 NPCs at a tile center, our
	 * current-best rule for which the engine draws (highest scene index; the
	 * true rule is still being validated with the NPC stack oracle). Undrawn
	 * stack members are gated out of emission so their particles don't double
	 * up on the drawn npc. Reused per tick.
	 */
	private final Map<Long, NPC> npcTileOwners = new HashMap<>();
	/**
	 * This tick's higher-precedence claims: the local player's tile beats
	 * everyone on it, and their player combat target's tile beats NPC claims
	 * and index order.
	 */
	private long localClaimKey = Long.MIN_VALUE;
	private long targetClaimKey = Long.MIN_VALUE;
	@Nullable
	private Player localClaimTarget;

	/**
	 * Per-instance emitter state for tracked scenery. Objects are static or
	 * engine-animated in place, so there is no trail, movement or stack
	 * state - just resolved emitters and this tick's anchors. Client thread.
	 */
	private static class ObjectEmitters
	{
		final List<ActiveEmitter> emitters = new ArrayList<>();
		float[] anchorXs = new float[0];
		float[] anchorYs = new float[0];
		float[] anchorZs = new float[0];
		int anchorCount;
		int revision = -1;
		boolean loggedResolveMiss;
	}

	/**
	 * Tracked scenery instances, maintained by spawn/despawn events plus a
	 * scene rescan when the profile store changes. Client thread.
	 */
	private final Map<TileObject, ObjectEmitters> objectEmitters = new HashMap<>();
	/**
	 * Object IDs that carry at least one object profile; spawns of anything
	 * else are ignored without allocation.
	 */
	private Set<Integer> profiledObjectIds = Set.of();
	private int profiledIdsRevision = -1;

	/**
	 * Tracked NPC instances; NPCs reuse the player emitter state since they
	 * move and animate the same way. Client thread.
	 */
	private final Map<NPC, PlayerEmitters> npcEmitters = new HashMap<>();
	private Set<Integer> profiledNpcIds = Set.of();

	/**
	 * One enabled graphic profile resolved for emission: point-based when no
	 * vertices were picked, otherwise anchored to the spot anim model's
	 * piece vertices. Graphic models are single cache models, so the global
	 * indices resolved from the first instance hold for every instance.
	 */
	private static class GraphicEmitter
	{
		final ParticleStyle style;
		@Nullable
		final String signature;
		final int[] locals;
		final int[] faceLocals;
		/**
		 * One resolved emitter per matching piece: vertices are model
		 * globals, chains present when feathering.
		 */
		final List<ActiveEmitter> resolved = new ArrayList<>();
		boolean resolveTried;

		GraphicEmitter(ParticleStyle style, @Nullable String signature, int[] locals, int[] faceLocals)
		{
			this.style = style;
			this.signature = signature;
			this.locals = locals;
			this.faceLocals = faceLocals == null ? EMPTY_INTS : faceLocals;
		}
	}

	/**
	 * Enabled graphic profiles by spot anim ID: emission at graphics objects
	 * and on actors playing the spot anim. Client thread.
	 */
	private final Map<Integer, List<GraphicEmitter>> graphicEmitters = new HashMap<>();
	/**
	 * Rate carries per live graphics object, swept against the live set.
	 */
	private final Map<GraphicsObject, double[]> graphicCarries = new HashMap<>();
	private final Set<GraphicsObject> liveGraphics = new HashSet<>();
	/**
	 * Recently seen spot anim / graphics IDs for the capture list, as
	 * id -> [count, lastSeenMs].
	 */
	private final Map<Integer, long[]> recentGraphics = new LinkedHashMap<>();
	/**
	 * Last-seen source label per graphic ID (the actor who played it, or
	 * "tile"); spot anims have no cache name, so this stands in for one.
	 */
	private final Map<Integer, String> recentGraphicSource = new HashMap<>();
	/**
	 * Graphic ID armed for deferred viewer capture: spell gfx are gone
	 * before Load can be clicked, so the mesh is grabbed the next time the
	 * graphic plays. -1 when idle. Client thread.
	 */
	private int pendingGraphicCapture = -1;

	// Debug marker aggregate across all players, read by the overlay; only
	// filled while markers are shown. Client thread.
	@Getter
	private int anchorCount;
	@Getter
	private float[] anchorXs = new float[0];
	@Getter
	private float[] anchorYs = new float[0];
	@Getter
	private float[] anchorZs = new float[0];
	@Getter
	private int anchorWorldView = -1;
	@Getter
	private int anchorLevel;
	/**
	 * Smoothed feather paths for the debug overlay, one flat [x,y,z, ...] per
	 * chain. Rebuilt per tick only while markers are shown.
	 */
	@Getter
	private final List<float[]> featherDebugPaths = new ArrayList<>();
	/**
	 * Segments longer than this are teleports/rebinds, not movement.
	 */
	private static final float MAX_TRAIL_SEGMENT = 256f;

	/**
	 * The last snapshot loaded into the viewer; vertex toggles resolve their
	 * piece against it. EDT only.
	 */
	private ModelSnapshot viewerSnapshot;

	/**
	 * The scenery object or NPC ID the viewer's snapshot was captured from,
	 * or -1 when it shows the player. Decides which profile family vertex
	 * clicks create. EDT only; snapshot captures receive them as parameters.
	 */
	private int viewerObjectId = -1;
	private int viewerNpcId = -1;
	private int viewerGraphicId = -1;
	/**
	 * Game name of the loaded object or NPC, resolved at capture time on the
	 * client thread; null when unavailable. New profiles are named after it.
	 * EDT only.
	 */
	@Nullable
	private String viewerTargetName;

	// Animation recording for the viewer scrubber: per-tick vertex position
	// samples over one topology, plus each sample's animation frame.
	// Client thread.
	private int recordTicksLeft;
	private int recordObjectId = -1;
	private int recordNpcId = -1;
	private int recordGraphicId = -1;
	@Nullable
	private TileObject recordObject;
	@Nullable
	private NPC recordNpc;
	@Nullable
	private ModelSnapshot recordSnapshot;
	private final List<float[]> recordXs = new ArrayList<>();
	private final List<float[]> recordYs = new ArrayList<>();
	private final List<float[]> recordZs = new ArrayList<>();
	private final List<Integer> recordFrames = new ArrayList<>();

	/**
	 * Piece signatures present on the currently worn model; written on the
	 * client thread, read by the sidebar for its "worn" indicator.
	 */
	private volatile Set<String> presentSignatures = Set.of();

	/**
	 * A projectile-targeted profile that passed its gear gate; matched
	 * against active projectiles by ID every tick.
	 */
	private static class ActiveProjectileProfile
	{
		final int projectileId;
		final ParticleStyle style;

		ActiveProjectileProfile(int projectileId, ParticleStyle style)
		{
			this.projectileId = projectileId;
			this.style = style;
		}
	}

	/**
	 * Per-projectile state: last tick's position for trail segments, and
	 * fractional emission carries per matched style.
	 */
	private static class ProjectileTracker
	{
		float prevX, prevY, prevZ;
		boolean prevValid;
		int stamp;
		final Map<ParticleStyle, double[]> carries = new HashMap<>();
	}

	// Projectile emission state; profile list is gear-gated by the local
	// player and rebuilt with their resolution. Client thread.
	private final List<ActiveProjectileProfile> activeProjectileProfiles = new ArrayList<>();
	private final Map<Projectile, ProjectileTracker> projectileTrackers = new HashMap<>();
	private int projectileStamp;

	/**
	 * One AABB cylinder per enabled weather profile; spawn rate comes from
	 * {@link EmitterProfile#getWeatherParticlesPerTile()}.
	 */
	private static class ActiveWeatherZone
	{
		final AABB aabb;
		final ParticleStyle style;
		final float particlesPerTile;
		final float densityScale;
		double spawnAccum;

		ActiveWeatherZone(AABB aabb, ParticleStyle style, float particlesPerTile, float densityScale)
		{
			this.aabb = aabb;
			this.style = style;
			this.particlesPerTile = particlesPerTile;
			this.densityScale = densityScale;
		}
	}

	private final List<ActiveWeatherZone> activeWeatherZones = new ArrayList<>();
	/**
	 * Recently seen projectile IDs -> [count, lastSeenMs], for the picker's
	 * capture list. Client thread.
	 */
	private final LinkedHashMap<Integer, long[]> recentProjectiles = new LinkedHashMap<>();
	private int stylesRevision = -1;

	// Reused consumers; a method reference expression allocates per evaluation
	private final java.util.function.Consumer<Particle> deathStats = this::onParticleDeath;
	private static final java.util.function.Consumer<Particle> DISCARD = p ->
	{
	};

	private long lastNanos;
	private int lastLevel = -1;

	// Diagnostics, aggregated over one-second windows and shown by the overlay
	private long statsWindowStart;
	private int windowSpawns;
	private int windowDeaths;
	private float windowDeathAgeSum;
	/**
	 * Most recent non-idle action animation, sticky so short animations can be
	 * read off the stats line for authoring animation gates.
	 */
	private int lastActionAnimation = -1;
	@Getter
	private String statsLine = "";
	@Getter
	private int statAlive;
	@Getter
	private int statMax;
	@Getter
	private int statSpawnsPerSec;
	@Getter
	private int statVisibleEmitters;
	@Getter
	private int statInView;
	@Getter
	private int statInViewMax;
	@Getter
	private long statCpuNanos;
	@Getter
	private long statLastCpuNanos;
	private int activeWeatherZoneCount;
	private long cpuAccumNanos;
	private int cpuSampleCount;
	/**
	 * Stack-oracle diagnostic line for the hovered tile; empty outside
	 * developer mode. See {@link #updateStackOracle}.
	 */
	@Getter
	private String oracleLine = "";
	private String lastOracleDump = "";
	/**
	 * NPC stacked-draw oracle line for the hovered tile; empty outside
	 * developer mode. See {@link #updateNpcStackOracle}.
	 */
	@Getter
	private String npcOracleLine = "";
	private String lastNpcOracleDump = "";

	public void startUp()
	{
		eventBus.register(this);
		developerMode &= !PREVIEW_PLAYER_VIEW;
		lastNanos = System.nanoTime();
		statsWindowStart = lastNanos;
		stylesRevision = -1;
		playerEmitters.clear();
		// Migration: the Just me checkbox became the Apply to dropdown
		if ("true".equals(configManager.getConfiguration(HdPluginConfig.CONFIG_GROUP, "justMe"))
			&& configManager.getConfiguration(HdPluginConfig.CONFIG_GROUP, "particleApplyTo") == null)
		{
			configManager.setConfiguration(HdPluginConfig.CONFIG_GROUP, "particleApplyTo", HdPluginConfig.ParticleApplyTo.ME);
		}
		configManager.unsetConfiguration(HdPluginConfig.CONFIG_GROUP, "justMe");

		renderer = new ParticleRenderer(client);
		store = new EmitterStore(configManager, gson, developerMode, gamevalManager);
		store.load();
		store.setChangeListener(this::refreshViewer);
		if (developerMode)
		{
			store.startWatching(clientThread);
		}

		log.debug("Particles started");
	}

	public void shutDown()
	{
		eventBus.unregister(this);
		if (store != null)
		{
			store.shutDownWatching();
		}

		SwingUtilities.invokeLater(() ->
		{
			if (viewerFrame != null)
			{
				viewerFrame.dispose();
				viewerFrame = null;
			}
			viewerSnapshot = null;
		});

		// Capture the instance: a quick disable-enable reassigns the field
		// before this lambda runs, which would reset the NEW renderer and
		// orphan the old one's still-active scene objects
		final ParticleRenderer stopped = renderer;
		clientThread.invokeLater(() ->
		{
			particleSystem.clear(DISCARD);
			stopped.reset();
			anchorCount = 0;
			playerEmitters.clear();
			objectEmitters.clear();
			npcEmitters.clear();
			graphicCarries.clear();
			recentGraphics.clear();
			recentGraphicSource.clear();
			pendingGraphicCapture = -1;
			projectileTrackers.clear();
			activeProjectileProfiles.clear();
			activeWeatherZones.clear();
			recentProjectiles.clear();
			featherDebugPaths.clear();
		});

		log.debug("Particles stopped");
	}

	private boolean useGpuRendering() {
		return client.isGpu() && hdPlugin.renderer instanceof ZoneRenderer;
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		// particle sidebar removed; config hook kept for future use
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		trackObject(event.getGameObject());
	}

	@Subscribe
	public void onGameObjectDespawned(GameObjectDespawned event)
	{
		objectEmitters.remove(event.getGameObject());
	}

	@Subscribe
	public void onWallObjectSpawned(WallObjectSpawned event)
	{
		trackObject(event.getWallObject());
	}

	@Subscribe
	public void onWallObjectDespawned(WallObjectDespawned event)
	{
		objectEmitters.remove(event.getWallObject());
	}

	@Subscribe
	public void onDecorativeObjectSpawned(DecorativeObjectSpawned event)
	{
		trackObject(event.getDecorativeObject());
	}

	@Subscribe
	public void onDecorativeObjectDespawned(DecorativeObjectDespawned event)
	{
		objectEmitters.remove(event.getDecorativeObject());
	}

	@Subscribe
	public void onGroundObjectSpawned(GroundObjectSpawned event)
	{
		trackObject(event.getGroundObject());
	}

	@Subscribe
	public void onGroundObjectDespawned(GroundObjectDespawned event)
	{
		objectEmitters.remove(event.getGroundObject());
	}

	private void trackObject(TileObject object)
	{
		if (profiledObjectIds.contains(object.getId()))
		{
			objectEmitters.putIfAbsent(object, new ObjectEmitters());
		}
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		trackNpc(event.getNpc());
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		npcEmitters.remove(event.getNpc());
	}

	@Subscribe
	public void onNpcChanged(NpcChanged event)
	{
		// Transmog changed the ID; drop state and re-evaluate tracking
		npcEmitters.remove(event.getNpc());
		trackNpc(event.getNpc());
	}

	private void trackNpc(NPC npc)
	{
		if (profiledNpcIds.contains(npc.getId()))
		{
			npcEmitters.putIfAbsent(npc, new PlayerEmitters());
		}
	}

	@Subscribe
	public void onGraphicsObjectCreated(GraphicsObjectCreated event)
	{
		noteGraphic(event.getGraphicsObject().getId(), "tile");
	}

	@Subscribe
	public void onGraphicChanged(GraphicChanged event)
	{
		String source = actorLabel(event.getActor());
		for (ActorSpotAnim spotAnim : event.getActor().getSpotAnims())
		{
			noteGraphic(spotAnim.getId(), source);
		}
	}

	/**
	 * Dev-mode sweep for the capture list: live graphics objects and every
	 * actor's spot anim list. Events alone miss list-borne spot anims (the
	 * GraphicChanged event tracks only the legacy single-graphic slot) and
	 * short-lived entries can be evicted before the list is read.
	 */
	private void pollGraphicSightings()
	{
		for (GraphicsObject graphic : client.getTopLevelWorldView().getGraphicsObjects())
		{
			if (!graphic.finished())
			{
				noteGraphic(graphic.getId(), "tile");
			}
		}
		for (Player p : client.getTopLevelWorldView().players())
		{
			if (p != null)
			{
				String source = actorLabel(p);
				for (ActorSpotAnim spotAnim : p.getSpotAnims())
				{
					noteGraphic(spotAnim.getId(), source);
				}
			}
		}
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (npc != null)
			{
				String source = actorLabel(npc);
				for (ActorSpotAnim spotAnim : npc.getSpotAnims())
				{
					noteGraphic(spotAnim.getId(), source);
				}
			}
		}
	}

	/**
	 * Record a spot anim / graphics ID sighting for the capture list,
	 * evicting the stalest entry past the cap.
	 */
	private void noteGraphic(int id, String source)
	{
		if (!developerMode)
		{
			// The capture list is authoring-only; skip the bookkeeping for
			// shipped users, who can never open the picker to read it
			return;
		}
		long[] seen = recentGraphics.get(id);
		if (seen != null)
		{
			seen[0]++;
			seen[1] = System.currentTimeMillis();
			if (source != null && !"tile".equals(source))
			{
				// Prefer a named actor source over the generic tile label
				recentGraphicSource.put(id, source);
			}
			return;
		}
		if (recentGraphics.size() >= 24)
		{
			Integer oldestId = null;
			long oldestSeen = Long.MAX_VALUE;
			for (Map.Entry<Integer, long[]> entry : recentGraphics.entrySet())
			{
				if (entry.getValue()[1] < oldestSeen)
				{
					oldestSeen = entry.getValue()[1];
					oldestId = entry.getKey();
				}
			}
			recentGraphics.remove(oldestId);
			recentGraphicSource.remove(oldestId);
		}
		recentGraphics.put(id, new long[]{1, System.currentTimeMillis()});
		recentGraphicSource.put(id, source);
	}

	/**
	 * A short label for a graphic's source: the actor's name, else "player"
	 * or "npc" when unnamed.
	 */
	private static String actorLabel(Actor actor)
	{
		String name = cleanTargetName(actor.getName());
		if (name != null)
		{
			return name;
		}
		return actor instanceof NPC ? "npc" : "player";
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		switch (event.getGameState())
		{
			case LOADING:
				// The scene is rebasing; live particles' local coordinates
				// become meaningless, and scenery respawns with fresh events
				particleSystem.clear(DISCARD);
				renderer.reset();
				objectEmitters.clear();
				npcEmitters.clear();
				graphicCarries.clear();
				break;
			case LOGGED_IN:
				// Player models are rebuilt on scene load and their vertex
				// indices can change; re-resolve emitters against the new
				// models or emission goes subtly wrong until gear is re-equipped.
				// Invalidate only - the draw-order mirror must survive, since
				// these players never left the engine's processing list.
				invalidateResolutions();
				break;
			default:
				break;
		}
	}

	@Subscribe
	public void onClientTick(ClientTick tick)
	{
		long now = System.nanoTime();
		float dt = (now - lastNanos) / 1_000_000_000f;
		lastNanos = now;
		// Clamp dt so hitches don't cause bursts or huge integration steps
		dt = Math.min(dt, 0.1f);

		// Rebuild styles when profiles change; batches pick the new templates
		// up immediately since they're re-merged every tick. Read the revision
		// BEFORE the snapshot: an edit landing in between then leaves
		// stylesRevision behind, forcing a clean rebuild next tick instead of
		// recording content it never saw
		int storeRevision = store.getRevision();
		if (stylesRevision != storeRevision && renderer.rebuildStyles(store.snapshotAll(), store.snapshotDefinitions()))
		{
			stylesRevision = storeRevision;
			// Emitters hold style references; re-resolve everyone
			invalidateResolutions();
			rebuildWeatherZones();
		}
		// Gate on renderer readiness so styles resolve on the first rebuild;
		// before the particle mesh loads, getStyle would return null forever
		if (renderer.isReady() && profiledIdsRevision != storeRevision)
		{
			profiledIdsRevision = storeRevision;
			rebuildProfiledObjects();
			rebuildProfiledNpcs();
			rebuildGraphicStyles();
			rebuildWeatherZones();
		}

		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null)
		{
			anchorCount = 0;
			playerEmitters.clear();
			particleSystem.clear(DISCARD);
			renderer.reset();
			lastLevel = -1;
			return;
		}

		// Drop all particles when the scene plane changes; their positions
		// are only meaningful on the level they spawned on
		int level = localPlayer.getWorldView().getPlane();
		if (level != lastLevel)
		{
			particleSystem.clear(DISCARD);
			// Break trail continuity too: the inter-floor height (~240) is
			// under MAX_TRAIL_SEGMENT, so stale prev anchors would smear a
			// vertical streak between floors on the first tick up a staircase
			for (PlayerEmitters pe : playerEmitters.values())
			{
				pe.prevCount = 0;
				Arrays.fill(pe.trailCarry, 0f);
			}
			lastLevel = level;
		}
		anchorWorldView = localPlayer.getLocalLocation().getWorldView();
		anchorLevel = level;

		// Debug aggregates rebuilt during the player loop
		// Marker circles are a dev authoring aid; users only get the text line
		boolean markers = false;
		anchorCount = 0;
		featherDebugPaths.clear();

		// Stacked-actor claim mirror (tileLastDrawnActor /
		// tileLastOccupiedCycle): the engine's dedup only applies to actors
		// standing exactly at tile center - movers and the local player
		// always draw. The winner precedence below was field-validated with
		// the hover-menu oracle after the original deob-derived mirror
		// failed; see updateStackOracle.
		playerStamp++;
		tileOwners.clear();
		npcClaimedTiles.clear();
		npcTileOwners.clear();

		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (npc == null || npc.getWorldLocation().getPlane() != level)
			{
				continue;
			}
			NPCComposition composition = npc.getTransformedComposition();
			if (composition == null || composition.getSize() != 1)
			{
				continue;
			}
			LocalPoint lp = npc.getLocalLocation();
			if ((lp.getX() & 127) != 64 || (lp.getY() & 127) != 64)
			{
				continue;
			}
			long key = ((long) (lp.getX() >> 7) << 32) | ((lp.getY() >> 7) & 0xffffffffL);
			npcClaimedTiles.add(key);
			// Current draw-winner hypothesis among stacked NPCs: highest scene
			// index (lowest was falsified by the oracle). Still being pinned
			// down - see updateNpcStackOracle, which scores it live.
			NPC owner = npcTileOwners.get(key);
			if (owner == null || npc.getIndex() > owner.getIndex())
			{
				npcTileOwners.put(key, npc);
			}
		}
		for (Player player : client.getTopLevelWorldView().players())
		{
			if (player == null)
			{
				continue;
			}
			PlayerEmitters pe = playerEmitters.computeIfAbsent(player, p -> new PlayerEmitters());
			pe.stamp = playerStamp;

			if (player.getWorldLocation().getPlane() != level || !isCentered(player))
			{
				continue;
			}
			long key = tileKey(player);
			Player current = tileOwners.get(key);
			if (current == null || player.getId() < current.getId())
			{
				tileOwners.put(key, player);
			}
		}

		localClaimKey = isCentered(localPlayer) ? tileKey(localPlayer) : Long.MIN_VALUE;
		localClaimTarget = localPlayer.getInteracting() instanceof Player
			? (Player) localPlayer.getInteracting() : null;
		targetClaimKey = localClaimTarget != null
			&& localClaimTarget.getWorldLocation().getPlane() == level
			&& isCentered(localClaimTarget)
			? tileKey(localClaimTarget) : Long.MIN_VALUE;

		HdPluginConfig.ParticleApplyTo applyTo = config.particleApplyTo();
		int radiusUnits = config.particleEffectRadius() * 128;
		LocalPoint localLp = localPlayer.getLocalLocation();

		// Every provably drawn player emits, not just the local one.
		// players() spans all four planes of the scene, but the client only
		// draws the current one - other planes must not emit at all.
		for (Player player : client.getTopLevelWorldView().players())
		{
			if (player == null)
			{
				continue;
			}
			PlayerEmitters pe = playerEmitters.get(player);
			if (pe == null)
			{
				continue;
			}
			boolean drawn;
			if (player != localPlayer
				&& (applyTo == HdPluginConfig.ParticleApplyTo.ME
					|| (applyTo == HdPluginConfig.ParticleApplyTo.FRIENDS && !player.isFriend())
					|| player.getLocalLocation().distanceTo(localLp) > radiusUnits))
			{
				// Out of scope by user preference; the hidden branch below
				// still clears their state so re-entry starts clean
				drawn = false;
			}
			else if (player.getWorldLocation().getPlane() != level)
			{
				drawn = false;
			}
			else if (!isCentered(player) || player == localPlayer)
			{
				drawn = true;
			}
			else
			{
				// Engine claim precedence for a centered stack member; movers
				// and the local player were already granted above
				long key = tileKey(player);
				if (key == localClaimKey)
				{
					drawn = false;
				}
				else if (key == targetClaimKey)
				{
					drawn = player == localClaimTarget;
				}
				else if (npcClaimedTiles.contains(key))
				{
					drawn = false;
				}
				else
				{
					drawn = tileOwners.get(key) == player;
				}
			}
			if (!drawn)
			{
				// Hidden under a stack: stop emitting and break trail
				// continuity so un-stacking doesn't lerp from stale positions
				pe.anchorCount = 0;
				pe.prevCount = 0;
				for (ActiveEmitter emitter : pe.emitters)
				{
					emitter.carry = 0;
				}
				continue;
			}

			resolvePlayer(pe, player);
			updateAnchors(pe, player, markers);
			emit(dt, pe, player);
			emitActorSpotAnims(dt, pe, player);
		}
		Iterator<PlayerEmitters> peIt = playerEmitters.values().iterator();
		while (peIt.hasNext())
		{
			if (peIt.next().stamp != playerStamp)
			{
				peIt.remove();
			}
		}

		if (pendingGraphicCapture >= 0)
		{
			int armedGraphic = pendingGraphicCapture;
			Model pendingModel = findGraphicModel(armedGraphic);
			if (pendingModel != null)
			{
				pushGraphicSnapshot(armedGraphic, pendingModel);
			}
		}
		if (recordTicksLeft > 0)
		{
			sampleRecording();
		}
		if (developerMode)
		{
			pollGraphicSightings();
		}

		processObjects(dt, level, radiusUnits, localLp);
		processNpcs(dt, level, radiusUnits, localLp);
		emitGraphicsObjects(dt, level, radiusUnits, localLp);
		emitProjectiles(dt);
		processWeather(dt);
		frameTimer.begin(Timer.UPDATE_PARTICLES);
		long cpuStart = System.nanoTime();
		try
		{
			particleSystem.update(dt, deathStats);
			particleSystem.clipGround(anchorWorldView, client, deathStats);
			if (!useGpuRendering())
			{
				renderer.sync(particleSystem.getParticles(), anchorWorldView, anchorLevel);
				statInView = renderer.getLastDrawnParticles();
				statInViewMax = MAX_DRAWN_PARTICLES;
			}
		}
		finally
		{
			frameTimer.end(Timer.UPDATE_PARTICLES);
			long cpuElapsed = System.nanoTime() - cpuStart;
			statLastCpuNanos = cpuElapsed;
			cpuAccumNanos += cpuElapsed;
			cpuSampleCount++;
		}

		statAlive = particleSystem.getParticles().size();
		statMax = config.particleMaxParticles();
		statVisibleEmitters = countVisibleEmitters();
		updateStats(now);
	}

	void recordRenderStats(int inView, int inViewMax)
	{
		statInView = inView;
		statInViewMax = inViewMax;
	}

	private int countVisibleEmitters()
	{
		int count = activeWeatherZoneCount;
		for (PlayerEmitters pe : playerEmitters.values())
		{
			for (ActiveEmitter emitter : pe.emitters)
			{
				if (emitter.anchorCount > 0)
				{
					count++;
				}
			}
		}
		for (ObjectEmitters oe : objectEmitters.values())
		{
			for (ActiveEmitter emitter : oe.emitters)
			{
				if (emitter.anchorCount > 0)
				{
					count++;
				}
			}
		}
		for (PlayerEmitters ne : npcEmitters.values())
		{
			for (ActiveEmitter emitter : ne.emitters)
			{
				if (emitter.anchorCount > 0)
				{
					count++;
				}
			}
		}
		return count;
	}

	/**
	 * Dev diagnostic: the hover menu lists every player in a stack and the
	 * engine puts the DRAWN player's entry on top - ground truth for the
	 * stack winner that no API exposes, and that seven falsified
	 * reconstruction attempts never had. While hovering a stack, show the
	 * oracle winner next to the gate's decision and the lowest/highest
	 * menu-index candidates, and log one full state dump per distinct
	 * situation so the true precedence rule can be mined offline.
	 */
	private void updateStackOracle(int level)
	{
		oracleLine = "";
		if (!developerMode)
		{
			return;
		}

		// Menus build bottom-up: the last vanilla player entry renders as the
		// top row. Only vanilla options - plugin-injected entries would lie.
		MenuEntry[] entries = client.getMenu().getMenuEntries();
		Player winner = null;
		Map<Player, Integer> menuIndices = new HashMap<>();
		for (MenuEntry entry : entries)
		{
			Player p = entry.getPlayer();
			if (p != null && isVanillaPlayerOption(entry.getType()))
			{
				menuIndices.put(p, entry.getIdentifier());
				winner = p;
			}
		}
		if (winner == null)
		{
			return;
		}

		long key = tileKey(winner);
		List<Player> stack = new ArrayList<>();
		for (Player p : client.getTopLevelWorldView().players())
		{
			if (p != null && p.getWorldLocation().getPlane() == level
				&& isCentered(p) && tileKey(p) == key)
			{
				stack.add(p);
			}
		}
		String gate;
		if (key == localClaimKey)
		{
			gate = "local";
		}
		else if (key == targetClaimKey && localClaimTarget != null)
		{
			gate = localClaimTarget.getName();
		}
		else if (npcClaimedTiles.contains(key))
		{
			gate = "silent-npc";
		}
		else
		{
			Player owner = tileOwners.get(key);
			gate = owner == null ? "none" : owner.getName();
		}

		Player lo = null;
		Player hi = null;
		for (Player p : stack)
		{
			Integer idx = menuIndices.get(p);
			if (idx == null)
			{
				continue;
			}
			if (lo == null || idx < menuIndices.get(lo))
			{
				lo = p;
			}
			if (hi == null || idx > menuIndices.get(hi))
			{
				hi = p;
			}
		}

		// The shippable rule, scored live against the oracle: the engine's
		// claim precedence (local -> local's player target -> size-1 centered
		// NPC -> ascending index), with indices from Player.getId() - the
		// scene-wide source the rule would use outside the hovered tile
		Player localPlayer = client.getLocalPlayer();
		Player pred = null;
		String predWhy;
		if (stack.contains(localPlayer))
		{
			pred = localPlayer;
			predWhy = "local";
		}
		else
		{
			Player target = localPlayer != null && localPlayer.getInteracting() instanceof Player
				? (Player) localPlayer.getInteracting() : null;
			if (target != null && stack.contains(target))
			{
				pred = target;
				predWhy = "target";
			}
			else if (npcClaimedTiles.contains(key))
			{
				predWhy = "npc-claim";
			}
			else
			{
				for (Player p : stack)
				{
					if (pred == null || p.getId() < pred.getId())
					{
						pred = p;
					}
				}
				predWhy = "idx";
			}
		}
		// Menus never list the local player, so when we predict ourselves the
		// oracle cannot confirm it - that is blindness, not a falsification
		String predText = (pred == null ? predWhy : pred.getName() + "[" + predWhy + "]")
			+ (!isCentered(winner) ? " n/a-moving"
			: pred == winner ? " MATCH"
			: pred == localPlayer ? " oracle-blind-self"
			: " MISS");

		// Does the scene-wide index source agree with the menu identifiers?
		// A disagreement here would explain the old engine-mirror failures.
		boolean idxOk = true;
		for (Player p : stack)
		{
			Integer menuIdx = menuIndices.get(p);
			if (menuIdx != null && menuIdx != p.getId())
			{
				idxOk = false;
			}
		}

		// Mirror the scope filters (Apply to, effect radius) the drawn loop
		// applies before the claim - the claim can approve a player that
		// scope then excludes, which otherwise reads as a MATCH that fails
		String scoped = "";
		if (winner != localPlayer)
		{
			HdPluginConfig.ParticleApplyTo applyTo = config.particleApplyTo();
			if (applyTo == HdPluginConfig.ParticleApplyTo.ME
				|| (applyTo == HdPluginConfig.ParticleApplyTo.FRIENDS && !winner.isFriend()))
			{
				scoped = " SCOPED-OUT(" + applyTo + ")";
			}
			else if (localPlayer != null && winner.getLocalLocation()
				.distanceTo(localPlayer.getLocalLocation()) > config.particleEffectRadius() * 128)
			{
				scoped = " SCOPED-OUT(radius " + config.particleEffectRadius() + ")";
			}
		}

		// Resolution state: 0 emitters = their gear matched no enabled
		// preset; emitters but 0 anchors = blocked by the gate or scope
		PlayerEmitters winnerPe = playerEmitters.get(winner);
		String emitState = winnerPe == null
			? "emit -"
			: "emit " + winnerPe.emitters.size() + "e/" + winnerPe.anchorCount + "a";

		oracleLine = "stack " + stack.size()
			+ " | drawn " + winner.getName() + (isCentered(winner) ? "" : " (moving)")
			+ " | gate " + gate
			+ " | pred " + predText
			+ " | loIdx " + (lo == null ? "-" : lo.getName())
			+ " | hiIdx " + (hi == null ? "-" : hi.getName())
			+ " | idxSrc " + (idxOk ? "ok" : "MISMATCH")
			+ " | " + emitState + scoped;

		// One dump per distinct situation, with everything a candidate
		// precedence rule could depend on
		StringBuilder dump = new StringBuilder("stack oracle: drawn=")
			.append(winner.getName()).append('#').append(menuIndices.getOrDefault(winner, -1))
			.append(" gate=").append(gate)
			.append(" pred=").append(predText)
			.append(" idxOk=").append(idxOk)
			.append(' ').append(emitState).append(scoped);
		for (Player p : stack)
		{
			LocalPoint lp = p.getLocalLocation();
			dump.append(" | ").append(p.getName())
				.append('#').append(menuIndices.getOrDefault(p, -1))
				.append('/').append(p.getId())
				.append(" lp=").append(lp.getX()).append(',').append(lp.getY())
				.append(p == localPlayer ? " LOCAL" : "")
				.append(p.getInteracting() != null ? " ->" + p.getInteracting().getName() : "");
		}
		String text = dump.toString();
		if (!text.equals(lastOracleDump))
		{
			lastOracleDump = text;
			log.debug(text);
		}
	}

	private static boolean isVanillaPlayerOption(MenuAction action)
	{
		switch (action)
		{
			case PLAYER_FIRST_OPTION:
			case PLAYER_SECOND_OPTION:
			case PLAYER_THIRD_OPTION:
			case PLAYER_FOURTH_OPTION:
			case PLAYER_FIFTH_OPTION:
			case PLAYER_SIXTH_OPTION:
			case PLAYER_SEVENTH_OPTION:
			case PLAYER_EIGHTH_OPTION:
				return true;
			default:
				return false;
		}
	}

	/**
	 * Dev diagnostic, the NPC analog of {@link #updateStackOracle}: the hover
	 * menu's top NPC entry is the DRAWN npc among a stack - ground truth no API
	 * exposes - scored live against the candidate rule (lowest scene index
	 * wins) so the true stacked-NPC draw order can be mined before an emission
	 * gate is wired. Works on any NPCs, not just profiled ones, so stacks can
	 * be sampled wherever they occur.
	 */
	private void updateNpcStackOracle(int level)
	{
		npcOracleLine = "";
		if (!developerMode)
		{
			return;
		}

		// Menus build bottom-up: the last vanilla NPC entry renders as the top
		// row, which is the drawn npc. Only vanilla options - plugin entries lie
		MenuEntry[] entries = client.getMenu().getMenuEntries();
		NPC winner = null;
		Map<NPC, Integer> menuIndices = new HashMap<>();
		for (MenuEntry entry : entries)
		{
			NPC npc = entry.getNpc();
			if (npc != null && isVanillaNpcOption(entry.getType()))
			{
				menuIndices.put(npc, entry.getIdentifier());
				winner = npc;
			}
		}
		if (winner == null)
		{
			return;
		}

		// The stack: centered size-1 NPCs sharing the drawn npc's tile. Only
		// size-1 centered actors are deduped by the engine; movers always draw
		long key = tileKey(winner);
		List<NPC> stack = new ArrayList<>();
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (npc == null || npc.getWorldLocation().getPlane() != level || !isCentered(npc))
			{
				continue;
			}
			NPCComposition composition = npc.getTransformedComposition();
			if (composition == null || composition.getSize() != 1)
			{
				continue;
			}
			if (tileKey(npc) == key)
			{
				stack.add(npc);
			}
		}

		// Score several candidate rules at once so the true one shows itself:
		// lowest / highest scene index, and first / last in the scene's NPC
		// iteration order (the stack is built in that order). Whichever stays
		// green across stacks is the rule to wire the gate to.
		NPC lowestIdx = null;
		NPC highestIdx = null;
		for (NPC npc : stack)
		{
			if (lowestIdx == null || npc.getIndex() < lowestIdx.getIndex())
			{
				lowestIdx = npc;
			}
			if (highestIdx == null || npc.getIndex() > highestIdx.getIndex())
			{
				highestIdx = npc;
			}
		}
		NPC firstIter = stack.isEmpty() ? null : stack.get(0);
		NPC lastIter = stack.isEmpty() ? null : stack.get(stack.size() - 1);
		boolean scoreable = isCentered(winner);

		// Does the scene-wide index (getIndex) agree with the menu identifier?
		// A mismatch would mean getIndex is the wrong source for the rule
		boolean idxOk = true;
		for (NPC npc : stack)
		{
			Integer menuIdx = menuIndices.get(npc);
			if (menuIdx != null && menuIdx != npc.getIndex())
			{
				idxOk = false;
			}
		}

		int winnerIter = stack.indexOf(winner);
		npcOracleLine = "npc stack " + stack.size()
			+ " drawn " + winner.getName() + "#" + winner.getIndex() + "(it" + winnerIter + ")"
			+ (scoreable ? "" : " moving")
			+ " | loIdx" + predMark(lowestIdx, winner, scoreable)
			+ " | hiIdx" + predMark(highestIdx, winner, scoreable)
			+ " | 1st" + predMark(firstIter, winner, scoreable)
			+ " | last" + predMark(lastIter, winner, scoreable)
			+ " | idxSrc " + (idxOk ? "ok" : "MISMATCH");

		// One dump per distinct situation, with everything a rule could depend
		// on: iteration position, scene index, type id, menu identifier, pos
		StringBuilder dump = new StringBuilder("npc stack oracle: drawn=")
			.append(winner.getName()).append('#').append(winner.getIndex())
			.append("(it").append(winnerIter).append(')')
			.append(" loIdx").append(predMark(lowestIdx, winner, scoreable))
			.append(" hiIdx").append(predMark(highestIdx, winner, scoreable))
			.append(" 1st").append(predMark(firstIter, winner, scoreable))
			.append(" last").append(predMark(lastIter, winner, scoreable))
			.append(" idxSrc=").append(idxOk ? "ok" : "MISMATCH");
		for (int i = 0; i < stack.size(); i++)
		{
			NPC npc = stack.get(i);
			LocalPoint lp = npc.getLocalLocation();
			dump.append(" | it").append(i).append(' ').append(npc.getName())
				.append('#').append(npc.getIndex())
				.append("/id").append(npc.getId())
				.append("/menu").append(menuIndices.getOrDefault(npc, -1))
				.append(" lp=").append(lp.getX()).append(',').append(lp.getY());
		}
		String text = dump.toString();
		if (!text.equals(lastNpcOracleDump))
		{
			lastNpcOracleDump = text;
			log.debug(text);
		}
	}

	/**
	 * One candidate predictor's result against the oracle winner: its index
	 * plus Y (match), n (miss), or ? (winner is a mover, not scoreable).
	 */
	private static String predMark(NPC pred, NPC winner, boolean scoreable)
	{
		if (pred == null)
		{
			return "-";
		}
		return "#" + pred.getIndex() + (!scoreable ? " ?" : pred == winner ? " Y" : " n");
	}

	private static boolean isVanillaNpcOption(MenuAction action)
	{
		switch (action)
		{
			case NPC_FIRST_OPTION:
			case NPC_SECOND_OPTION:
			case NPC_THIRD_OPTION:
			case NPC_FOURTH_OPTION:
			case NPC_FIFTH_OPTION:
				return true;
			default:
				return false;
		}
	}

	private void onParticleDeath(Particle p)
	{
		windowDeaths++;
		// 1.0 = lived its full lifetime; below that it was killed early
		windowDeathAgeSum += 1f - p.lifeFraction();
	}

	private void updateStats(long now)
	{
		if (now - statsWindowStart < 1_000_000_000L)
		{
			return;
		}
		statsWindowStart = now;
		statSpawnsPerSec = windowSpawns;
		statCpuNanos = cpuSampleCount > 0 ? cpuAccumNanos / cpuSampleCount : 0;
		cpuAccumNanos = 0;
		cpuSampleCount = 0;
		int avgDeathAgePct = windowDeaths == 0 ? -1
			: Math.round(windowDeathAgeSum / windowDeaths * 100);
		statsLine = "alive " + statAlive + "/" + statMax
			+ " | in view " + statInView + "/" + statInViewMax
			+ " | emitters " + statVisibleEmitters
			+ " | spawns/s " + statSpawnsPerSec
			+ " | cpu " + (statCpuNanos / 1_000_000f) + " ms"
			+ " | batches " + renderer.getActiveObjects()
			+ " | deaths/s " + windowDeaths
			+ " | avg death age " + (avgDeathAgePct < 0 ? "-" : avgDeathAgePct + "%")
			+ " | oob kills " + renderer.drainOutOfSceneKills()
			+ " | last anim " + lastActionAnimation;
		windowSpawns = 0;
		windowDeaths = 0;
		windowDeathAgeSum = 0;
	}

	/**
	 * Transform a player's active emitter vertices from their posed model
	 * into world (local scene) coordinates.
	 */
	private void updateAnchors(PlayerEmitters pe, Actor player, boolean markers)
	{
		// Keep last tick's anchors for movement-segment interpolation
		pe.prevCount = pe.anchorCount;
		if (pe.anchorCount > 0)
		{
			if (pe.prevXs.length < pe.anchorXs.length)
			{
				pe.prevXs = new float[pe.anchorXs.length];
				pe.prevYs = new float[pe.anchorXs.length];
				pe.prevZs = new float[pe.anchorXs.length];
			}
			System.arraycopy(pe.anchorXs, 0, pe.prevXs, 0, pe.anchorCount);
			System.arraycopy(pe.anchorYs, 0, pe.prevYs, 0, pe.anchorCount);
			System.arraycopy(pe.anchorZs, 0, pe.prevZs, 0, pe.anchorCount);
		}
		pe.anchorCount = 0;

		if (pe.emitters.isEmpty())
		{
			return;
		}

		Model model = player.getModel();
		if (model == null)
		{
			return;
		}
		int vertexCount = model.getVerticesCount();
		if (vertexCount == 0)
		{
			return;
		}

		int totalVertices = 0;
		for (ActiveEmitter emitter : pe.emitters)
		{
			totalVertices += emitter.vertices.length + emitter.extraAnchors;
		}
		if (pe.anchorXs.length < totalVertices)
		{
			pe.anchorXs = new float[totalVertices];
			pe.anchorYs = new float[totalVertices];
			pe.anchorZs = new float[totalVertices];
		}

		// Rotate model space into the world by the player's current facing
		int orientation = player.getCurrentOrientation();
		int sin = Perspective.SINE[orientation];
		int cos = Perspective.COSINE[orientation];
		LocalPoint lp = player.getLocalLocation();
		// The engine renders the whole model relative to a single base height:
		// the tile height averaged over the actor's footprint, adjusted by the
		// current animation - the same formula ModelOutlineRenderer uses. A
		// plain point getTileHeight desyncs from the model on uneven ground.
		int playerBaseZ = Perspective.getFootprintTileHeight(client, lp, anchorLevel, player.getFootprintSize())
			- player.getAnimationHeightOffset();

		for (ActiveEmitter emitter : pe.emitters)
		{
			emitter.anchorStart = pe.anchorCount;
			emitter.anchorCount = 0;
			// Style offset in model space, so it rotates with the player
			ParticleStyle style = emitter.style;
			for (int v : emitter.vertices)
			{
				if (v < 0 || v >= vertexCount)
				{
					continue;
				}
				float vx = model.getVerticesX()[v] + style.getOffsetX();
				float vy = model.getVerticesY()[v] - style.getOffsetZ();
				float vz = model.getVerticesZ()[v] + style.getOffsetY();

				pe.anchorXs[pe.anchorCount] = lp.getX() + (vz * sin + vx * cos) / 65536f;
				pe.anchorYs[pe.anchorCount] = lp.getY() + (vz * cos - vx * sin) / 65536f;
				// Model y is already in the engine's negative-up convention
				pe.anchorZs[pe.anchorCount] = playerBaseZ + vy;
				pe.anchorCount++;
				emitter.anchorCount++;
			}
			emitter.featherReady = emitter.anchorCount == emitter.vertices.length;
			if (emitter.featherReady && emitter.extraAnchors > 0)
			{
				int end = appendInterpolatedAnchors(emitter, pe.anchorXs, pe.anchorYs, pe.anchorZs,
					null, pe.anchorCount);
				emitter.anchorCount += end - pe.anchorCount;
				pe.anchorCount = end;
			}
			fillFaceWorldPositions(emitter, model.getVerticesX(), model.getVerticesY(), model.getVerticesZ(),
				vertexCount, style.getOffsetX(), style.getOffsetY(), style.getOffsetZ(),
				lp.getX(), lp.getY(), playerBaseZ, sin, cos, true);
		}

		if (pe.trailCarry.length < pe.anchorXs.length)
		{
			pe.trailCarry = new float[pe.anchorXs.length];
		}
		// Anchor slots only correspond across ticks while the layout is stable
		if (pe.rebuilt || pe.prevCount != pe.anchorCount)
		{
			pe.prevCount = 0;
			Arrays.fill(pe.trailCarry, 0, pe.trailCarry.length, 0f);
		}
		pe.rebuilt = false;

		if (markers)
		{
			appendDebugMarkers(pe);
		}
	}

	/**
	 * Aggregate this player's anchors and feathered emission lines into the
	 * overlay's debug arrays, for tuning.
	 */
	private void appendDebugMarkers(PlayerEmitters pe)
	{
		int needed = anchorCount + pe.anchorCount;
		if (anchorXs.length < needed)
		{
			anchorXs = Arrays.copyOf(anchorXs, Math.max(needed, anchorXs.length * 2 + 16));
			anchorYs = Arrays.copyOf(anchorYs, anchorXs.length);
			anchorZs = Arrays.copyOf(anchorZs, anchorXs.length);
		}
		System.arraycopy(pe.anchorXs, 0, anchorXs, anchorCount, pe.anchorCount);
		System.arraycopy(pe.anchorYs, 0, anchorYs, anchorCount, pe.anchorCount);
		System.arraycopy(pe.anchorZs, 0, anchorZs, anchorCount, pe.anchorCount);
		anchorCount += pe.anchorCount;

		for (ActiveEmitter emitter : pe.emitters)
		{
			int w = emitter.style.getFeatherStrength();
			// featherReady, not the raw count: interpolation appends extra
			// anchors so anchorCount exceeds vertices.length when both are on
			if (w <= 0 || emitter.chains == null || !emitter.featherReady)
			{
				continue;
			}
			for (int[] chain : emitter.chains)
			{
				if (chain.length < 2)
				{
					continue;
				}
				float[] points = new float[chain.length * 3];
				for (int j = 0; j < chain.length; j++)
				{
					points[j * 3] = smoothed(pe.anchorXs, emitter, chain, j, w);
					points[j * 3 + 1] = smoothed(pe.anchorYs, emitter, chain, j, w);
					points[j * 3 + 2] = smoothed(pe.anchorZs, emitter, chain, j, w);
				}
				featherDebugPaths.add(points);
			}
		}
	}

	/**
	 * @return whether an anchor's movement since last tick is usable as an
	 * emission segment (layout stable and not a teleport-sized jump)
	 */
	private static boolean segmentUsable(PlayerEmitters pe, int a)
	{
		if (pe.prevCount != pe.anchorCount)
		{
			return false;
		}
		float dx = pe.anchorXs[a] - pe.prevXs[a];
		float dy = pe.anchorYs[a] - pe.prevYs[a];
		float dz = pe.anchorZs[a] - pe.prevZs[a];
		return dx * dx + dy * dy + dz * dz <= MAX_TRAIL_SEGMENT * MAX_TRAIL_SEGMENT;
	}

	private static float segmentLength(PlayerEmitters pe, int a)
	{
		float dx = pe.anchorXs[a] - pe.prevXs[a];
		float dy = pe.anchorYs[a] - pe.prevYs[a];
		float dz = pe.anchorZs[a] - pe.prevZs[a];
		return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/**
	 * Map stored piece-local emitters onto one player's composite model,
	 * honoring each profile's worn item filter against that player's gear.
	 * Cached: re-runs only when their equipment or the profile store changes.
	 */
	private void resolvePlayer(PlayerEmitters pe, Player player)
	{
		PlayerComposition composition = player.getPlayerComposition();
		if (composition == null)
		{
			pe.emitters.clear();
			pe.equipmentIds = null;
			return;
		}

		// Compare gear directly; building a key string every tick is garbage
		int[] equipmentIds = composition.getEquipmentIds();
		int revision = store.getRevision();
		if (Arrays.equals(equipmentIds, pe.equipmentIds) && revision == pe.revision)
		{
			return;
		}

		Model model = player.getModel();
		if (model == null)
		{
			pe.emitters.clear();
			pe.equipmentIds = null;
			return;
		}
		pe.equipmentIds = equipmentIds.clone();
		pe.revision = revision;
		pe.rebuilt = true;

		Set<Integer> wornItemIds = wornItemIds(composition);
		EmitterStore.Snapshot snap = store.snapshot();
		Map<String, EmitterProfile> profiles = snap.profiles;
		Map<String, ProfileFolder> folders = snap.folders;
		ModelSnapshot snapshot = ModelSnapshot.capture(model);

		pe.emitters.clear();
		Set<String> present = new HashSet<>();
		for (ModelSnapshot.Piece piece : snapshot.getPieces())
		{
			present.add(piece.getSignature());
			for (String mirror : piece.getMirrorSignatures())
			{
				present.add(mirror);
			}
			for (Map.Entry<String, EmitterProfile> entry : profiles.entrySet())
			{
				EmitterProfile profile = entry.getValue();
				if (!EmitterProfile.TARGET_PLAYER.equals(profile.getTargetType())
					|| !piece.matchesSignature(profile.getSignature())
					|| !ParticlesPanel.effectiveEnabled(profile, folders)
					|| (profile.getVertices().isEmpty() && profile.getFaces().isEmpty())
					|| (!developerMode && ParticlesPanel.effectiveWip(profile, folders)))
				{
					continue;
				}
				// Item variant gate: distinguishes recolored items sharing a mesh
				if (!profile.getItemIds().isEmpty() && Collections.disjoint(profile.getItemIds(), wornItemIds))
				{
					continue;
				}
				ParticleStyle style = renderer.getStyle(entry.getKey());
				if (style == null)
				{
					continue;
				}

				ActiveEmitter emitter = resolveMeshEmitter(style, snapshot, piece, profile);
				if (emitter != null)
				{
					pe.emitters.add(emitter);
				}
			}
		}

		if (player != client.getLocalPlayer())
		{
			return;
		}

		// Local-player extras: the sidebar's worn indicator, and projectile
		// profiles gear-gated by the local player's equipment
		activeProjectileProfiles.clear();
		for (Map.Entry<String, EmitterProfile> entry : profiles.entrySet())
		{
			EmitterProfile profile = entry.getValue();
			if (!profile.isProjectileTarget() || !ParticlesPanel.effectiveEnabled(profile, folders)
				|| profile.getProjectileId() < 0
				|| (!developerMode && ParticlesPanel.effectiveWip(profile, folders)))
			{
				continue;
			}
			if (!profile.getItemIds().isEmpty() && Collections.disjoint(profile.getItemIds(), wornItemIds))
			{
				continue;
			}
			ParticleStyle style = renderer.getStyle(entry.getKey());
			if (style != null)
			{
				activeProjectileProfiles.add(new ActiveProjectileProfile(profile.getProjectileId(), style));
			}
		}
		projectileTrackers.clear();

		if (!present.equals(presentSignatures))
		{
			presentSignatures = present;
			refreshViewer();
		}
	}

	/**
	 * Resolve piece-local vertex and face indices into a live {@link ActiveEmitter}.
	 * Returns null when neither set maps onto the piece.
	 */
	@Nullable
	private ActiveEmitter resolveMeshEmitter(ParticleStyle style, ModelSnapshot snapshot,
		ModelSnapshot.Piece piece, EmitterProfile profile)
	{
		int[] pieceVerts = piece.verticesFor(profile.getSignature());
		List<Integer> globals = new ArrayList<>();
		for (int local : profile.getVertices())
		{
			if (local >= 0 && local < pieceVerts.length)
			{
				globals.add(pieceVerts[local]);
			}
		}
		int[] faceCorners = resolveFaceCorners(snapshot, piece, profile.getFaces());
		if (globals.isEmpty() && faceCorners.length == 0)
		{
			return null;
		}
		int[] vertices = new int[globals.size()];
		for (int i = 0; i < vertices.length; i++)
		{
			vertices[i] = globals.get(i);
		}
		int[][] chains = vertices.length > 0 && (style.getFeatherStrength() > 0 || style.getInterpolation() > 0)
			? buildChains(snapshot, piece, vertices)
			: null;
		return new ActiveEmitter(style, vertices, faceCorners, chains);
	}

	/**
	 * Map piece-local face ranks to global corner triple streams for emission.
	 */
	private static int[] resolveFaceCorners(ModelSnapshot snapshot, ModelSnapshot.Piece piece,
		Set<Integer> localFaces)
	{
		if (localFaces == null || localFaces.isEmpty())
		{
			return EMPTY_INTS;
		}
		int[] pieceFaces = piece.getFaces();
		int[] f1 = snapshot.getFaceIndices1();
		int[] f2 = snapshot.getFaceIndices2();
		int[] f3 = snapshot.getFaceIndices3();
		List<Integer> corners = new ArrayList<>(localFaces.size() * 3);
		for (int local : localFaces)
		{
			if (local < 0 || local >= pieceFaces.length)
			{
				continue;
			}
			int globalFace = pieceFaces[local];
			corners.add(f1[globalFace]);
			corners.add(f2[globalFace]);
			corners.add(f3[globalFace]);
		}
		if (corners.isEmpty())
		{
			return EMPTY_INTS;
		}
		int[] out = new int[corners.size()];
		for (int i = 0; i < out.length; i++)
		{
			out[i] = corners.get(i);
		}
		return out;
	}

	/**
	 * Chain an emitter's vertices along the mesh edges of their piece, for
	 * feathered emission. Each chain is a walkable path of anchor offsets;
	 * vertices with no emitter neighbor become single-point chains. Cold
	 * path, runs only at emitter resolution.
	 */
	private static int[][] buildChains(ModelSnapshot snapshot, ModelSnapshot.Piece piece, int[] emitterVertices)
	{
		Map<Integer, Integer> offsetOf = new HashMap<>();
		for (int i = 0; i < emitterVertices.length; i++)
		{
			offsetOf.put(emitterVertices[i], i);
		}

		// Adjacency between emitter vertices sharing a mesh edge
		List<List<Integer>> adjacency = new ArrayList<>(emitterVertices.length);
		for (int i = 0; i < emitterVertices.length; i++)
		{
			adjacency.add(new ArrayList<>());
		}
		Set<Long> seenEdges = new HashSet<>();
		int[] f1 = snapshot.getFaceIndices1();
		int[] f2 = snapshot.getFaceIndices2();
		int[] f3 = snapshot.getFaceIndices3();
		for (int f : piece.getFaces())
		{
			chainEdge(offsetOf, adjacency, seenEdges, f1[f], f2[f]);
			chainEdge(offsetOf, adjacency, seenEdges, f2[f], f3[f]);
			chainEdge(offsetOf, adjacency, seenEdges, f1[f], f3[f]);
		}

		// Walk paths from endpoints and junctions first, then leftover cycles
		boolean[] visited = new boolean[emitterVertices.length];
		List<List<Integer>> chains = new ArrayList<>();
		for (int pass = 0; pass < 2; pass++)
		{
			for (int start = 0; start < emitterVertices.length; start++)
			{
				if (visited[start] || (pass == 0 && adjacency.get(start).size() == 2))
				{
					continue;
				}
				List<Integer> path = new ArrayList<>();
				int current = start;
				visited[current] = true;
				path.add(current);
				boolean extended = true;
				while (extended)
				{
					extended = false;
					for (int next : adjacency.get(current))
					{
						if (!visited[next])
						{
							visited[next] = true;
							path.add(next);
							current = next;
							extended = true;
							break;
						}
					}
				}
				chains.add(path);
			}
		}

		bridgeChains(snapshot, emitterVertices, chains);

		int[][] result = new int[chains.size()][];
		for (int c = 0; c < chains.size(); c++)
		{
			List<Integer> path = chains.get(c);
			int[] chain = new int[path.size()];
			for (int i = 0; i < chain.length; i++)
			{
				chain[i] = path.get(i);
			}
			result[c] = chain;
		}
		return result;
	}

	/**
	 * Distance particles will feather across even without a connecting mesh
	 * edge, e.g. between the separate puffs of a fur trim.
	 */
	private static final float CHAIN_BRIDGE_DISTANCE = 40f;

	/**
	 * Repeatedly join chains whose endpoints are close in space, reversing as
	 * needed so the joined path stays walkable. Cold path.
	 */
	private static void bridgeChains(ModelSnapshot snapshot, int[] emitterVertices, List<List<Integer>> chains)
	{
		boolean merged = true;
		while (merged && chains.size() > 1)
		{
			merged = false;
			outer:
			for (int i = 0; i < chains.size(); i++)
			{
				for (int j = i + 1; j < chains.size(); j++)
				{
					List<Integer> a = chains.get(i);
					List<Integer> b = chains.get(j);
					// Endpoint pairings: a-head/tail against b-head/tail
					if (endpointsClose(snapshot, emitterVertices, a.get(a.size() - 1), b.get(0)))
					{
						a.addAll(b);
					}
					else if (endpointsClose(snapshot, emitterVertices, a.get(a.size() - 1), b.get(b.size() - 1)))
					{
						Collections.reverse(b);
						a.addAll(b);
					}
					else if (endpointsClose(snapshot, emitterVertices, a.get(0), b.get(b.size() - 1)))
					{
						a.addAll(0, b);
					}
					else if (endpointsClose(snapshot, emitterVertices, a.get(0), b.get(0)))
					{
						Collections.reverse(b);
						a.addAll(0, b);
					}
					else
					{
						continue;
					}
					chains.remove(j);
					merged = true;
					break outer;
				}
			}
		}
	}

	private static boolean endpointsClose(ModelSnapshot snapshot, int[] emitterVertices, int offsetA, int offsetB)
	{
		int globalA = emitterVertices[offsetA];
		int globalB = emitterVertices[offsetB];
		float dx = snapshot.getVerticesX()[globalA] - snapshot.getVerticesX()[globalB];
		float dy = snapshot.getVerticesY()[globalA] - snapshot.getVerticesY()[globalB];
		float dz = snapshot.getVerticesZ()[globalA] - snapshot.getVerticesZ()[globalB];
		return dx * dx + dy * dy + dz * dz <= CHAIN_BRIDGE_DISTANCE * CHAIN_BRIDGE_DISTANCE;
	}

	private static void chainEdge(Map<Integer, Integer> offsetOf, List<List<Integer>> adjacency,
		Set<Long> seenEdges, int globalA, int globalB)
	{
		Integer a = offsetOf.get(globalA);
		Integer b = offsetOf.get(globalB);
		if (a == null || b == null)
		{
			return;
		}
		long key = a < b ? ((long) a << 32) | b : ((long) b << 32) | a;
		if (seenEdges.add(key))
		{
			adjacency.get(a).add(b);
			adjacency.get(b).add(a);
		}
	}

	/**
	 * Force re-resolution for everyone without touching the draw-order
	 * mirror, e.g. when styles rebuild or models are recreated on scene load.
	 */
	private void invalidateResolutions()
	{
		for (PlayerEmitters pe : playerEmitters.values())
		{
			pe.equipmentIds = null;
		}
	}

	/**
	 * Standing exactly at a tile center - the only state the engine's
	 * stacked-actor dedup applies to.
	 */
	private static boolean isCentered(Actor actor)
	{
		LocalPoint lp = actor.getLocalLocation();
		return (lp.getX() & 127) == 64 && (lp.getY() & 127) == 64;
	}

	private static long tileKey(Actor actor)
	{
		LocalPoint lp = actor.getLocalLocation();
		return ((long) (lp.getX() >> 7) << 32) | ((lp.getY() >> 7) & 0xffffffffL);
	}

	/**
	 * A size-1 NPC standing exactly at its tile center: the only NPCs the
	 * engine dedups when stacked, and thus the only ones the draw gate covers.
	 */
	private static boolean isCenteredSize1(NPC npc)
	{
		if (!isCentered(npc))
		{
			return false;
		}
		NPCComposition composition = npc.getTransformedComposition();
		return composition != null && composition.getSize() == 1;
	}

	private static Set<Integer> wornItemIds(PlayerComposition composition)
	{
		Set<Integer> ids = new HashSet<>();
		for (int equipmentId : composition.getEquipmentIds())
		{
			if (equipmentId >= PlayerComposition.ITEM_OFFSET)
			{
				ids.add(equipmentId - PlayerComposition.ITEM_OFFSET);
			}
		}
		return ids;
	}

	private void emit(float dt, PlayerEmitters pe, Actor player)
	{
		boolean anySites = pe.anchorCount > 0;
		if (!anySites)
		{
			for (ActiveEmitter emitter : pe.emitters)
			{
				if (emitter.faceCount() > 0)
				{
					anySites = true;
					break;
				}
			}
		}
		if (!anySites)
		{
			for (ActiveEmitter emitter : pe.emitters)
			{
				emitter.carry = 0;
			}
			return;
		}

		// Animation gate inputs from THIS player, shared by their emitters
		int actionAnimation = player.getAnimation();
		int actionFrame = player.getAnimationFrame();
		int poseAnimation = player.getPoseAnimation();
		boolean isLocal = player == client.getLocalPlayer();
		if (isLocal)
		{
			lastActionAnimation = actionAnimation != -1 ? actionAnimation : lastActionAnimation;
		}
		// Movement lifetime: a full-lifetime plume smears a tile behind a
		// moving wearer, so profiles can shorten it while THIS player's walk
		// or run pose animation is playing (weapon-specific poses included)
		boolean moving = poseAnimation == player.getRunAnimation() || poseAnimation == player.getWalkAnimation();

		int budget = config.particleMaxParticles();
		float densityScale = config.particleDensity().getFactor();
		if (isLocal && densityScale < 1f && config.particleFullSelfDensity())
		{
			densityScale = 1f;
		}
		for (ActiveEmitter emitter : pe.emitters)
		{
			if (!emitter.hasEmitSites())
			{
				emitter.carry = 0;
				continue;
			}
			ParticleStyle style = emitter.style;
			if (!style.animationMatches(actionAnimation, actionFrame, poseAnimation))
			{
				// Reset so the gate opening doesn't release a burst
				emitter.carry = 0;
				continue;
			}

			// Time-based emission, throttled to what the budget can sustain:
			// outrunning it makes births and deaths synchronize into waves
			float sustainable = budget / style.getLifetimeSec() * 0.95f;
			float rate = Math.min(style.getParticlesPerSecond() * densityScale, sustainable);
			emitter.carry += rate * dt;
			int count = (int) emitter.carry;
			emitter.carry -= count;

			float lifeScale = moving ? style.getMovementLifetimeScale() : 1f;
			if (needsCentroid(style))
			{
				setCentroid(emitter, pe.anchorXs, pe.anchorYs, pe.anchorZs);
			}
			boolean feathered = style.getFeatherStrength() > 0 && emitter.chains != null
				&& emitter.featherReady;
			for (int i = 0; i < count; i++)
			{
				if (particleSystem.getParticles().size() >= budget)
				{
					return;
				}
				if (feathered)
				{
					spawnFeathered(pe, emitter, lifeScale);
				}
				else if (shouldSpawnOnFace(emitter))
				{
					spawnOnFace(emitter, lifeScale);
				}
				else
				{
					int a = emitter.anchorStart + random.nextInt(emitter.anchorCount);
					spawnParticle(pe, emitter, a, random.nextFloat(), lifeScale);
				}
			}

			// Distance-based emission: spread spawns evenly along each
			// anchor's movement this tick, for ribbon-like weapon trails
			float density = style.getTrailDensity() * densityScale;
			if (density <= 0 || emitter.anchorCount == 0)
			{
				continue;
			}
			for (int a = emitter.anchorStart; a < emitter.anchorStart + emitter.anchorCount; a++)
			{
				if (!segmentUsable(pe, a))
				{
					pe.trailCarry[a] = 0;
					continue;
				}
				float owed = pe.trailCarry[a] + segmentLength(pe, a) / 128f * density;
				int n = (int) owed;
				pe.trailCarry[a] = owed - n;
				for (int i = 0; i < n; i++)
				{
					if (particleSystem.getParticles().size() >= budget)
					{
						return;
					}
					// Stratified positions along the segment: an even ribbon
					spawnParticle(pe, emitter, a, (i + random.nextFloat()) / n, lifeScale);
				}
			}
		}
	}


	/**
	 * World-space corner positions for face emission this tick.
	 * {@code actorSpace}: player/NPC offsets (X east-in-model, Y height as -Z,
	 * Z north-in-model) with yaw; scene objects use pre-rotated verts and
	 * world-axis offsets instead.
	 */
	private void fillFaceWorldPositions(ActiveEmitter emitter, float[] vx, float[] vy, float[] vz,
		int vertexCount, float offX, float offY, float offZ,
		float baseX, float baseY, float baseZ, int sin, int cos, boolean actorSpace)
	{
		int n = emitter.faceCorners.length;
		if (n == 0)
		{
			emitter.faceWorldX = EMPTY_FLOATS;
			emitter.faceWorldY = EMPTY_FLOATS;
			emitter.faceWorldZ = EMPTY_FLOATS;
			return;
		}
		if (emitter.faceWorldX.length != n)
		{
			emitter.faceWorldX = new float[n];
			emitter.faceWorldY = new float[n];
			emitter.faceWorldZ = new float[n];
		}
		for (int i = 0; i < n; i++)
		{
			int v = emitter.faceCorners[i];
			if (v < 0 || v >= vertexCount)
			{
				emitter.faceWorldX[i] = baseX;
				emitter.faceWorldY[i] = baseY;
				emitter.faceWorldZ[i] = baseZ;
				continue;
			}
			if (actorSpace)
			{
				float mx = vx[v] + offX;
				float my = vy[v] - offZ;
				float mz = vz[v] + offY;
				emitter.faceWorldX[i] = baseX + (mz * sin + mx * cos) / 65536f;
				emitter.faceWorldY[i] = baseY + (mz * cos - mx * sin) / 65536f;
				emitter.faceWorldZ[i] = baseZ + my;
			}
			else
			{
				emitter.faceWorldX[i] = baseX + vx[v] + offX;
				emitter.faceWorldY[i] = baseY + vz[v] + offY;
				emitter.faceWorldZ[i] = baseZ + vy[v] - offZ;
			}
		}
	}

	/** Prefer face sites proportionally when both verts and faces are present. */
	private boolean shouldSpawnOnFace(ActiveEmitter emitter)
	{
		int faces = emitter.faceCount();
		if (faces <= 0)
		{
			return false;
		}
		if (emitter.anchorCount <= 0)
		{
			return true;
		}
		return random.nextInt(emitter.anchorCount + faces) < faces;
	}

	/** Uniform barycentric sample on a random authored triangle. */
	private void spawnOnFace(ActiveEmitter emitter, float lifeScale)
	{
		int faces = emitter.faceCount();
		if (faces <= 0 || emitter.faceWorldX.length < faces * 3)
		{
			return;
		}
		int f = random.nextInt(faces) * 3;
		float u = random.nextFloat();
		float v = random.nextFloat();
		if (u + v > 1f)
		{
			u = 1f - u;
			v = 1f - v;
		}
		float w = 1f - u - v;
		float x = emitter.faceWorldX[f] * w + emitter.faceWorldX[f + 1] * u + emitter.faceWorldX[f + 2] * v;
		float y = emitter.faceWorldY[f] * w + emitter.faceWorldY[f + 1] * u + emitter.faceWorldY[f + 2] * v;
		float z = emitter.faceWorldZ[f] * w + emitter.faceWorldZ[f + 1] * u + emitter.faceWorldZ[f + 2] * v;
		if (needsCentroid(emitter.style) && emitter.anchorCount == 0)
		{
			float sx = 0, sy = 0, sz = 0;
			for (int i = 0; i < emitter.faceWorldX.length; i++)
			{
				sx += emitter.faceWorldX[i];
				sy += emitter.faceWorldY[i];
				sz += emitter.faceWorldZ[i];
			}
			float inv = 1f / emitter.faceWorldX.length;
			emitter.cx = sx * inv;
			emitter.cy = sy * inv;
			emitter.cz = sz * inv;
		}
		spawnAt(emitter.style, x, y, z, emitter.cx, emitter.cy, emitter.cz, lifeScale);
	}

	/**
	 * Spawn one particle at fraction t along the anchor's movement segment
	 * since last tick (falling back to its current position), plus jitter.
	 */
	private void spawnParticle(PlayerEmitters pe, ActiveEmitter emitter, int a, float t, float lifeScale)
	{
		float ax = pe.anchorXs[a];
		float ay = pe.anchorYs[a];
		float az = pe.anchorZs[a];
		if (segmentUsable(pe, a))
		{
			ax = pe.prevXs[a] + (ax - pe.prevXs[a]) * t;
			ay = pe.prevYs[a] + (ay - pe.prevYs[a]) * t;
			az = pe.prevZs[a] + (az - pe.prevZs[a]) * t;
		}

		spawnAt(emitter.style, ax, ay, az, emitter.cx, emitter.cy, emitter.cz, lifeScale);
	}

	/**
	 * Spawn along the smoothed line through the emitter's vertex chains:
	 * a quadratic curve through segment midpoints with the vertex as control
	 * point, which rounds off jagged hem corners into a soft band.
	 */
	private void spawnFeathered(PlayerEmitters pe, ActiveEmitter emitter, float lifeScale)
	{
		int k = random.nextInt(emitter.sampleChainOf.length);
		int[] chain = emitter.chains[emitter.sampleChainOf[k]];
		int j = emitter.samplePosOf[k];
		int w = emitter.style.getFeatherStrength();
		int jA = Math.max(0, j - 1);
		int jC = Math.min(chain.length - 1, j + 1);
		float t = random.nextFloat();

		float x = quadratic(smoothed(pe.anchorXs, emitter, chain, jA, w),
			smoothed(pe.anchorXs, emitter, chain, j, w),
			smoothed(pe.anchorXs, emitter, chain, jC, w), t);
		float y = quadratic(smoothed(pe.anchorYs, emitter, chain, jA, w),
			smoothed(pe.anchorYs, emitter, chain, j, w),
			smoothed(pe.anchorYs, emitter, chain, jC, w), t);
		float z = quadratic(smoothed(pe.anchorZs, emitter, chain, jA, w),
			smoothed(pe.anchorZs, emitter, chain, j, w),
			smoothed(pe.anchorZs, emitter, chain, jC, w), t);

		// Time-lerp along the anchors' movement this tick, like point spawns
		if (segmentUsable(pe, emitter.anchorStart + chain[j]))
		{
			float timeT = random.nextFloat();
			float px = quadratic(smoothed(pe.prevXs, emitter, chain, jA, w),
				smoothed(pe.prevXs, emitter, chain, j, w),
				smoothed(pe.prevXs, emitter, chain, jC, w), t);
			float py = quadratic(smoothed(pe.prevYs, emitter, chain, jA, w),
				smoothed(pe.prevYs, emitter, chain, j, w),
				smoothed(pe.prevYs, emitter, chain, jC, w), t);
			float pz = quadratic(smoothed(pe.prevZs, emitter, chain, jA, w),
				smoothed(pe.prevZs, emitter, chain, j, w),
				smoothed(pe.prevZs, emitter, chain, jC, w), t);
			x = px + (x - px) * timeT;
			y = py + (y - py) * timeT;
			z = pz + (z - pz) * timeT;
		}

		spawnAt(emitter.style, x, y, z, emitter.cx, emitter.cy, emitter.cz, lifeScale);
	}

	/**
	 * Chain position averaged over a window of neighbors: the feathering
	 * filter that turns a jagged vertex path into a smooth band.
	 */
	private float smoothed(float[] coords, ActiveEmitter emitter, int[] chain, int j, int w)
	{
		int from = Math.max(0, j - w);
		int to = Math.min(chain.length - 1, j + w);
		float sum = 0;
		for (int i = from; i <= to; i++)
		{
			sum += coords[emitter.anchorStart + chain[i]];
		}
		return sum / (to - from + 1);
	}

	/**
	 * Quadratic Bezier from midpoint(a,b) to midpoint(b,c) with b as control:
	 * the smoothed-polyline curve.
	 */
	private static float quadratic(float a, float b, float c, float t)
	{
		float m1 = (a + b) / 2f;
		float m2 = (b + c) / 2f;
		float inv = 1f - t;
		return inv * inv * m1 + 2f * inv * t * b + t * t * m2;
	}

	/**
	 * Emit for projectile-targeted profiles: every active projectile whose ID
	 * matches an enabled profile emits along its movement segment - trail
	 * density is the natural driver for comet tails. Also records recently
	 * seen projectile IDs for the picker's capture list. The player animation
	 * gate doesn't apply here; projectiles are inherently lifecycle-gated.
	 */
	private void emitProjectiles(float dt)
	{
		projectileStamp++;
		long nowMs = System.currentTimeMillis();
		int budget = config.particleMaxParticles();
		float densityScale = config.particleDensity().getFactor();

		for (Projectile projectile : client.getProjectiles())
		{
			if (client.getGameCycle() < projectile.getStartCycle())
			{
				// Queued but not launched yet
				continue;
			}

			ProjectileTracker tracker = projectileTrackers.get(projectile);
			if (tracker == null)
			{
				tracker = new ProjectileTracker();
				projectileTrackers.put(projectile, tracker);
				noteRecentProjectile(projectile.getId(), nowMs);
			}
			tracker.stamp = projectileStamp;

			float x = (float) projectile.getX();
			float y = (float) projectile.getY();
			float z = (float) projectile.getZ();
			float px = tracker.prevX;
			float py = tracker.prevY;
			float pz = tracker.prevZ;
			float distSq = (x - px) * (x - px) + (y - py) * (y - py) + (z - pz) * (z - pz);
			boolean segment = tracker.prevValid && distSq <= MAX_TRAIL_SEGMENT * MAX_TRAIL_SEGMENT;
			tracker.prevX = x;
			tracker.prevY = y;
			tracker.prevZ = z;
			tracker.prevValid = true;

			for (ActiveProjectileProfile profile : activeProjectileProfiles)
			{
				if (profile.projectileId != projectile.getId())
				{
					continue;
				}
				ParticleStyle style = profile.style;
				double[] carries = tracker.carries.computeIfAbsent(style, s -> new double[2]);

				// Time-based emission, spread along this tick's movement
				float sustainable = budget / style.getLifetimeSec() * 0.95f;
				carries[0] += Math.min(style.getParticlesPerSecond() * densityScale, sustainable) * dt;
				int count = (int) carries[0];
				carries[0] -= count;
				for (int i = 0; i < count; i++)
				{
					if (particleSystem.getParticles().size() >= budget)
					{
						return;
					}
					float t = segment ? random.nextFloat() : 1f;
					spawnAt(style, px + (x - px) * t, py + (y - py) * t, pz + (z - pz) * t, 1f);
				}

				// Distance-based ribbon emission
				if (style.getTrailDensity() > 0 && segment)
				{
					double owed = carries[1] + Math.sqrt(distSq) / 128f * style.getTrailDensity() * densityScale;
					int n = (int) owed;
					carries[1] = owed - n;
					for (int i = 0; i < n; i++)
					{
						if (particleSystem.getParticles().size() >= budget)
						{
							return;
						}
						float t = (i + random.nextFloat()) / n;
						spawnAt(style, px + (x - px) * t, py + (y - py) * t, pz + (z - pz) * t, 1f);
					}
				}
			}
		}

		// Drop trackers whose projectiles ended
		Iterator<ProjectileTracker> it = projectileTrackers.values().iterator();
		while (it.hasNext())
		{
			if (it.next().stamp != projectileStamp)
			{
				it.remove();
			}
		}
	}

	private void noteRecentProjectile(int projectileId, long nowMs)
	{
		if (!developerMode)
		{
			return;
		}
		long[] seen = recentProjectiles.get(projectileId);
		if (seen != null)
		{
			seen[0]++;
			seen[1] = nowMs;
			return;
		}
		if (recentProjectiles.size() >= 24)
		{
			Integer oldestId = null;
			long oldestSeen = Long.MAX_VALUE;
			for (Map.Entry<Integer, long[]> entry : recentProjectiles.entrySet())
			{
				if (entry.getValue()[1] < oldestSeen)
				{
					oldestSeen = entry.getValue()[1];
					oldestId = entry.getKey();
				}
			}
			recentProjectiles.remove(oldestId);
		}
		recentProjectiles.put(projectileId, new long[]{1, nowMs});
	}

	/**
	 * Whether a style's emit scale or vortex is active, so the emitter centroid
	 * is worth computing this tick.
	 */
	private static boolean needsCentroid(ParticleStyle style)
	{
		return style.getEmitScale() != 1f || style.getVortex() != 0f;
	}

	/**
	 * Store the mean of an emitter's anchors this tick as its centroid, the
	 * reference point emit scale and vortex are measured from.
	 */
	private static void setCentroid(ActiveEmitter emitter, float[] xs, float[] ys, float[] zs)
	{
		float sx = 0f;
		float sy = 0f;
		float sz = 0f;
		int start = emitter.anchorStart;
		int end = start + emitter.anchorCount;
		for (int a = start; a < end; a++)
		{
			sx += xs[a];
			sy += ys[a];
			sz += zs[a];
		}
		float inv = 1f / emitter.anchorCount;
		emitter.cx = sx * inv;
		emitter.cy = sy * inv;
		emitter.cz = sz * inv;
	}

	/**
	 * Spawn with no emitter centroid: emit scale and vortex are measured from a
	 * centre, so single-point targets (projectiles, spot-anims) that call this
	 * pass the point itself, making both a no-op.
	 */
	private void spawnAt(ParticleStyle style, float ax, float ay, float az, float lifeScale)
	{
		spawnAt(style, ax, ay, az, ax, ay, az, lifeScale);
	}

	private void spawnAt(ParticleStyle style, float ax, float ay, float az,
		float cx, float cy, float cz, float lifeScale)
	{
		// Emit scale: pull the emit point toward the centroid (<1) or push it
		// out (>1) before spawning, scaling the whole emitter ring in place
		float emitScale = style.getEmitScale();
		if (emitScale != 1f)
		{
			ax = cx + (ax - cx) * emitScale;
			ay = cy + (ay - cy) * emitScale;
			az = cz + (az - cz) * emitScale;
		}

		// Spawn within a small volume around the point, not at it exactly
		float jitter = style.getSpawnJitter();
		double jitterAngle = random.nextFloat() * 2 * Math.PI;
		float jitterRadius = jitter * (float) Math.sqrt(random.nextFloat());
		float x = ax + (float) Math.cos(jitterAngle) * jitterRadius;
		float y = ay + (float) Math.sin(jitterAngle) * jitterRadius;
		float z = az + (random.nextFloat() - 0.5f) * jitter;

		float spread = style.getSpreadSpeed();
		float velX = (random.nextFloat() - 0.5f) * spread;
		float velY = (random.nextFloat() - 0.5f) * spread;
		// Scene z is negative-up, so rising means decreasing z
		float velZ = -style.getRiseSpeed() * (0.75f + random.nextFloat() * 0.5f);

		// Vortex: add a radial velocity of constant magnitude from the centroid
		// through the (scaled) emit point - outward for +, inward for -. Uses
		// the pre-jitter point so every particle from a vertex shares a clean
		// radial direction.
		float vortex = style.getVortex();
		if (vortex != 0f)
		{
			float rx = ax - cx;
			float ry = ay - cy;
			float rz = az - cz;
			float rmag = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
			if (rmag > 0.001f)
			{
				float scale = vortex / rmag;
				velX += rx * scale;
				velY += ry * scale;
				velZ += rz * scale;
			}
		}
		// Slow sinusoidal drift; amplitude reuses the spread speed
		float wobblePhase = random.nextFloat() * 2f * (float) Math.PI;
		float wobbleFreq = 1.5f + random.nextFloat() * 2f;
		int sizeVariant = random.nextInt(ParticleStyle.SIZE_MULTIPLIERS.length);

		// Per-particle base-size jitter: pick a base in [size-jitter, size+jitter]
		// (floored at the minimum size), then carry it as a uniform scale so the
		// auto-variation still applies on top.
		float sizeScale = 1f;
		int sizeJitter = style.getSizeJitter();
		if (sizeJitter > 0)
		{
			int base = style.getBaseSize();
			int offset = random.nextInt(2 * sizeJitter + 1) - sizeJitter;
			int jitteredBase = Math.max(ParticleStyle.MIN_SIZE, base + offset);
			sizeScale = jitteredBase / (float) base;
		}

		Particle p = particleSystem.spawn(x, y, z, velX, velY, velZ,
			style.getLifetimeSec() * lifeScale, style, sizeVariant, sizeScale, wobblePhase, wobbleFreq, spread,
			flipbookFrameFor(style));
		if (p != null)
		{
			finalizeSpawn(p, style);
			windowSpawns++;
		}
	}

	private void finalizeSpawn(Particle p, ParticleStyle style)
	{
		float yaw = random.nextFloat() * 2f * (float) Math.PI;
		int spawnColor = 0;
		if (!style.isUniformColorVariation())
		{
			float t = random.nextFloat();
			spawnColor = lerpArgb(style.getStartArgb(), style.getEndArgb(), t);
		}
		p.setSpawnState(yaw, spawnColor);
	}

	private static int lerpArgb(int a, int b, float t)
	{
		int aa = (a >>> 24) & 0xFF, ar = (a >>> 16) & 0xFF, ag = (a >>> 8) & 0xFF, ab = a & 0xFF;
		int ba = (b >>> 24) & 0xFF, br = (b >>> 16) & 0xFF, bg = (b >>> 8) & 0xFF, bb = b & 0xFF;
		int oa = Math.round(aa + (ba - aa) * t);
		int or = Math.round(ar + (br - ar) * t);
		int og = Math.round(ag + (bg - ag) * t);
		int ob = Math.round(ab + (bb - ab) * t);
		return (oa << 24) | (or << 16) | (og << 8) | ob;
	}

	private int flipbookFrameFor(ParticleStyle style)
	{
		if (style.hasFlipbook() && "random".equalsIgnoreCase(style.getFlipbookMode()))
		{
			return random.nextInt(style.getFlipbookFrameCount());
		}
		return -1;
	}

	/**
	 * Open (or focus) the particle dev panel and load a fresh snapshot.
	 * Called on the Swing EDT. Bound to Ctrl+F8 in developer mode.
	 */
	public void openViewer()
	{
		if (!developerMode)
		{
			return;
		}
		if (viewerFrame == null)
		{
			viewerFrame = new ModelViewerFrame(this);
			viewerFrame.addWindowListener(new java.awt.event.WindowAdapter()
			{
				@Override
				public void windowClosed(java.awt.event.WindowEvent e)
				{
					viewerFrame = null;
					viewerSnapshot = null;
					recordSnapshot = null;
				}
			});
		}
		viewerFrame.setVisible(true);
		viewerFrame.toFront();
		viewerFrame.refreshDefinitions(store.snapshot().definitions);
		refreshSnapshot();
	}

	// ==================== Scenery ====================

	/**
	 * Recompute which object IDs carry profiles and rescan the loaded scene
	 * for instances. The scene walk is expensive but runs only when the
	 * profile store changes; spawn events keep the registry current
	 * otherwise.
	 */
	private void rebuildProfiledObjects()
	{
		Set<Integer> ids = new HashSet<>();
		for (EmitterProfile profile : store.snapshotAll().values())
		{
			if (profile.isObjectTarget() && profile.getObjectId() >= 0)
			{
				ids.add(profile.getObjectId());
			}
		}
		profiledObjectIds = ids;
		objectEmitters.clear();
		if (ids.isEmpty())
		{
			return;
		}
		Scene scene = client.getTopLevelWorldView().getScene();
		for (Tile[][] plane : scene.getTiles())
		{
			for (Tile[] column : plane)
			{
				for (Tile tile : column)
				{
					if (tile == null)
					{
						continue;
					}
					GameObject[] gameObjects = tile.getGameObjects();
					if (gameObjects != null)
					{
						for (GameObject gameObject : gameObjects)
						{
							if (gameObject != null)
							{
								trackObject(gameObject);
							}
						}
					}
					if (tile.getWallObject() != null)
					{
						trackObject(tile.getWallObject());
					}
					if (tile.getDecorativeObject() != null)
					{
						trackObject(tile.getDecorativeObject());
					}
					if (tile.getGroundObject() != null)
					{
						trackObject(tile.getGroundObject());
					}
				}
			}
		}
	}

	/**
	 * Resolve, anchor and emit for tracked scenery. No claim gate applies -
	 * scenery never participates in the actor dedup - only plane and radius.
	 */
	private void processObjects(float dt, int level, int radiusUnits, LocalPoint localLp)
	{
		if (objectEmitters.isEmpty())
		{
			return;
		}
		int revision = store.getRevision();
		boolean markers = false;
		for (Map.Entry<TileObject, ObjectEmitters> entry : objectEmitters.entrySet())
		{
			TileObject object = entry.getKey();
			ObjectEmitters oe = entry.getValue();
			if (object.getPlane() != level
				|| object.getLocalLocation().distanceTo(localLp) > radiusUnits)
			{
				oe.anchorCount = 0;
				for (ActiveEmitter emitter : oe.emitters)
				{
					emitter.carry = 0;
				}
				continue;
			}
			if (oe.revision != revision)
			{
				resolveObject(oe, object, revision);
			}
			updateObjectAnchors(oe, object, markers);
			emitObject(dt, oe);
		}
	}

	/**
	 * Map stored object profiles onto one instance's model, matching by
	 * object ID plus piece signature.
	 */
	private void resolveObject(ObjectEmitters oe, TileObject object, int revision)
	{
		oe.revision = revision;
		oe.emitters.clear();
		EmitterStore.Snapshot snap = store.snapshot();
		Map<String, EmitterProfile> profiles = snap.profiles;
		Map<String, ProfileFolder> folders = snap.folders;
		Model model = objectModel(object);
		if (model == null)
		{
			logResolveMissOnce(oe, object, profiles, null);
			return;
		}
		ModelSnapshot snapshot = ModelSnapshot.capture(model);
		for (Map.Entry<String, EmitterProfile> entry : profiles.entrySet())
		{
			EmitterProfile profile = entry.getValue();
			if (!profile.isObjectTarget() || profile.getObjectId() != object.getId()
				|| !ParticlesPanel.effectiveEnabled(profile, folders)
				|| (profile.getVertices().isEmpty() && profile.getFaces().isEmpty())
				|| (!developerMode && ParticlesPanel.effectiveWip(profile, folders)))
			{
				continue;
			}
			ParticleStyle style = renderer.getStyle(entry.getKey());
			if (style == null)
			{
				continue;
			}
			// Attach to EVERY matching piece: identical twin fragments (a
			// double sconce's two flames) share a signature and should all
			// emit, matching the player path's behavior
			for (ModelSnapshot.Piece piece : snapshot.getPieces())
			{
				if (!piece.matchesSignature(profile.getSignature()))
				{
					continue;
				}
				ActiveEmitter emitter = resolveMeshEmitter(style, snapshot, piece, profile);
				if (emitter != null)
				{
					oe.emitters.add(emitter);
				}
			}
		}
		if (oe.emitters.isEmpty())
		{
			logResolveMissOnce(oe, object, profiles, model);
		}
	}

	/**
	 * Hypothesis probe for placements matching nothing despite a profiled
	 * ID: if the dump shows the same NvMf counts with a different hash
	 * suffix, the placement uses a mirrored model whose reversed winding
	 * changes the topology hash. Once per instance, debug level.
	 */
	private void logResolveMissOnce(ObjectEmitters oe, TileObject object,
		Map<String, EmitterProfile> profiles, @Nullable Model primary)
	{
		if (oe.loggedResolveMiss || !log.isDebugEnabled())
		{
			return;
		}
		oe.loggedResolveMiss = true;
		StringBuilder sb = new StringBuilder("object resolve miss: id=").append(object.getId());
		appendPieceSignatures(sb, " primary=", primary);
		appendPieceSignatures(sb, " secondary=", secondaryModel(object));
		sb.append(" profiles=");
		for (EmitterProfile profile : profiles.values())
		{
			if (profile.isObjectTarget() && profile.getObjectId() == object.getId())
			{
				sb.append(profile.getSignature()).append('(').append(profile.getName()).append(") ");
			}
		}
		log.debug(sb.toString());
	}

	private static void appendPieceSignatures(StringBuilder sb, String label, @Nullable Model model)
	{
		sb.append(label);
		if (model == null)
		{
			sb.append("null");
			return;
		}
		for (ModelSnapshot.Piece piece : ModelSnapshot.capture(model).getPieces())
		{
			sb.append(piece.getSignature()).append(' ');
		}
	}

	@Nullable
	private static Model secondaryModel(TileObject object)
	{
		if (object instanceof WallObject)
		{
			return modelOf(((WallObject) object).getRenderable2());
		}
		if (object instanceof DecorativeObject)
		{
			return modelOf(((DecorativeObject) object).getRenderable2());
		}
		return null;
	}

	private void updateObjectAnchors(ObjectEmitters oe, TileObject object, boolean markers)
	{
		oe.anchorCount = 0;
		if (oe.emitters.isEmpty())
		{
			return;
		}
		Model model = objectModel(object);
		if (model == null)
		{
			return;
		}
		int vertexCount = model.getVerticesCount();
		int total = 0;
		for (ActiveEmitter emitter : oe.emitters)
		{
			total += emitter.vertices.length + emitter.extraAnchors;
		}
		if (oe.anchorXs.length < total)
		{
			oe.anchorXs = new float[total];
			oe.anchorYs = new float[total];
			oe.anchorZs = new float[total];
		}
		LocalPoint lp = object.getLocalLocation();
		int baseZ = Perspective.getTileHeight(client, lp, object.getPlane());
		float[] vx = model.getVerticesX();
		float[] vy = model.getVerticesY();
		float[] vz = model.getVerticesZ();
		for (ActiveEmitter emitter : oe.emitters)
		{
			emitter.anchorStart = oe.anchorCount;
			emitter.anchorCount = 0;
			ParticleStyle style = emitter.style;
			for (int v : emitter.vertices)
			{
				if (v < 0 || v >= vertexCount)
				{
					continue;
				}
				// Scene models arrive pre-rotated, so offsets apply on world
				// axes: X east, Y north, Z up
				oe.anchorXs[oe.anchorCount] = lp.getX() + vx[v] + style.getOffsetX();
				oe.anchorYs[oe.anchorCount] = lp.getY() + vz[v] + style.getOffsetY();
				oe.anchorZs[oe.anchorCount] = baseZ + vy[v] - style.getOffsetZ();
				oe.anchorCount++;
				emitter.anchorCount++;
			}
			emitter.featherReady = emitter.anchorCount == emitter.vertices.length;
			if (emitter.featherReady && emitter.extraAnchors > 0)
			{
				int end = appendInterpolatedAnchors(emitter, oe.anchorXs, oe.anchorYs, oe.anchorZs,
					null, oe.anchorCount);
				emitter.anchorCount += end - oe.anchorCount;
				oe.anchorCount = end;
			}
			fillFaceWorldPositions(emitter, vx, vy, vz, vertexCount,
				style.getOffsetX(), style.getOffsetY(), style.getOffsetZ(),
				lp.getX(), lp.getY(), baseZ, 0, 65536, false);
		}
		if (markers && oe.anchorCount > 0)
		{
			int needed = anchorCount + oe.anchorCount;
			if (anchorXs.length < needed)
			{
				anchorXs = Arrays.copyOf(anchorXs, Math.max(needed, anchorXs.length * 2 + 16));
				anchorYs = Arrays.copyOf(anchorYs, anchorXs.length);
				anchorZs = Arrays.copyOf(anchorZs, anchorXs.length);
			}
			System.arraycopy(oe.anchorXs, 0, anchorXs, anchorCount, oe.anchorCount);
			System.arraycopy(oe.anchorYs, 0, anchorYs, anchorCount, oe.anchorCount);
			System.arraycopy(oe.anchorZs, 0, anchorZs, anchorCount, oe.anchorCount);
			anchorCount = needed;
		}
	}

	private void emitObject(float dt, ObjectEmitters oe)
	{
		boolean anySites = oe.anchorCount > 0;
		if (!anySites)
		{
			for (ActiveEmitter emitter : oe.emitters)
			{
				if (emitter.faceCount() > 0)
				{
					anySites = true;
					break;
				}
			}
		}
		if (!anySites)
		{
			for (ActiveEmitter emitter : oe.emitters)
			{
				emitter.carry = 0;
			}
			return;
		}
		int budget = config.particleMaxParticles();
		float densityScale = config.particleDensity().getFactor();
		for (ActiveEmitter emitter : oe.emitters)
		{
			if (!emitter.hasEmitSites())
			{
				emitter.carry = 0;
				continue;
			}
			ParticleStyle style = emitter.style;
			float sustainable = budget / style.getLifetimeSec() * 0.95f;
			float rate = Math.min(style.getParticlesPerSecond() * densityScale, sustainable);
			emitter.carry += rate * dt;
			int count = (int) emitter.carry;
			emitter.carry -= count;
			if (needsCentroid(style))
			{
				setCentroid(emitter, oe.anchorXs, oe.anchorYs, oe.anchorZs);
			}
			boolean feathered = style.getFeatherStrength() > 0 && emitter.chains != null
				&& emitter.featherReady;
			for (int i = 0; i < count; i++)
			{
				if (particleSystem.getParticles().size() >= budget)
				{
					return;
				}
				if (feathered)
				{
					spawnFeatheredStatic(oe.anchorXs, oe.anchorYs, oe.anchorZs, emitter, true);
				}
				else if (shouldSpawnOnFace(emitter))
				{
					spawnOnFace(emitter, 1f);
				}
				else
				{
					int a = emitter.anchorStart + random.nextInt(emitter.anchorCount);
					spawnAt(style, oe.anchorXs[a], oe.anchorYs[a], oe.anchorZs[a],
						emitter.cx, emitter.cy, emitter.cz, 1f);
				}
			}
		}
	}

	/**
	 * Feathered spawn over static anchors: the smoothed-chain curve without
	 * the movement lerp that the player variant threads through prev arrays.
	 */
	private void spawnFeatheredStatic(float[] xs, float[] ys, float[] zs, ActiveEmitter emitter,
		boolean useCentroid)
	{
		int k = random.nextInt(emitter.sampleChainOf.length);
		int[] chain = emitter.chains[emitter.sampleChainOf[k]];
		int j = emitter.samplePosOf[k];
		int w = emitter.style.getFeatherStrength();
		int jA = Math.max(0, j - 1);
		int jC = Math.min(chain.length - 1, j + 1);
		float t = random.nextFloat();

		float x = quadratic(smoothed(xs, emitter, chain, jA, w),
			smoothed(xs, emitter, chain, j, w),
			smoothed(xs, emitter, chain, jC, w), t);
		float y = quadratic(smoothed(ys, emitter, chain, jA, w),
			smoothed(ys, emitter, chain, j, w),
			smoothed(ys, emitter, chain, jC, w), t);
		float z = quadratic(smoothed(zs, emitter, chain, jA, w),
			smoothed(zs, emitter, chain, j, w),
			smoothed(zs, emitter, chain, jC, w), t);

		// The graphic path has no persistent centroid, so it opts out and both
		// centroid effects stay a no-op there.
		if (useCentroid)
		{
			spawnAt(emitter.style, x, y, z, emitter.cx, emitter.cy, emitter.cz, 1f);
		}
		else
		{
			spawnAt(emitter.style, x, y, z, 1f);
		}
	}

	@Nullable
	private static Model objectModel(TileObject object)
	{
		if (object instanceof GameObject)
		{
			return modelOf(((GameObject) object).getRenderable());
		}
		if (object instanceof WallObject)
		{
			Model model = modelOf(((WallObject) object).getRenderable1());
			return model != null ? model : modelOf(((WallObject) object).getRenderable2());
		}
		if (object instanceof DecorativeObject)
		{
			Model model = modelOf(((DecorativeObject) object).getRenderable());
			return model != null ? model : modelOf(((DecorativeObject) object).getRenderable2());
		}
		if (object instanceof GroundObject)
		{
			return modelOf(((GroundObject) object).getRenderable());
		}
		return null;
	}

	@Nullable
	private static Model modelOf(@Nullable Renderable renderable)
	{
		if (renderable instanceof Model)
		{
			return (Model) renderable;
		}
		if (renderable instanceof DynamicObject)
		{
			return ((DynamicObject) renderable).getModel();
		}
		return null;
	}

	/**
	 * The live animation frame of an animated object's renderable - the same
	 * scene-side surface the Identificator plugin reads - or -1 for static
	 * placements.
	 */
	private static int objectAnimFrame(@Nullable TileObject object)
	{
		Renderable renderable = null;
		if (object instanceof GameObject)
		{
			renderable = ((GameObject) object).getRenderable();
		}
		else if (object instanceof WallObject)
		{
			renderable = ((WallObject) object).getRenderable1();
			if (!(renderable instanceof DynamicObject))
			{
				renderable = ((WallObject) object).getRenderable2();
			}
		}
		else if (object instanceof DecorativeObject)
		{
			renderable = ((DecorativeObject) object).getRenderable();
			if (!(renderable instanceof DynamicObject))
			{
				renderable = ((DecorativeObject) object).getRenderable2();
			}
		}
		else if (object instanceof GroundObject)
		{
			renderable = ((GroundObject) object).getRenderable();
		}
		return renderable instanceof DynamicObject ? ((DynamicObject) renderable).getAnimFrame() : -1;
	}

	/**
	 * Every scenery instance on one plane of the loaded scene, deduplicated
	 * (multi-tile objects appear on each covered tile). Dev-tool path only -
	 * this walks the whole scene.
	 */
	private List<TileObject> sceneObjects(int plane)
	{
		List<TileObject> out = new ArrayList<>();
		Set<TileObject> seen = new HashSet<>();
		Tile[][] tiles = client.getTopLevelWorldView().getScene().getTiles()[plane];
		for (Tile[] column : tiles)
		{
			for (Tile tile : column)
			{
				if (tile == null)
				{
					continue;
				}
				GameObject[] gameObjects = tile.getGameObjects();
				if (gameObjects != null)
				{
					for (GameObject gameObject : gameObjects)
					{
						if (gameObject != null && seen.add(gameObject))
						{
							out.add(gameObject);
						}
					}
				}
				if (tile.getWallObject() != null && seen.add(tile.getWallObject()))
				{
					out.add(tile.getWallObject());
				}
				if (tile.getDecorativeObject() != null && seen.add(tile.getDecorativeObject()))
				{
					out.add(tile.getDecorativeObject());
				}
				if (tile.getGroundObject() != null && seen.add(tile.getGroundObject()))
				{
					out.add(tile.getGroundObject());
				}
			}
		}
		return out;
	}

	/**
	 * Every scenery type on the loaded plane for the viewer capture list: one
	 * entry per object ID with instance count and nearest distance. Client thread.
	 */
	private List<ModelViewerFrame.ObjectSighting> sceneObjectSightings()
	{
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return List.of();
		}
		LocalPoint lp = player.getLocalLocation();
		Map<Integer, Agg> byId = new HashMap<>();
		for (TileObject object : sceneObjects(player.getWorldView().getPlane()))
		{
			int id = object.getId();
			Agg agg = byId.computeIfAbsent(id, k -> new Agg());
			agg.kind = ModelViewerFrame.ObjectKind.of(object);
			agg.count++;
			int dist = object.getLocalLocation().distanceTo(lp) / 128;
			if (dist < agg.nearestTiles)
			{
				agg.nearestTiles = dist;
			}
		}
		List<ModelViewerFrame.ObjectSighting> out = new ArrayList<>();
		for (Map.Entry<Integer, Agg> entry : byId.entrySet())
		{
			int id = entry.getKey();
			Agg agg = entry.getValue();
			String name = objectDisplayName(id);
			out.add(new ModelViewerFrame.ObjectSighting(id, name, agg.nearestTiles, agg.kind, agg.count));
		}
		out.sort((a, b) ->
		{
			int byName = a.name.compareToIgnoreCase(b.name);
			return byName != 0 ? byName : Integer.compare(a.id, b.id);
		});
		return out;
	}

	private static final class Agg
	{
		ModelViewerFrame.ObjectKind kind = ModelViewerFrame.ObjectKind.GAME_OBJECT;
		int count;
		int nearestTiles = Integer.MAX_VALUE;
	}

	@Nullable
	private String objectDisplayName(int objectId)
	{
		try (GamevalManager.Handle handle = gamevalManager.obtainHandle())
		{
			String gameval = handle.getObjectName(objectId);
			if (gameval != null && !gameval.isEmpty())
			{
				return gameval;
			}
		}
		String name = cleanTargetName(client.getObjectDefinition(objectId).getName());
		return name != null ? name : "object_" + objectId;
	}

	/**
	 * Nearby scenery for the viewer's capture list: one entry per object ID,
	 * nearest instance, sorted by distance. Client thread.
	 */
	private List<ModelViewerFrame.ObjectSighting> nearbySightings()
	{
		return sceneObjectSightings();
	}

	// ==================== NPCs and graphics ====================

	private void rebuildProfiledNpcs()
	{
		Set<Integer> ids = new HashSet<>();
		for (EmitterProfile profile : store.snapshotAll().values())
		{
			if (profile.isNpcTarget() && profile.getNpcId() >= 0)
			{
				ids.add(profile.getNpcId());
			}
		}
		profiledNpcIds = ids;
		npcEmitters.clear();
		if (ids.isEmpty())
		{
			return;
		}
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (npc != null)
			{
				trackNpc(npc);
			}
		}
	}

	private void rebuildGraphicStyles()
	{
		graphicEmitters.clear();
		EmitterStore.Snapshot snap = store.snapshot();
		Map<String, ProfileFolder> folders = snap.folders;
		for (Map.Entry<String, EmitterProfile> entry : snap.profiles.entrySet())
		{
			EmitterProfile profile = entry.getValue();
			if (!profile.isGraphicTarget() || profile.getGraphicId() < 0
				|| !ParticlesPanel.effectiveEnabled(profile, folders)
				|| (!developerMode && ParticlesPanel.effectiveWip(profile, folders)))
			{
				continue;
			}
			ParticleStyle style = renderer.getStyle(entry.getKey());
			if (style == null)
			{
				continue;
			}
			int[] locals = new int[profile.getVertices().size()];
			int i = 0;
			for (int local : profile.getVertices())
			{
				locals[i++] = local;
			}
			int[] faceLocals = new int[profile.getFaces().size()];
			i = 0;
			for (int local : profile.getFaces())
			{
				faceLocals[i++] = local;
			}
			graphicEmitters.computeIfAbsent(profile.getGraphicId(), k -> new ArrayList<>())
				.add(new GraphicEmitter(style, profile.getSignature(), locals, faceLocals));
		}
	}

	private void rebuildWeatherZones()
	{
		activeWeatherZones.clear();
		EmitterStore.Snapshot snap = store.snapshot();
		Map<String, ProfileFolder> folders = snap.folders;
		for (Map.Entry<String, EmitterProfile> entry : snap.profiles.entrySet())
		{
			EmitterProfile profile = entry.getValue();
			if (!profile.isWeatherTarget()
				|| profile.getWeatherAreas() == null || profile.getWeatherAreas().isEmpty()
				|| profile.getWeatherParticlesPerTile() <= 0f
				|| !ParticlesPanel.effectiveEnabled(profile, folders)
				|| (!developerMode && ParticlesPanel.effectiveWip(profile, folders)))
			{
				continue;
			}
			ParticleStyle style = renderer.getStyle(entry.getKey());
			if (style == null)
			{
				continue;
			}
			for (String areaName : profile.getWeatherAreas())
			{
				if (areaName == null || areaName.isEmpty())
				{
					continue;
				}
				Area area = areaManager.getArea(areaName);
				if (area == null || area == Area.NONE || area.aabbs == null || area.aabbs.length == 0)
				{
					continue;
				}
				for (AABB aabb : area.aabbs)
				{
					activeWeatherZones.add(new ActiveWeatherZone(
						aabb, style, profile.getWeatherParticlesPerTile(),
						profile.getWeatherDensityScale()));
				}
			}
		}
	}

	/**
	 * Procedural area weather: spawn above the local tile / camera and fall
	 * straight down across the inscribed circle of each active area AABB.
	 */
	private void processWeather(float dt)
	{
		activeWeatherZoneCount = 0;
		if (activeWeatherZones.isEmpty())
		{
			return;
		}
		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null)
		{
			return;
		}
		SceneContext ctx = hdPlugin.getSceneContext();
		WorldPoint playerLoc = localPlayer.getWorldLocation();

		int budget = config.particleMaxParticles();
		float densityScale = config.particleDensity().getFactor();
		float weatherBudget = budget * 0.75f;

		float sumDesired = 0f;
		for (ActiveWeatherZone zone : activeWeatherZones)
		{
			if (!weatherZoneActive(ctx, zone, playerLoc))
			{
				continue;
			}
			sumDesired += desiredWeatherAlive(zone);
		}
		float scale = sumDesired > 1e-3f ? Math.min(1f, weatherBudget / sumDesired) : 1f;
		int plane = anchorLevel;

		for (ActiveWeatherZone zone : activeWeatherZones)
		{
			if (!weatherZoneActive(ctx, zone, playerLoc))
			{
				continue;
			}
			activeWeatherZoneCount++;
			float desiredAlive = desiredWeatherAlive(zone) * scale * densityScale * zone.densityScale;
			float avgLife = zone.style.getLifetimeSec();
			if (avgLife <= 0.01f)
			{
				avgLife = 1f;
			}
			float spawnPerSec = desiredAlive / avgLife;
			zone.spawnAccum += spawnPerSec * dt;
			int toSpawn = (int) zone.spawnAccum;
			zone.spawnAccum -= toSpawn;
			toSpawn = Math.min(toSpawn, 600);
			if (toSpawn <= 0)
			{
				continue;
			}

			int width = zone.aabb.maxX - zone.aabb.minX + 1;
			int height = zone.aabb.maxY - zone.aabb.minY + 1;
			int radiusTiles = Math.max(1, Math.min(width, height) / 2);
			int cx = (zone.aabb.minX + zone.aabb.maxX) / 2;
			int cy = (zone.aabb.minY + zone.aabb.maxY) / 2;

			for (int i = 0; i < toSpawn; i++)
			{
				if (particleSystem.getParticles().size() >= budget)
				{
					return;
				}
				int wx = cx;
				int wy = cy;
				boolean found = false;
				for (int attempt = 0; attempt < 12; attempt++)
				{
					float u = random.nextFloat();
					float r = (float) Math.sqrt(u) * radiusTiles;
					double angle = random.nextFloat() * Math.PI * 2;
					wx = cx + (int) Math.round(Math.cos(angle) * r);
					wy = cy + (int) Math.round(Math.sin(angle) * r);
					if (wx >= zone.aabb.minX && wx <= zone.aabb.maxX
						&& wy >= zone.aabb.minY && wy <= zone.aabb.maxY)
					{
						found = true;
						break;
					}
				}
				if (!found)
				{
					continue;
				}
				int worldPlane = zone.aabb.hasZ()
					? Math.max(0, Math.min(2, zone.aabb.minZ))
					: plane;
				spawnWeatherAtWorld(zone, wx, wy, worldPlane, budget);
			}
		}
	}

	private boolean weatherZoneActive(@Nullable SceneContext ctx, ActiveWeatherZone zone, WorldPoint playerLoc)
	{
		if (zone.aabb.contains(playerLoc))
		{
			return true;
		}
		return ctx != null && ctx.intersects(zone.aabb);
	}

	private void spawnWeatherAtWorld(ActiveWeatherZone zone, int wx, int wy, int worldPlane, int budget)
	{
		LocalPoint lp = LocalPoint.fromWorld(client.getTopLevelWorldView(), wx, wy);
		if (lp == null)
		{
			lp = LocalPoint.fromWorld(client, new WorldPoint(wx, wy, worldPlane));
		}
		if (lp == null)
		{
			return;
		}
		if (particleSystem.getParticles().size() >= budget)
		{
			return;
		}
		float halfTile = LOCAL_TILE_SIZE / 2f;
		float lx = lp.getX() + halfTile + (random.nextFloat() - 0.5f) * LOCAL_TILE_SIZE;
		float ly = lp.getY() + halfTile + (random.nextFloat() - 0.5f) * LOCAL_TILE_SIZE;
		int worldView = lp.getWorldView();
		LocalPoint heightLp = new LocalPoint((int) lx, (int) ly, worldView);
		int ground = Perspective.getTileHeight(client, heightLp, worldPlane);
		float spawnZ = ground - ParticleSystem.WEATHER_SPAWN_ABOVE_GROUND;
		float hideFromCamera = hdPlugin.cameraPosition[1] - ParticleSystem.WEATHER_SPAWN_ABOVE_CAMERA;
		if (spawnZ > hideFromCamera)
		{
			spawnZ = hideFromCamera;
		}
		spawnZ = Math.max(spawnZ, ground - ParticleSystem.WEATHER_SPAWN_MAX_ABOVE_GROUND);
		spawnZ = Math.min(spawnZ, ground - ParticleSystem.WEATHER_SPAWN_MIN_ABOVE_GROUND);
		spawnWeatherAt(zone.style, lx, ly, spawnZ, worldPlane);
	}

	private static float desiredWeatherAlive(ActiveWeatherZone zone)
	{
		AABB aabb = zone.aabb;
		int width = aabb.maxX - aabb.minX + 1;
		int height = aabb.maxY - aabb.minY + 1;
		int radiusTiles = Math.max(1, Math.min(width, height) / 2);
		return (float) (Math.PI * radiusTiles * radiusTiles) * zone.particlesPerTile;
	}

	/** Spawn a weather particle falling straight down from a high point. */
	private void spawnWeatherAt(ParticleStyle style, float x, float y, float z, int worldPlane)
	{
		float jitter = style.getSpawnJitter();
		double jitterAngle = random.nextFloat() * 2 * Math.PI;
		float jitterRadius = jitter * (float) Math.sqrt(random.nextFloat());
		x += (float) Math.cos(jitterAngle) * jitterRadius;
		y += (float) Math.sin(jitterAngle) * jitterRadius;
		z += (random.nextFloat() - 0.5f) * jitter;

		float spread = style.getSpreadSpeed();
		float velX = (random.nextFloat() - 0.5f) * spread;
		float velY = (random.nextFloat() - 0.5f) * spread;
		float fallSpeed = style.getRiseSpeed() < 0
			? -style.getRiseSpeed()
			: Math.max(12, style.getGravity() > 0 ? style.getGravity() : 26);
		float velZ = fallSpeed * (0.75f + random.nextFloat() * 0.5f);

		float wobblePhase = random.nextFloat() * 2f * (float) Math.PI;
		float wobbleFreq = 1.5f + random.nextFloat() * 2f;
		int sizeVariant = random.nextInt(ParticleStyle.SIZE_MULTIPLIERS.length);
		float sizeScale = 1f;
		int sizeJitter = style.getSizeJitter();
		if (sizeJitter > 0)
		{
			int base = style.getBaseSize();
			int offset = random.nextInt(2 * sizeJitter + 1) - sizeJitter;
			int jitteredBase = Math.max(ParticleStyle.MIN_SIZE, base + offset);
			sizeScale = jitteredBase / (float) base;
		}

		Particle p = particleSystem.spawn(x, y, z, velX, velY, velZ,
			style.getLifetimeSec(), style, sizeVariant, sizeScale, wobblePhase, wobbleFreq, spread,
			flipbookFrameFor(style), true, worldPlane);
		if (p != null)
		{
			finalizeSpawn(p, style);
			windowSpawns++;
		}
	}

	/**
	 * Map a vertex-based graphic emitter onto the spot anim's model, once
	 * per rebuild. Attaches across every matching piece like the other
	 * vertex targets.
	 */
	private void resolveGraphic(GraphicEmitter ge, Model model)
	{
		ge.resolveTried = true;
		if (ge.signature == null || (ge.locals.length == 0 && ge.faceLocals.length == 0))
		{
			return;
		}
		ModelSnapshot snapshot = ModelSnapshot.capture(model);
		Set<Integer> faceSet = new HashSet<>();
		for (int local : ge.faceLocals)
		{
			faceSet.add(local);
		}
		for (ModelSnapshot.Piece piece : snapshot.getPieces())
		{
			if (!piece.matchesSignature(ge.signature))
			{
				continue;
			}
			int[] pieceVerts = piece.verticesFor(ge.signature);
			List<Integer> globals = new ArrayList<>();
			for (int local : ge.locals)
			{
				if (local >= 0 && local < pieceVerts.length)
				{
					globals.add(pieceVerts[local]);
				}
			}
			int[] faceCorners = resolveFaceCorners(snapshot, piece, faceSet);
			if (globals.isEmpty() && faceCorners.length == 0)
			{
				continue;
			}
			int[] vertices = new int[globals.size()];
			for (int i = 0; i < vertices.length; i++)
			{
				vertices[i] = globals.get(i);
			}
			int[][] chains = vertices.length > 0
				&& (ge.style.getFeatherStrength() > 0 || ge.style.getInterpolation() > 0)
				? buildChains(snapshot, piece, vertices)
				: null;
			ge.resolved.add(new ActiveEmitter(ge.style, vertices, faceCorners, chains));
		}
	}

	private static final int VISIBLE_ALPHA_MAX = 128;
	private boolean[] visibleMaskScratch = new boolean[0];
	private float[] gfxAnchorXs = new float[0];
	private float[] gfxAnchorYs = new float[0];
	private float[] gfxAnchorZs = new float[0];
	private boolean[] gfxAnchorVisible = new boolean[0];

	/**
	 * Append interpolated midpoints between chain-adjacent anchors, after
	 * the emitter's real anchor block. Midpoint visibility (when tracked)
	 * requires both endpoints visible.
	 *
	 * @return the write index after the appended anchors
	 */
	private static int appendInterpolatedAnchors(ActiveEmitter emitter,
		float[] xs, float[] ys, float[] zs, @Nullable boolean[] visible, int writeIndex)
	{
		int interpolation = emitter.style.getInterpolation();
		for (int[] chain : emitter.chains)
		{
			for (int j = 0; j + 1 < chain.length; j++)
			{
				int a = emitter.anchorStart + chain[j];
				int b = emitter.anchorStart + chain[j + 1];
				for (int s = 1; s <= interpolation; s++)
				{
					float t = s / (float) (interpolation + 1);
					xs[writeIndex] = xs[a] + (xs[b] - xs[a]) * t;
					ys[writeIndex] = ys[a] + (ys[b] - ys[a]) * t;
					zs[writeIndex] = zs[a] + (zs[b] - zs[a]) * t;
					if (visible != null)
					{
						visible[writeIndex] = visible[a] && visible[b];
					}
					writeIndex++;
				}
			}
		}
		return writeIndex;
	}

	/**
	 * Spawn a batch for a graphic emitter at its base point. Vertex-based
	 * emitters spawn only from CURRENTLY VISIBLE vertices: swipe-style gfx
	 * keep their whole mesh in place and animate the sweep purely with face
	 * transparency, so hidden vertices must not emit ahead of the visible
	 * arc. Feathered emitters trace the smoothed chain instead (a chain
	 * across a half-revealed mesh has no clean meaning). While the whole
	 * mesh is hidden the carry resets so the reveal doesn't burst.
	 *
	 * @return false when the particle budget is exhausted
	 */
	private boolean spawnGraphicBatch(GraphicEmitter ge, @Nullable Model model, int count,
		float baseX, float baseY, float baseZ, int sin, int cos, int budget, double[] carries, int gi)
	{
		ParticleStyle style = ge.style;
		float ox = baseX + style.getOffsetX();
		float oy = baseY + style.getOffsetY();
		float oz = baseZ - style.getOffsetZ();
		if (model == null || ge.resolved.isEmpty())
		{
			for (int i = 0; i < count; i++)
			{
				if (particleSystem.getParticles().size() >= budget)
				{
					return false;
				}
				spawnAt(style, ox, oy, oz, 1f);
			}
			return true;
		}
		boolean feathered = style.getFeatherStrength() > 0;
		boolean interpolated = !feathered && style.getInterpolation() > 0;
		boolean[] visible = feathered ? null : visibleVertexMask(model);
		float[] vx = model.getVerticesX();
		float[] vy = model.getVerticesY();
		float[] vz = model.getVerticesZ();
		// The anchors an emitter fills are constant for the whole batch (the
		// model doesn't move between particles this tick), so refill only
		// when the randomly picked emitter changes, not per particle
		ActiveEmitter filled = null;
		for (int i = 0; i < count; i++)
		{
			if (particleSystem.getParticles().size() >= budget)
			{
				return false;
			}
			ActiveEmitter emitter = ge.resolved.size() == 1
				? ge.resolved.get(0)
				: ge.resolved.get(random.nextInt(ge.resolved.size()));
			if (feathered && emitter.chains != null)
			{
				if (filled != emitter)
				{
					fillGraphicAnchors(emitter, model, ox, oy, oz, sin, cos, null);
					filled = emitter;
				}
				spawnFeatheredStatic(gfxAnchorXs, gfxAnchorYs, gfxAnchorZs, emitter, false);
			}
			else if (interpolated && emitter.chains != null)
			{
				if (filled != emitter)
				{
					fillGraphicAnchors(emitter, model, ox, oy, oz, sin, cos, visible);
					filled = emitter;
				}
				int a = pickVisibleAnchor(emitter.anchorCount);
				if (a < 0)
				{
					carries[gi] = 0;
					return true;
				}
				spawnAt(style, gfxAnchorXs[a], gfxAnchorYs[a], gfxAnchorZs[a], 1f);
			}
			else if (emitter.faceCount() > 0
				&& (emitter.vertices.length == 0 || random.nextInt(emitter.vertices.length + emitter.faceCount()) < emitter.faceCount()))
			{
				fillFaceWorldPositions(emitter, vx, vy, vz, model.getVerticesCount(),
					0, 0, 0, ox, oy, oz, sin, cos, true);
				spawnOnFace(emitter, 1f);
			}
			else
			{
				int v = pickVisibleVertex(emitter.vertices, visible, model.getVerticesCount());
				if (v < 0)
				{
					carries[gi] = 0;
					return true;
				}
				// Rotate the spot anim's local mesh by the actor's facing, the
				// same transform the player anchor path uses; graphics objects
				// pass identity (they are already world-oriented per direction)
				spawnAt(style, ox + (vz[v] * sin + vx[v] * cos) / 65536f,
					oy + (vz[v] * cos - vx[v] * sin) / 65536f, oz + vy[v], 1f);
			}
		}
		return true;
	}

	private int pickVisibleAnchor(int count)
	{
		int start = random.nextInt(count);
		for (int i = 0; i < count; i++)
		{
			int a = (start + i) % count;
			if (gfxAnchorVisible[a])
			{
				return a;
			}
		}
		return -1;
	}

	/**
	 * Vertices touching at least one face under the transparency threshold,
	 * or null when the model has no per-face alpha (everything visible).
	 */
	@Nullable
	private boolean[] visibleVertexMask(Model model)
	{
		byte[] transparencies = model.getFaceTransparencies();
		if (transparencies == null)
		{
			return null;
		}
		int vertexCount = model.getVerticesCount();
		if (visibleMaskScratch.length < vertexCount)
		{
			visibleMaskScratch = new boolean[vertexCount];
		}
		boolean[] mask = visibleMaskScratch;
		Arrays.fill(mask, 0, vertexCount, false);
		int[] f1 = model.getFaceIndices1();
		int[] f2 = model.getFaceIndices2();
		int[] f3 = model.getFaceIndices3();
		int faceCount = Math.min(model.getFaceCount(), transparencies.length);
		for (int f = 0; f < faceCount; f++)
		{
			if ((transparencies[f] & 0xFF) <= VISIBLE_ALPHA_MAX)
			{
				mask[f1[f]] = true;
				mask[f2[f]] = true;
				mask[f3[f]] = true;
			}
		}
		return mask;
	}

	private int pickVisibleVertex(int[] vertices, @Nullable boolean[] visible, int vertexCount)
	{
		if (visible == null)
		{
			int v = vertices[random.nextInt(vertices.length)];
			return v >= 0 && v < vertexCount ? v : -1;
		}
		// Random start with a linear probe; emitter vertex sets are small
		int start = random.nextInt(vertices.length);
		for (int i = 0; i < vertices.length; i++)
		{
			int v = vertices[(start + i) % vertices.length];
			if (v >= 0 && v < vertexCount && v < visible.length && visible[v])
			{
				return v;
			}
		}
		return -1;
	}

	private void fillGraphicAnchors(ActiveEmitter emitter, Model model, float ox, float oy, float oz,
		int sin, int cos, @Nullable boolean[] vertexMask)
	{
		int real = emitter.vertices.length;
		int n = real + emitter.extraAnchors;
		if (gfxAnchorXs.length < n)
		{
			gfxAnchorXs = new float[n];
			gfxAnchorYs = new float[n];
			gfxAnchorZs = new float[n];
		}
		if (gfxAnchorVisible.length < n)
		{
			gfxAnchorVisible = new boolean[n];
		}
		int vertexCount = model.getVerticesCount();
		float[] vx = model.getVerticesX();
		float[] vy = model.getVerticesY();
		float[] vz = model.getVerticesZ();
		for (int k = 0; k < real; k++)
		{
			int v = emitter.vertices[k];
			if (v < 0 || v >= vertexCount)
			{
				gfxAnchorXs[k] = ox;
				gfxAnchorYs[k] = oy;
				gfxAnchorZs[k] = oz;
				gfxAnchorVisible[k] = false;
				continue;
			}
			gfxAnchorXs[k] = ox + (vz[v] * sin + vx[v] * cos) / 65536f;
			gfxAnchorYs[k] = oy + (vz[v] * cos - vx[v] * sin) / 65536f;
			gfxAnchorZs[k] = oz + vy[v];
			gfxAnchorVisible[k] = vertexMask == null || (v < vertexMask.length && vertexMask[v]);
		}
		emitter.anchorStart = 0;
		emitter.anchorCount = real;
		emitter.featherReady = true;
		if (emitter.extraAnchors > 0)
		{
			emitter.anchorCount = appendInterpolatedAnchors(emitter,
				gfxAnchorXs, gfxAnchorYs, gfxAnchorZs, gfxAnchorVisible, real);
		}
	}

	/**
	 * NPCs run the player pipeline - posed model anchors, orientation,
	 * trails, feathering, animation gates - minus the equipment resolution
	 * (the NPC ID is the whole identity). Like players they honour the
	 * stacked-actor draw: among size-1 NPCs sharing a tile center the engine
	 * draws only the lowest scene index, so the rest are gated out of emission.
	 */
	private void processNpcs(float dt, int level, int radiusUnits, LocalPoint localLp)
	{
		if (npcEmitters.isEmpty())
		{
			return;
		}
		int revision = store.getRevision();
		boolean markers = false;
		for (Map.Entry<NPC, PlayerEmitters> entry : npcEmitters.entrySet())
		{
			NPC npc = entry.getKey();
			PlayerEmitters pe = entry.getValue();
			if (npc.getWorldLocation().getPlane() != level
				|| npc.getLocalLocation().distanceTo(localLp) > radiusUnits
				|| (isCenteredSize1(npc) && npcTileOwners.get(tileKey(npc)) != npc))
			{
				// Out of scope, or a stacked npc the engine does not draw
				pe.anchorCount = 0;
				pe.prevCount = 0;
				for (ActiveEmitter emitter : pe.emitters)
				{
					emitter.carry = 0;
				}
				continue;
			}
			if (pe.revision != revision || pe.equipmentIds == null
				|| pe.equipmentIds[0] != npc.getId())
			{
				resolveNpc(pe, npc, revision);
			}
			updateAnchors(pe, npc, markers);
			emit(dt, pe, npc);
			emitActorSpotAnims(dt, pe, npc);
		}
	}

	private void resolveNpc(PlayerEmitters pe, NPC npc, int revision)
	{
		pe.revision = revision;
		// The equipment cache slot doubles as the resolve key for NPCs: a
		// single-element array holding the (transform-aware) NPC ID
		pe.equipmentIds = new int[]{npc.getId()};
		pe.rebuilt = true;
		pe.emitters.clear();
		Model model = npc.getModel();
		if (model == null)
		{
			pe.equipmentIds = null;
			return;
		}
		ModelSnapshot snapshot = ModelSnapshot.capture(model);
		EmitterStore.Snapshot snap = store.snapshot();
		Map<String, ProfileFolder> folders = snap.folders;
		for (Map.Entry<String, EmitterProfile> entry : snap.profiles.entrySet())
		{
			EmitterProfile profile = entry.getValue();
			if (!profile.isNpcTarget() || profile.getNpcId() != npc.getId()
				|| !ParticlesPanel.effectiveEnabled(profile, folders)
				|| (profile.getVertices().isEmpty() && profile.getFaces().isEmpty())
				|| (!developerMode && ParticlesPanel.effectiveWip(profile, folders)))
			{
				continue;
			}
			ParticleStyle style = renderer.getStyle(entry.getKey());
			if (style == null)
			{
				continue;
			}
			for (ModelSnapshot.Piece piece : snapshot.getPieces())
			{
				if (!piece.matchesSignature(profile.getSignature()))
				{
					continue;
				}
				ActiveEmitter emitter = resolveMeshEmitter(style, snapshot, piece, profile);
				if (emitter != null)
				{
					pe.emitters.add(emitter);
				}
			}
		}
	}

	/**
	 * Emission for graphic profiles matching the actor's active spot anims
	 * (vengeance, skulls, teleports, weapon swipes). Point-based when no
	 * vertices were picked; vertex-anchored spot anims rotate their local
	 * mesh by the actor's facing, like the player and NPC anchor paths.
	 */
	private void emitActorSpotAnims(float dt, PlayerEmitters pe, Actor actor)
	{
		if (graphicEmitters.isEmpty())
		{
			return;
		}
		int budget = config.particleMaxParticles();
		float densityScale = config.particleDensity().getFactor();
		LocalPoint lp = actor.getLocalLocation();
		// Spot anim models are authored facing orientation 0 and the engine
		// turns them with the actor; mirror that here so anchors track facing
		int orientation = actor.getCurrentOrientation();
		int sin = Perspective.SINE[orientation];
		int cos = Perspective.COSINE[orientation];
		int actorBase = Integer.MIN_VALUE;
		for (ActorSpotAnim spotAnim : actor.getSpotAnims())
		{
			List<GraphicEmitter> list = graphicEmitters.get(spotAnim.getId());
			if (list == null)
			{
				continue;
			}
			if (pe.spotAnimCarries == null)
			{
				pe.spotAnimCarries = new HashMap<>();
			}
			double[] carries = pe.spotAnimCarries.get(spotAnim.getId());
			if (carries == null || carries.length != list.size())
			{
				carries = new double[list.size()];
				pe.spotAnimCarries.put(spotAnim.getId(), carries);
			}
			Model model = null;
			for (int gi = 0; gi < list.size(); gi++)
			{
				GraphicEmitter ge = list.get(gi);
				// Frame windows gate spot anim emission like action anims;
				// reset the carry so the window opening doesn't burst
				if (!ge.style.frameMatches(spotAnim.getFrame()))
				{
					carries[gi] = 0;
					continue;
				}
				float sustainable = budget / ge.style.getLifetimeSec() * 0.95f;
				carries[gi] += Math.min(ge.style.getParticlesPerSecond() * densityScale, sustainable) * dt;
				int count = (int) carries[gi];
				carries[gi] -= count;
				if (count == 0)
				{
					continue;
				}
				if (model == null)
				{
					model = spotAnim.getModel();
				}
				if (!ge.resolveTried && model != null)
				{
					resolveGraphic(ge, model);
				}
				if (actorBase == Integer.MIN_VALUE)
				{
					actorBase = Perspective.getFootprintTileHeight(client, lp, anchorLevel, actor.getFootprintSize())
						- actor.getAnimationHeightOffset();
				}
				float z = actorBase - spotAnim.getHeight();
				if (!spawnGraphicBatch(ge, model, count, lp.getX(), lp.getY(), z, sin, cos,
					budget, carries, gi))
				{
					return;
				}
			}
		}
	}

	/**
	 * Point emission at live graphics objects (spell splats, tile effects)
	 * whose IDs carry a graphic profile.
	 */
	private void emitGraphicsObjects(float dt, int level, int radiusUnits, LocalPoint localLp)
	{
		if (graphicEmitters.isEmpty())
		{
			if (!graphicCarries.isEmpty())
			{
				graphicCarries.clear();
			}
			return;
		}
		liveGraphics.clear();
		int budget = config.particleMaxParticles();
		float densityScale = config.particleDensity().getFactor();
		for (GraphicsObject graphic : client.getTopLevelWorldView().getGraphicsObjects())
		{
			if (graphic.finished())
			{
				continue;
			}
			List<GraphicEmitter> list = graphicEmitters.get(graphic.getId());
			if (list == null)
			{
				continue;
			}
			liveGraphics.add(graphic);
			LocalPoint lp = graphic.getLocation();
			if (graphic.getLevel() != level || lp.distanceTo(localLp) > radiusUnits)
			{
				continue;
			}
			double[] carries = graphicCarries.get(graphic);
			if (carries == null || carries.length != list.size())
			{
				carries = new double[list.size()];
				graphicCarries.put(graphic, carries);
			}
			Model model = graphic.getModel();
			for (int gi = 0; gi < list.size(); gi++)
			{
				GraphicEmitter ge = list.get(gi);
				if (!ge.resolveTried && model != null)
				{
					resolveGraphic(ge, model);
				}
				float sustainable = budget / ge.style.getLifetimeSec() * 0.95f;
				carries[gi] += Math.min(ge.style.getParticlesPerSecond() * densityScale, sustainable) * dt;
				int count = (int) carries[gi];
				carries[gi] -= count;
				// Identity rotation: graphics objects place a per-direction
				// model already in world orientation
				if (count > 0 && !spawnGraphicBatch(ge, model, count, lp.getX(), lp.getY(),
					graphic.getZ(), 0, 65536, budget, carries, gi))
				{
					return;
				}
			}
		}
		graphicCarries.keySet().retainAll(liveGraphics);
	}

	@Nullable
	private String npcDisplayName(int npcId)
	{
		try (GamevalManager.Handle handle = gamevalManager.obtainHandle())
		{
			String gameval = handle.getNpcName(npcId);
			if (gameval != null && !gameval.isEmpty())
			{
				return gameval;
			}
		}
		NPCComposition composition = client.getNpcDefinition(npcId);
		String name = composition == null ? null : cleanTargetName(composition.getName());
		return name != null ? name : "npc_" + npcId;
	}

	/**
	 * Every NPC on the loaded plane for the viewer capture list: one entry per
	 * NPC ID with instance count and nearest distance. Client thread.
	 */
	private List<ModelViewerFrame.ObjectSighting> npcSightings()
	{
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return List.of();
		}
		LocalPoint lp = player.getLocalLocation();
		int plane = player.getWorldView().getPlane();
		Map<Integer, Agg> byId = new HashMap<>();
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (npc == null || npc.getWorldLocation().getPlane() != plane)
			{
				continue;
			}
			int id = npc.getId();
			Agg agg = byId.computeIfAbsent(id, k -> new Agg());
			agg.count++;
			int dist = npc.getLocalLocation().distanceTo(lp) / 128;
			if (dist < agg.nearestTiles)
			{
				agg.nearestTiles = dist;
			}
		}
		List<ModelViewerFrame.ObjectSighting> out = new ArrayList<>();
		for (Map.Entry<Integer, Agg> entry : byId.entrySet())
		{
			int id = entry.getKey();
			Agg agg = entry.getValue();
			out.add(new ModelViewerFrame.ObjectSighting(id, npcDisplayName(id), agg.nearestTiles,
				ModelViewerFrame.ObjectKind.GAME_OBJECT, agg.count));
		}
		out.sort((a, b) ->
		{
			int byName = a.name.compareToIgnoreCase(b.name);
			return byName != 0 ? byName : Integer.compare(a.id, b.id);
		});
		return out;
	}

	/**
	 * Recently seen spot anims / graphics with their source label, newest
	 * first. Client thread.
	 */
	private List<ModelViewerFrame.GraphicSighting> recentGraphicList()
	{
		long nowMs = System.currentTimeMillis();
		List<ModelViewerFrame.GraphicSighting> out = new ArrayList<>(recentGraphics.size());
		for (Map.Entry<Integer, long[]> entry : recentGraphics.entrySet())
		{
			int id = entry.getKey();
			out.add(new ModelViewerFrame.GraphicSighting(id,
				recentGraphicSource.getOrDefault(id, ""),
				(int) entry.getValue()[0],
				(int) ((nowMs - entry.getValue()[1]) / 1000)));
		}
		out.sort((a, b) -> Integer.compare(a.secondsAgo, b.secondsAgo));
		return out;
	}

	// ==================== ModelViewerFrame.Callbacks ====================

	@Override
	public void refreshSnapshot()
	{
		int objectId = viewerObjectId;
		int npcId = viewerNpcId;
		int graphicId = viewerGraphicId;
		clientThread.invokeLater(() ->
		{
			if (objectId >= 0)
			{
				captureObjectSnapshot(objectId);
			}
			else if (npcId >= 0)
			{
				captureNpcSnapshot(npcId);
			}
			else if (graphicId >= 0)
			{
				captureGraphicSnapshot(graphicId);
			}
			else
			{
				capturePlayerSnapshot();
			}
		});
	}

	@Override
	public void loadObject(int objectId)
	{
		viewerObjectId = objectId;
		viewerNpcId = -1;
		viewerGraphicId = -1;
		refreshSnapshot();
	}

	@Override
	public void loadNpc(int npcId)
	{
		viewerNpcId = npcId;
		viewerObjectId = -1;
		viewerGraphicId = -1;
		refreshSnapshot();
	}

	@Override
	public void loadGraphic(int graphicId)
	{
		viewerGraphicId = graphicId;
		viewerObjectId = -1;
		viewerNpcId = -1;
		refreshSnapshot();
	}

	/**
	 * Begin sampling the viewer's target every tick for ~3 seconds. Object
	 * and NPC sources are pinned to their nearest instance up front;
	 * graphics are re-found per tick since their instances are transient.
	 * Client thread.
	 */
	private void startRecording(int objectId, int npcId, int graphicId)
	{
		recordObjectId = objectId;
		recordNpcId = npcId;
		recordGraphicId = graphicId;
		recordObject = null;
		recordNpc = null;
		recordSnapshot = null;
		recordXs.clear();
		recordYs.clear();
		recordZs.clear();
		recordFrames.clear();

		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return;
		}
		LocalPoint lp = player.getLocalLocation();
		if (objectId >= 0)
		{
			int bestDist = Integer.MAX_VALUE;
			for (TileObject object : sceneObjects(player.getWorldView().getPlane()))
			{
				if (object.getId() != objectId)
				{
					continue;
				}
				int dist = object.getLocalLocation().distanceTo(lp);
				if (dist < bestDist)
				{
					bestDist = dist;
					recordObject = object;
				}
			}
		}
		else if (npcId >= 0)
		{
			int bestDist = Integer.MAX_VALUE;
			for (NPC npc : client.getTopLevelWorldView().npcs())
			{
				if (npc == null || npc.getId() != npcId)
				{
					continue;
				}
				int dist = npc.getLocalLocation().distanceTo(lp);
				if (dist < bestDist)
				{
					bestDist = dist;
					recordNpc = npc;
				}
			}
		}
		recordTicksLeft = 150;
	}

	private void sampleRecording()
	{
		recordTicksLeft--;
		Model model = null;
		int frame = -1;
		if (recordObjectId >= 0)
		{
			model = recordObject == null ? null : objectModel(recordObject);
			frame = objectAnimFrame(recordObject);
		}
		else if (recordNpcId >= 0)
		{
			model = recordNpc == null ? null : recordNpc.getModel();
			frame = recordNpc == null ? -1 : recordNpc.getAnimationFrame();
		}
		else if (recordGraphicId >= 0)
		{
			ActorSpotAnim spotAnim = findSpotAnim(recordGraphicId);
			if (spotAnim != null)
			{
				model = spotAnim.getModel();
				frame = spotAnim.getFrame();
			}
			else
			{
				model = findGraphicModel(recordGraphicId);
			}
		}
		else
		{
			Player local = client.getLocalPlayer();
			model = local == null ? null : local.getModel();
			frame = local == null ? -1 : local.getAnimationFrame();
		}

		if (model == null && !recordXs.isEmpty())
		{
			// Source vanished mid-recording (a gfx ended, an NPC died); ship
			// what was sampled instead of waiting out the window
			recordTicksLeft = 0;
		}
		if (model != null)
		{
			if (recordSnapshot == null)
			{
				recordSnapshot = ModelSnapshot.capture(model);
			}
			if (model.getVerticesCount() != recordSnapshot.getVertexCount())
			{
				// Topology changed mid-recording (gear swap etc.); abort and
				// drop the source refs so a despawned object/NPC isn't pinned
				recordTicksLeft = 0;
				recordSnapshot = null;
				recordObject = null;
				recordNpc = null;
				recordXs.clear();
				recordYs.clear();
				recordZs.clear();
				recordFrames.clear();
				return;
			}
			// Trim to the logical vertex count: live model arrays are backing
			// buffers that can run longer than the vertices they hold
			int count = recordSnapshot.getVertexCount();
			recordXs.add(Arrays.copyOf(model.getVerticesX(), count));
			recordYs.add(Arrays.copyOf(model.getVerticesY(), count));
			recordZs.add(Arrays.copyOf(model.getVerticesZ(), count));
			recordFrames.add(frame);
		}

		if (recordTicksLeft == 0)
		{
			finishRecording();
		}
	}

	private void finishRecording()
	{
		ModelSnapshot snapshot = recordSnapshot;
		recordSnapshot = null;
		recordObject = null;
		recordNpc = null;
		if (snapshot == null || recordXs.isEmpty())
		{
			return;
		}
		float[][] xs = recordXs.toArray(new float[0][]);
		float[][] ys = recordYs.toArray(new float[0][]);
		float[][] zs = recordZs.toArray(new float[0][]);
		int[] frames = new int[recordFrames.size()];
		for (int i = 0; i < frames.length; i++)
		{
			frames[i] = recordFrames.get(i);
		}
		recordXs.clear();
		recordYs.clear();
		recordZs.clear();
		recordFrames.clear();

		// Recordings piggyback on the snapshot already in the viewer - no
		// re-push, so the camera and picks are undisturbed; the viewer drops
		// any recording whose topology no longer matches what it shows
		SwingUtilities.invokeLater(() ->
		{
			if (viewerFrame != null)
			{
				viewerFrame.setRecording(xs, ys, zs, frames);
			}
		});
	}

	@Nullable
	private ActorSpotAnim findSpotAnim(int graphicId)
	{
		for (Player p : client.getTopLevelWorldView().players())
		{
			if (p == null)
			{
				continue;
			}
			for (ActorSpotAnim spotAnim : p.getSpotAnims())
			{
				if (spotAnim.getId() == graphicId)
				{
					return spotAnim;
				}
			}
		}
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (npc == null)
			{
				continue;
			}
			for (ActorSpotAnim spotAnim : npc.getSpotAnims())
			{
				if (spotAnim.getId() == graphicId)
				{
					return spotAnim;
				}
			}
		}
		return null;
	}

	@Override
	public void playerViewSelected()
	{
		if (viewerObjectId != -1 || viewerNpcId != -1 || viewerGraphicId != -1)
		{
			viewerObjectId = -1;
			viewerNpcId = -1;
			viewerGraphicId = -1;
			refreshSnapshot();
		}
	}

	@Override
	public String createGraphicProfile(int graphicId)
	{
		return store.ensureGraphicProfile(graphicId, "gfx " + graphicId);
	}

	@Override
	public String createWeatherProfile(List<String> weatherAreas)
	{
		String name = weatherAreas.isEmpty() ? "Weather" : weatherAreas.get(0);
		return store.ensureWeatherProfile(weatherAreas, name);
	}

	@Override
	public List<String> areaNames()
	{
		List<String> names = new ArrayList<>();
		for (Area area : AreaManager.AREAS)
		{
			if (area == null || area.name == null || area.name.isEmpty())
			{
				continue;
			}
			if ("NONE".equals(area.name) || "ALL".equals(area.name))
			{
				continue;
			}
			names.add(area.name);
		}
		Collections.sort(names);
		return names;
	}

	/**
	 * Capture the local player's composite into the viewer. Client thread.
	 */
	/**
	 * Client-thread snapshot for the dev viewer when open; geometry only
	 * otherwise so face colour arrays are not cloned every frame.
	 */
	private ModelSnapshot captureModelSnapshot(Model model)
	{
		return viewerFrame != null ? ModelSnapshot.captureForViewer(model) : ModelSnapshot.capture(model);
	}

	private void capturePlayerSnapshot()
	{
		pendingGraphicCapture = -1;
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return;
		}
		PlayerComposition composition = player.getPlayerComposition();
		Model model = player.getModel();
		if (composition == null || model == null)
		{
			return;
		}
		ModelSnapshot snapshot = captureModelSnapshot(model);
		List<String> wornItems = new ArrayList<>();
		for (int itemId : wornItemIds(composition))
		{
			wornItems.add(itemId + " - " + client.getItemDefinition(itemId).getName());
		}
		List<int[]> recent = recentProjectileList();
		List<ModelViewerFrame.ObjectSighting> sightings = nearbySightings();
		List<ModelViewerFrame.ObjectSighting> npcs = npcSightings();
		List<ModelViewerFrame.GraphicSighting> recentGfx = recentGraphicList();
		SwingUtilities.invokeLater(() ->
		{
			viewerSnapshot = snapshot;
			viewerTargetName = null;
			if (viewerFrame != null)
			{
				viewerFrame.setSnapshot(snapshot,
					selectedGlobals(snapshot, viewerFrame.getSelectedProfileKey()),
					profileEntriesBySignature(), wornItems,
					projectileProfileEntries(), recent,
					objectProfileEntries(), sightings,
					npcProfileEntries(), npcs,
					graphicProfileEntries(), recentGfx,
					weatherProfileEntries());
			}
		});
		// Auto-record so an animation performed right after refreshing lands
		// in the scrubber without a separate action
		startRecording(-1, -1, -1);
	}

	/**
	 * Capture the nearest instance of this object into the viewer, falling
	 * back to the player when none is loaded. Client thread.
	 */
	private void captureObjectSnapshot(int objectId)
	{
		pendingGraphicCapture = -1;
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return;
		}
		LocalPoint lp = player.getLocalLocation();
		TileObject best = null;
		int bestDist = Integer.MAX_VALUE;
		for (TileObject object : sceneObjects(player.getWorldView().getPlane()))
		{
			if (object.getId() != objectId)
			{
				continue;
			}
			int dist = object.getLocalLocation().distanceTo(lp);
			if (dist < bestDist)
			{
				bestDist = dist;
				best = object;
			}
		}
		Model model = best == null ? null : objectModel(best);
		if (model == null)
		{
			SwingUtilities.invokeLater(() -> viewerObjectId = -1);
			capturePlayerSnapshot();
			return;
		}
		String name = objectDisplayName(objectId);
		pushNonPlayerSnapshot(captureModelSnapshot(model), name);
		if (best != null && objectAnimFrame(best) >= 0)
		{
			// Animated placement: auto-record its cycle for the scrubber
			startRecording(objectId, -1, -1);
		}
	}

	/**
	 * Capture the nearest instance of this NPC into the viewer, falling back
	 * to the player when none is in scene. Client thread.
	 */
	private void captureNpcSnapshot(int npcId)
	{
		pendingGraphicCapture = -1;
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return;
		}
		LocalPoint lp = player.getLocalLocation();
		NPC best = null;
		int bestDist = Integer.MAX_VALUE;
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (npc == null || npc.getId() != npcId)
			{
				continue;
			}
			int dist = npc.getLocalLocation().distanceTo(lp);
			if (dist < bestDist)
			{
				bestDist = dist;
				best = npc;
			}
		}
		Model model = best == null ? null : best.getModel();
		String name = best == null ? null : cleanTargetName(best.getName());
		if (model == null)
		{
			// No live instance in scene: build the mesh from the cache
			// definition instead, so authoring needs nothing spawned
			ModelData cached = npcCacheModel(npcId);
			if (cached != null)
			{
				model = cached.light();
				NPCComposition composition = client.getNpcDefinition(npcId);
				name = composition == null ? null : cleanTargetName(composition.getName());
			}
		}
		if (model == null)
		{
			SwingUtilities.invokeLater(() -> viewerNpcId = -1);
			capturePlayerSnapshot();
			return;
		}
		pushNonPlayerSnapshot(captureModelSnapshot(model), name);
		if (best != null)
		{
			// Live NPC: auto-record its current animation for the scrubber
			startRecording(-1, npcId, -1);
		}
	}

	/**
	 * The NPC's composed cache mesh (unposed), or null. Client thread.
	 */
	@Nullable
	private ModelData npcCacheModel(int npcId)
	{
		NPCComposition composition = client.getNpcDefinition(npcId);
		int[] modelIds = composition == null ? null : composition.getModels();
		if (modelIds == null || modelIds.length == 0)
		{
			return null;
		}
		List<ModelData> parts = new ArrayList<>();
		for (int modelId : modelIds)
		{
			ModelData part = client.loadModelData(modelId);
			if (part != null)
			{
				parts.add(part);
			}
		}
		return parts.isEmpty() ? null : client.mergeModels(parts.toArray(new ModelData[0]));
	}

	@Override
	public void poseAnimation(int animId)
	{
		int npcId = viewerNpcId;
		if (npcId < 0)
		{
			// Player and object meshes cannot be rebuilt from the cache
			// through the API (worn equipment models are not exposed), so
			// posing only works on NPC snapshots - say so instead of no-op
			if (viewerFrame != null)
			{
				viewerFrame.showHint("Pose needs an NPC snapshot. For players and objects: Refresh, then perform the animation - it auto-records for the scrubber.");
			}
			return;
		}
		if (animId < 0)
		{
			return;
		}
		clientThread.invokeLater(() -> poseNpcAnimation(npcId, animId));
	}

	/**
	 * Build a synthetic scrubber recording straight from the cache: the
	 * NPC's composed mesh posed at every exact frame of the animation via
	 * AnimationController.animate - nothing needs to exist in the scene or
	 * play in game. Client thread.
	 */
	private void poseNpcAnimation(int npcId, int animId)
	{
		ModelData merged = npcCacheModel(npcId);
		if (merged == null)
		{
			log.debug("Cache pose failed: no models for npc {}", npcId);
			return;
		}
		AnimationController controller = new AnimationController(client, animId);
		Animation animation = controller.getAnimation();
		int numFrames = animation == null ? 0 : animation.getNumFrames();
		if (numFrames <= 0)
		{
			log.debug("Cache pose failed: no frames for anim {}", animId);
			return;
		}
		numFrames = Math.min(numFrames, 500);

		float[][] xs = new float[numFrames][];
		float[][] ys = new float[numFrames][];
		float[][] zs = new float[numFrames][];
		int[] frames = new int[numFrames];
		ModelSnapshot topology = null;
		for (int f = 0; f < numFrames; f++)
		{
			// Fresh lit copy per frame so transforms never accumulate
			Model base = merged.shallowCopy().cloneVertices().light();
			controller.setFrame(f);
			Model posed = controller.animate(base);
			if (posed == null)
			{
				posed = base;
			}
			if (topology == null)
			{
				topology = captureModelSnapshot(posed);
			}
			if (posed.getVerticesCount() != topology.getVertexCount())
			{
				log.debug("Cache pose aborted: vertex count changed at frame {}", f);
				return;
			}
			int count = topology.getVertexCount();
			xs[f] = Arrays.copyOf(posed.getVerticesX(), count);
			ys[f] = Arrays.copyOf(posed.getVerticesY(), count);
			zs[f] = Arrays.copyOf(posed.getVerticesZ(), count);
			frames[f] = f;
		}

		pushViewerSnapshot(topology, null, false);
		SwingUtilities.invokeLater(() ->
		{
			if (viewerFrame != null)
			{
				viewerFrame.setRecording(xs, ys, zs, frames);
			}
		});
	}

	/**
	 * Capture a live instance of this spot anim into the viewer - a graphics
	 * object or an actor playing it - falling back to the player when none
	 * is active. Client thread.
	 */
	private void captureGraphicSnapshot(int graphicId)
	{
		Model model = findGraphicModel(graphicId);
		if (model == null)
		{
			// Not playing right now; capture it the moment it next appears
			pendingGraphicCapture = graphicId;
			return;
		}
		pushGraphicSnapshot(graphicId, model);
	}

	@Nullable
	private Model findGraphicModel(int graphicId)
	{
		for (GraphicsObject graphic : client.getTopLevelWorldView().getGraphicsObjects())
		{
			if (!graphic.finished() && graphic.getId() == graphicId)
			{
				Model model = graphic.getModel();
				if (model != null)
				{
					return model;
				}
			}
		}
		for (Player p : client.getTopLevelWorldView().players())
		{
			Model model = p == null ? null : spotAnimModel(p, graphicId);
			if (model != null)
			{
				return model;
			}
		}
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			Model model = npc == null ? null : spotAnimModel(npc, graphicId);
			if (model != null)
			{
				return model;
			}
		}
		return null;
	}

	private void pushGraphicSnapshot(int graphicId, Model model)
	{
		pendingGraphicCapture = -1;
		pushNonPlayerSnapshot(captureModelSnapshot(model), null);
		SwingUtilities.invokeLater(() ->
		{
			if (viewerFrame != null)
			{
				viewerFrame.notifyGraphicSnapshot();
			}
		});
		// Auto-record while the graphic is still playing
		startRecording(-1, -1, graphicId);
	}

	@Nullable
	private static Model spotAnimModel(Actor actor, int graphicId)
	{
		for (ActorSpotAnim spotAnim : actor.getSpotAnims())
		{
			if (spotAnim.getId() == graphicId)
			{
				return spotAnim.getModel();
			}
		}
		return null;
	}

	/**
	 * A usable game name, or null: strips color tags and rejects the
	 * cache's "null" placeholder.
	 */
	@Nullable
	private static String cleanTargetName(@Nullable String name)
	{
		if (name == null)
		{
			return null;
		}
		String clean = net.runelite.client.util.Text.removeTags(name).trim();
		return clean.isEmpty() || clean.equals("null") ? null : clean;
	}

	/**
	 * Push an object, NPC or graphic snapshot to the viewer. Client thread.
	 */
	private void pushNonPlayerSnapshot(ModelSnapshot snapshot, @Nullable String targetName)
	{
		pushViewerSnapshot(snapshot, targetName, true);
	}

	private void pushViewerSnapshot(ModelSnapshot snapshot, @Nullable String targetName, boolean setName)
	{
		List<int[]> recent = recentProjectileList();
		List<ModelViewerFrame.ObjectSighting> sightings = nearbySightings();
		List<ModelViewerFrame.ObjectSighting> npcs = npcSightings();
		List<ModelViewerFrame.GraphicSighting> recentGfx = recentGraphicList();
		SwingUtilities.invokeLater(() ->
		{
			viewerSnapshot = snapshot;
			if (setName)
			{
				viewerTargetName = targetName;
			}
			if (viewerFrame != null)
			{
				viewerFrame.setSnapshot(snapshot,
					selectedGlobals(snapshot, viewerFrame.getSelectedProfileKey()),
					profileEntriesBySignature(), List.of(),
					projectileProfileEntries(), recent,
					objectProfileEntries(), sightings,
					npcProfileEntries(), npcs,
					graphicProfileEntries(), recentGfx,
					weatherProfileEntries());
			}
		});
	}

	@Override
	public void vertexToggled(@Nullable String profileKey, int globalVertex)
	{
		ModelSnapshot snapshot = viewerSnapshot;
		if (snapshot == null)
		{
			return;
		}
		ModelSnapshot.Piece piece = snapshot.pieceContaining(globalVertex);
		if (piece == null)
		{
			return;
		}

		// If the selected profile's piece isn't on this model at all (stale
		// signature or authored on other gear), clicking a vertex re-attaches
		// the profile to the clicked piece, keeping its style and filter.
		// Confirmed, because doing this accidentally hijacks the profile.
		if (profileKey != null)
		{
			EmitterProfile selected = store.snapshotAll().get(profileKey);
			if (selected != null && !selected.isProjectileTarget()
				&& !signaturePresent(snapshot, selected.getSignature()))
			{
				if (javax.swing.JOptionPane.showConfirmDialog(viewerFrame,
					"Re-attach profile '" + selected.getName() + "' to this piece? Its vertices will be re-picked.",
					"Re-attach profile", javax.swing.JOptionPane.YES_NO_OPTION)
					!= javax.swing.JOptionPane.YES_OPTION)
				{
					return;
				}
				store.rebind(profileKey, piece.getSignature());
			}
		}

		int local = piece.localIndexOf(globalVertex);
		if (local < 0)
		{
			return;
		}

		String target = targetProfileKey(profileKey, piece);
		store.toggleVertex(target, local);
		refreshViewerRows(target);
		refreshViewerMarkers();
	}

	private static boolean signaturePresent(ModelSnapshot snapshot, String signature)
	{
		for (ModelSnapshot.Piece piece : snapshot.getPieces())
		{
			if (piece.getSignature().equals(signature))
			{
				return true;
			}
		}
		return false;
	}

	@Override
	public void boxSelected(@Nullable String profileKey, Set<Integer> globalVertices, boolean add)
	{
		ModelSnapshot snapshot = viewerSnapshot;
		if (snapshot == null)
		{
			return;
		}

		Map<String, List<Integer>> localsByTarget = new HashMap<>();
		for (int globalVertex : globalVertices)
		{
			ModelSnapshot.Piece piece = snapshot.pieceContaining(globalVertex);
			if (piece == null)
			{
				continue;
			}
			int local = piece.localIndexOf(globalVertex);
			if (local < 0)
			{
				continue;
			}
			String target = add
				? targetProfileKey(profileKey, piece)
				: existingProfileKey(profileKey, piece);
			if (target != null)
			{
				localsByTarget.computeIfAbsent(target, k -> new ArrayList<>()).add(local);
			}
		}

		for (Map.Entry<String, List<Integer>> entry : localsByTarget.entrySet())
		{
			if (add)
			{
				store.addVertices(entry.getKey(), entry.getValue());
			}
			else
			{
				store.removeVertices(entry.getKey(), entry.getValue());
			}
		}
		refreshViewerRows(localsByTarget.size() == 1
			? localsByTarget.keySet().iterator().next()
			: viewerFrame != null ? viewerFrame.getSelectedProfileKey() : null);
		refreshViewerMarkers();
	}

	@Override
	public void facesSelected(@Nullable String profileKey, Set<Integer> globalFaces, boolean add)
	{
		ModelSnapshot snapshot = viewerSnapshot;
		if (snapshot == null)
		{
			return;
		}

		Map<String, List<Integer>> localsByTarget = new HashMap<>();
		for (int globalFace : globalFaces)
		{
			ModelSnapshot.Piece piece = snapshot.pieceContainingFace(globalFace);
			if (piece == null)
			{
				continue;
			}
			int local = piece.localFaceIndexOf(globalFace);
			if (local < 0)
			{
				continue;
			}
			String target = add
				? targetProfileKey(profileKey, piece)
				: existingProfileKey(profileKey, piece);
			if (target != null)
			{
				localsByTarget.computeIfAbsent(target, k -> new ArrayList<>()).add(local);
			}
		}

		for (Map.Entry<String, List<Integer>> entry : localsByTarget.entrySet())
		{
			if (add)
			{
				store.addFaces(entry.getKey(), entry.getValue());
			}
			else
			{
				store.removeFaces(entry.getKey(), entry.getValue());
			}
		}
		refreshViewerRows(localsByTarget.size() == 1
			? localsByTarget.keySet().iterator().next()
			: viewerFrame != null ? viewerFrame.getSelectedProfileKey() : null);
		refreshViewerMarkers();
	}

	@Override
	public void selectionChanged()
	{
		refreshViewerMarkers();
	}

	@Override
	public boolean canPickVertices()
	{
		if (viewerFrame != null && viewerFrame.isWeatherMode())
		{
			return false;
		}
		if (viewerSnapshot == null)
		{
			return false;
		}
		if (viewerObjectId >= 0 || viewerNpcId >= 0 || viewerGraphicId >= 0)
		{
			return true;
		}
		return viewerFrame != null && viewerFrame.inModelMode();
	}

	@Override
	@Nullable
	public EmitterProfile profile(String profileKey)
	{
		return store.snapshotAll().get(profileKey);
	}

	@Override
	public void saveProfile(String profileKey, EmitterProfile profile)
	{
		store.updateEmitter(profileKey, profile);
	}

	@Override
	public void saveDefinition(String definitionId, ParticleDefinition definition)
	{
		store.updateDefinition(definitionId, definition);
	}

	@Override
	@Nullable
	public ParticleDefinition definition(String definitionId)
	{
		return store.definition(definitionId);
	}

	@Override
	public List<String> particleTextureFiles()
	{
		TreeSet<String> names = new TreeSet<>(ParticleTextureLoader.listAvailableTextures());
		for (EmitterProfile profile : store.snapshotAll().values())
		{
			String tex = profile.getTextureFile();
			if (tex != null && !tex.isEmpty())
			{
				names.add(tex);
			}
		}
		return new ArrayList<>(names);
	}

	@Override
	public List<String> particleDefinitionIds()
	{
		return store.definitionIds();
	}

	@Override
	public void setProfileDefinition(String profileKey, String definitionId)
	{
		store.setDefinitionId(profileKey, definitionId);
	}

	@Override
	@Nullable
	public String duplicateProfile(String profileKey)
	{
		return store.duplicate(profileKey);
	}

	@Override
	public void deleteProfile(String profileKey)
	{
		store.delete(profileKey);
		refreshViewerRows(null);
		refreshViewerMarkers();
	}

	@Override
	public String createProjectileProfile(int projectileId)
	{
		return store.ensureProjectileProfile(projectileId, "proj " + projectileId);
	}

	// ====================================================================

	/**
	 * The profile a click should apply to: the viewer's selected profile when
	 * it targets this piece, otherwise the piece's default profile (created
	 * if missing).
	 */
	private String targetProfileKey(@Nullable String profileKey, ModelSnapshot.Piece piece)
	{
		String existing = existingProfileKey(profileKey, piece);
		if (existing != null)
		{
			return existing;
		}
		String defaultName = piece.getVertices().length + "v " + piece.getFaces().length + "f";
		// Prefer the game name captured with the snapshot; fall back to the
		// old id/verts/faces naming when the cache offers none
		if (viewerObjectId >= 0)
		{
			return store.ensureObjectPieceProfile(piece.getSignature(),
				viewerTargetName != null ? viewerTargetName : "obj " + viewerObjectId + " " + defaultName,
				viewerObjectId);
		}
		if (viewerNpcId >= 0)
		{
			return store.ensureNpcPieceProfile(piece.getSignature(),
				viewerTargetName != null ? viewerTargetName : "npc " + viewerNpcId + " " + defaultName,
				viewerNpcId);
		}
		if (viewerGraphicId >= 0)
		{
			return store.ensureGraphicPieceProfile(piece.getSignature(),
				"gfx " + viewerGraphicId + " " + defaultName, viewerGraphicId);
		}
		return store.ensureProfileFor(piece.getSignature(), defaultName);
	}

	@Nullable
	private String existingProfileKey(@Nullable String profileKey, ModelSnapshot.Piece piece)
	{
		if (profileKey != null)
		{
			EmitterProfile selected = store.snapshotAll().get(profileKey);
			if (selected != null && piece.getSignature().equals(selected.getSignature())
				&& matchesViewerContext(selected))
			{
				return profileKey;
			}
		}
		for (Map.Entry<String, EmitterProfile> entry : store.snapshotAll().entrySet())
		{
			if (piece.getSignature().equals(entry.getValue().getSignature())
				&& matchesViewerContext(entry.getValue()))
			{
				return entry.getKey();
			}
		}
		return null;
	}

	/**
	 * Does this profile belong to what the viewer is showing - the loaded
	 * object, or the player? Signatures alone could collide across the two
	 * families. EDT only.
	 */
	private boolean matchesViewerContext(EmitterProfile profile)
	{
		if (viewerObjectId >= 0)
		{
			return profile.isObjectTarget() && profile.getObjectId() == viewerObjectId;
		}
		if (viewerNpcId >= 0)
		{
			return profile.isNpcTarget() && profile.getNpcId() == viewerNpcId;
		}
		if (viewerGraphicId >= 0)
		{
			return profile.isGraphicTarget() && profile.getGraphicId() == viewerGraphicId;
		}
		return EmitterProfile.TARGET_PLAYER.equals(profile.getTargetType());
	}

	/**
	 * Rebuild the viewer's row list in place (a toggle may have created a
	 * profile), selecting the given profile's row. EDT only.
	 */
	private void refreshViewerRows(@Nullable String selectProfileKey)
	{
		if (viewerFrame == null)
		{
			return;
		}
		if (selectProfileKey != null)
		{
			viewerFrame.selectProfileOnNextSnapshot(selectProfileKey);
		}
		viewerFrame.refreshRows(profileEntriesBySignature(), projectileProfileEntries(),
			objectProfileEntries(), npcProfileEntries(), graphicProfileEntries(),
			weatherProfileEntries());
	}

	private void refreshViewerMarkers()
	{
		ModelSnapshot snapshot = viewerSnapshot;
		if (viewerFrame == null || snapshot == null)
		{
			return;
		}
		viewerFrame.setSelectedVertices(selectedGlobals(snapshot, viewerFrame.getSelectedProfileKey()));
		viewerFrame.setSelectedFaces(selectedFaceGlobals(snapshot, viewerFrame.getSelectedProfileKey()));
		viewerFrame.refreshStyleEditor();
	}

	/**
	 * Global indices of stored emitters on the snapshot's pieces: the selected
	 * profile's when one is selected, else all profiles'. EDT only.
	 */
	private Set<Integer> selectedGlobals(ModelSnapshot snapshot, @Nullable String profileKey)
	{
		Map<String, EmitterProfile> profiles = store.snapshotAll();
		Set<Integer> out = new HashSet<>();
		for (ModelSnapshot.Piece piece : snapshot.getPieces())
		{
			for (Map.Entry<String, EmitterProfile> entry : profiles.entrySet())
			{
				// Context matters: trivial fragments (a 5v4f flame) share a
				// signature across unrelated models, so another object's
				// profile must not highlight on this snapshot
				if (!piece.getSignature().equals(entry.getValue().getSignature())
					|| !matchesViewerContext(entry.getValue())
					|| (profileKey != null && !profileKey.equals(entry.getKey())))
				{
					continue;
				}
				for (int local : entry.getValue().getVertices())
				{
					if (local >= 0 && local < piece.getVertices().length)
					{
						out.add(piece.getVertices()[local]);
					}
				}
			}
		}
		return out;
	}

	/**
	 * Global face indices of stored triangle emitters on the snapshot.
	 */
	private Set<Integer> selectedFaceGlobals(ModelSnapshot snapshot, @Nullable String profileKey)
	{
		Map<String, EmitterProfile> profiles = store.snapshotAll();
		Set<Integer> out = new HashSet<>();
		for (ModelSnapshot.Piece piece : snapshot.getPieces())
		{
			for (Map.Entry<String, EmitterProfile> entry : profiles.entrySet())
			{
				if (!piece.getSignature().equals(entry.getValue().getSignature())
					|| !matchesViewerContext(entry.getValue())
					|| (profileKey != null && !profileKey.equals(entry.getKey())))
				{
					continue;
				}
				int[] pieceFaces = piece.getFaces();
				for (int local : entry.getValue().getFaces())
				{
					if (local >= 0 && local < pieceFaces.length)
					{
						out.add(pieceFaces[local]);
					}
				}
			}
		}
		return out;
	}

	/**
	 * Recently seen projectiles as [id, count, secondsAgo], newest first.
	 * Client thread.
	 */
	private List<int[]> recentProjectileList()
	{
		long nowMs = System.currentTimeMillis();
		List<int[]> out = new ArrayList<>(recentProjectiles.size());
		for (Map.Entry<Integer, long[]> entry : recentProjectiles.entrySet())
		{
			out.add(new int[]{
				entry.getKey(),
				(int) entry.getValue()[0],
				(int) ((nowMs - entry.getValue()[1]) / 1000)});
		}
		out.sort((a, b) -> Integer.compare(a[2], b[2]));
		return out;
	}

	private List<ModelViewerFrame.ProfileEntry> projectileProfileEntries()
	{
		List<ModelViewerFrame.ProfileEntry> out = new ArrayList<>();
		for (Map.Entry<String, EmitterProfile> entry : store.snapshotAll().entrySet())
		{
			EmitterProfile profile = entry.getValue();
			if (profile.isProjectileTarget())
			{
				out.add(new ModelViewerFrame.ProfileEntry(entry.getKey(),
					ParticleIds.emitterListName(entry.getKey(), profile) + " [proj " + profile.getProjectileId() + "]",
					!profile.getItemIds().isEmpty()));
			}
		}
		return out;
	}

	private Map<String, List<ModelViewerFrame.ProfileEntry>> profileEntriesBySignature()
	{
		Map<String, List<ModelViewerFrame.ProfileEntry>> out = new HashMap<>();
		for (Map.Entry<String, EmitterProfile> entry : store.snapshotAll().entrySet())
		{
			EmitterProfile profile = entry.getValue();
			// Only the family the viewer is showing; object and player
			// profiles both carry signatures
			if (profile.getSignature() == null || !matchesViewerContext(profile))
			{
				continue;
			}
			out.computeIfAbsent(profile.getSignature(), k -> new ArrayList<>())
				.add(new ModelViewerFrame.ProfileEntry(entry.getKey(), ParticleIds.emitterListName(entry.getKey(), profile),
					!profile.getItemIds().isEmpty()));
		}
		return out;
	}

	private List<ModelViewerFrame.ProfileEntry> objectProfileEntries()
	{
		List<ModelViewerFrame.ProfileEntry> out = new ArrayList<>();
		for (Map.Entry<String, EmitterProfile> entry : store.snapshotAll().entrySet())
		{
			EmitterProfile profile = entry.getValue();
			if (profile.isObjectTarget())
			{
				out.add(new ModelViewerFrame.ProfileEntry(entry.getKey(),
					ParticleIds.emitterListName(entry.getKey(), profile) + " [obj " + profile.getObjectId() + "]", false));
			}
		}
		return out;
	}

	private List<ModelViewerFrame.ProfileEntry> npcProfileEntries()
	{
		List<ModelViewerFrame.ProfileEntry> out = new ArrayList<>();
		for (Map.Entry<String, EmitterProfile> entry : store.snapshotAll().entrySet())
		{
			EmitterProfile profile = entry.getValue();
			if (profile.isNpcTarget())
			{
				out.add(new ModelViewerFrame.ProfileEntry(entry.getKey(),
					ParticleIds.emitterListName(entry.getKey(), profile) + " [npc " + profile.getNpcId() + "]", false));
			}
		}
		return out;
	}

	private List<ModelViewerFrame.ProfileEntry> graphicProfileEntries()
	{
		List<ModelViewerFrame.ProfileEntry> out = new ArrayList<>();
		for (Map.Entry<String, EmitterProfile> entry : store.snapshotAll().entrySet())
		{
			EmitterProfile profile = entry.getValue();
			if (profile.isGraphicTarget())
			{
				out.add(new ModelViewerFrame.ProfileEntry(entry.getKey(),
					ParticleIds.emitterListName(entry.getKey(), profile) + " [gfx " + profile.getGraphicId() + "]", false));
			}
		}
		return out;
	}

	private List<ModelViewerFrame.ProfileEntry> weatherProfileEntries()
	{
		List<ModelViewerFrame.ProfileEntry> out = new ArrayList<>();
		for (Map.Entry<String, EmitterProfile> entry : store.snapshotAll().entrySet())
		{
			EmitterProfile profile = entry.getValue();
			if (!profile.isWeatherTarget())
			{
				continue;
			}
			String areas = profile.getWeatherAreas() == null ? "" : String.join(", ", profile.getWeatherAreas());
			String label = ParticleIds.emitterListName(entry.getKey(), profile)
				+ " [" + areas + ", " + profile.getWeatherParticlesPerTile() + "/tile]";
			out.add(new ModelViewerFrame.ProfileEntry(entry.getKey(), label, false));
		}
		return out;
	}

	/**
	 * Open the vertex picker with this profile selected, if its piece is on
	 * the current model. EDT only.
	 */
	private void editProfile(String profileKey)
	{
		// Object and NPC profiles edit against their own model, not the player's
		EmitterProfile profile = store.snapshotAll().get(profileKey);
		if (profile != null && profile.isObjectTarget())
		{
			viewerObjectId = profile.getObjectId();
			viewerNpcId = -1;
			viewerGraphicId = -1;
		}
		else if (profile != null && profile.isNpcTarget())
		{
			viewerNpcId = profile.getNpcId();
			viewerObjectId = -1;
			viewerGraphicId = -1;
		}
		else if (profile != null && profile.isGraphicTarget())
		{
			viewerGraphicId = profile.getGraphicId();
			viewerObjectId = -1;
			viewerNpcId = -1;
		}
		else if (profile != null && !profile.isProjectileTarget())
		{
			viewerObjectId = -1;
			viewerNpcId = -1;
			viewerGraphicId = -1;
		}
		openViewer();
		if (viewerFrame != null)
		{
			viewerFrame.selectProfileOnNextSnapshot(profileKey);
		}
	}

	private void renameProfile(String profileKey)
	{
		EmitterProfile profile = store.snapshotAll().get(profileKey);
		if (profile == null)
		{
			return;
		}
		String name = javax.swing.JOptionPane.showInputDialog(viewerFrame,
			"Nickname for this profile:", profile.getName());
		if (name != null)
		{
			store.rename(profileKey, name);
		}
	}

	private void renameFolder(String folderId)
	{
		ProfileFolder folder = store.snapshot().folders.get(folderId);
		if (folder == null)
		{
			return;
		}
		String name = javax.swing.JOptionPane.showInputDialog(viewerFrame,
			"Name for this folder:", folder.getName());
		if (name != null)
		{
			store.renameFolder(folderId, name);
		}
	}

	@Override
	public void exportBundle()
	{
		java.awt.Component parent = viewerFrame;
		javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
		chooser.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
		chooser.setDialogTitle("Export to resources folder (writes emitters.json + definitions.json + folders.json)");
		String last = configManager.getConfiguration(HdPluginConfig.CONFIG_GROUP, "exportDir");
		if (last != null && !last.isEmpty())
		{
			chooser.setCurrentDirectory(new java.io.File(last));
		}
		if (chooser.showDialog(parent, "Export") != javax.swing.JFileChooser.APPROVE_OPTION)
		{
			return;
		}
		java.io.File dir = chooser.getSelectedFile();
		configManager.setConfiguration(HdPluginConfig.CONFIG_GROUP, "exportDir", dir.getAbsolutePath());
		try
		{
			String summary = store.exportBundle(dir);
			javax.swing.JOptionPane.showMessageDialog(parent, summary, "Particles export",
				javax.swing.JOptionPane.INFORMATION_MESSAGE);
		}
		catch (Exception e)
		{
			log.warn("Preset export failed", e);
			javax.swing.JOptionPane.showMessageDialog(parent, "Export failed: " + e.getMessage(),
				"Particles export", javax.swing.JOptionPane.ERROR_MESSAGE);
		}
	}

	private void refreshViewer()
	{
		EmitterStore.Snapshot snap = store.snapshot();
		SwingUtilities.invokeLater(() ->
		{
			if (viewerFrame != null)
			{
				viewerFrame.refreshDefinitions(snap.definitions);
			}
		});
	}

	private static BufferedImage createIcon()
	{
		BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(255, 171, 82));
		g.fillOval(2, 10, 5, 5);
		g.fillOval(9, 8, 4, 4);
		g.setColor(new Color(255, 152, 31, 170));
		g.fillOval(6, 4, 3, 3);
		g.fillOval(11, 1, 3, 3);
		g.dispose();
		return image;
	}
}
