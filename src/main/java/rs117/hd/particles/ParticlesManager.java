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
import rs117.hd.particles.effector.ActiveEffectorState;
import rs117.hd.particles.effector.EffectorDefinition;
import rs117.hd.particles.effector.EffectorDefinitionManager;
import rs117.hd.particles.effector.EffectorEffect;
import rs117.hd.particles.effector.EffectorPlacement;
import rs117.hd.particles.effector.PushEffect;
import rs117.hd.particles.effector.RadialEffect;
import rs117.hd.particles.effector.WhirlpoolEffect;
import rs117.hd.particles.effector.WindEffect;
import rs117.hd.particles.debug.EffectorDebugOverlay;
import rs117.hd.particles.debug.ParticleDebugOverlay;

@Slf4j
@Singleton
public class ParticlesManager implements ModelViewerFrame.Callbacks
{

	static final int MAX_DRAWN_PARTICLES = 2048;

	private static class ActiveEmitter
	{
		final ParticleStyle style;
		final ParticleStyleSet styleSet;
		final int[] vertices;

		final int[] faceCorners;

		final int[][] chains;

		final int[] sampleChainOf;
		final int[] samplePosOf;

		final int extraAnchors;
		double carry;
		int anchorStart;
		int anchorCount;

		boolean featherReady;

		float cx;
		float cy;
		float cz;

		float[] faceWorldX = EMPTY_FLOATS;
		float[] faceWorldY = EMPTY_FLOATS;
		float[] faceWorldZ = EMPTY_FLOATS;

		ActiveEmitter(ParticleStyle style, int[] vertices, int[][] chains)
		{
			this(ParticleStyleSet.of(style), vertices, EMPTY_INTS, chains);
		}

		ActiveEmitter(ParticleStyle style, int[] vertices, int[] faceCorners, int[][] chains)
		{
			this(ParticleStyleSet.of(style), vertices, faceCorners, chains);
		}

		ActiveEmitter(ParticleStyleSet styleSet, int[] vertices, int[] faceCorners, int[][] chains)
		{
			this.styleSet = styleSet;
			this.style = styleSet.primary();
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
	private EffectorDefinitionManager effectorDefinitions;

	@Inject
	private WintertodtStormController wintertodtStormController;

	@Inject
	private ParticleDebugOverlay particleDebugOverlay;

	@Inject
	private EffectorDebugOverlay effectorDebugOverlay;

	@Inject
	private FrameTimer frameTimer;

	private boolean particleOverlayActive;
	private boolean effectorOverlayActive;

	private static final boolean PREVIEW_PLAYER_VIEW = false;

	@Inject
	@Named("developerMode")
	private boolean developerMode;

	private final Random random = new Random();

	@Getter
	private final ParticleSystem particleSystem = new ParticleSystem();

	public List<Particle> liveParticles()
	{
		return particleSystem.getParticles();
	}

	private ParticleRenderer renderer;
	private EmitterStore store;
	private ModelViewerFrame viewerFrame;

	private static class PlayerEmitters
	{
		final List<ActiveEmitter> emitters = new ArrayList<>();
		int[] equipmentIds;
		int revision = -1;
		float[] anchorXs = new float[0];
		float[] anchorYs = new float[0];

		float[] anchorZs = new float[0];

		float[] prevXs = new float[0];
		float[] prevYs = new float[0];
		float[] prevZs = new float[0];

		float[] trailCarry = new float[0];

		@Nullable
		Map<Integer, double[]> spotAnimCarries;
		int anchorCount;
		int prevCount;
		boolean rebuilt;
		int stamp;
	}

	private final Map<Player, PlayerEmitters> playerEmitters = new HashMap<>();
	private int playerStamp;

	private final Map<Long, Player> tileOwners = new HashMap<>();

	private final Set<Long> npcClaimedTiles = new HashSet<>();

	private final Map<Long, NPC> npcTileOwners = new HashMap<>();

	private long localClaimKey = Long.MIN_VALUE;
	private long targetClaimKey = Long.MIN_VALUE;
	@Nullable
	private Player localClaimTarget;

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

	private final Map<TileObject, ObjectEmitters> objectEmitters = new HashMap<>();

	private Set<Integer> profiledObjectIds = Set.of();
	private int profiledIdsRevision = -1;

	private final Map<NPC, PlayerEmitters> npcEmitters = new HashMap<>();
	private Set<Integer> profiledNpcIds = Set.of();

	private static class GraphicEmitter
	{
		final ParticleStyle style;
		final ParticleStyleSet styleSet;
		@Nullable
		final String signature;
		final int[] locals;
		final int[] faceLocals;

		final List<ActiveEmitter> resolved = new ArrayList<>();
		boolean resolveTried;

		GraphicEmitter(ParticleStyleSet styleSet, @Nullable String signature, int[] locals, int[] faceLocals)
		{
			this.styleSet = styleSet;
			this.style = styleSet.primary();
			this.signature = signature;
			this.locals = locals;
			this.faceLocals = faceLocals == null ? EMPTY_INTS : faceLocals;
		}
	}

	private final Map<Integer, List<GraphicEmitter>> graphicEmitters = new HashMap<>();

	private final Map<GraphicsObject, double[]> graphicCarries = new HashMap<>();
	private final Set<GraphicsObject> liveGraphics = new HashSet<>();

	private final Map<Integer, long[]> recentGraphics = new LinkedHashMap<>();

	private final Map<Integer, String> recentGraphicSource = new HashMap<>();

	private int pendingGraphicCapture = -1;

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

	@Getter
	private final List<float[]> featherDebugPaths = new ArrayList<>();

	private static final float MAX_TRAIL_SEGMENT = 256f;

	private ModelSnapshot viewerSnapshot;

	private int viewerObjectId = -1;
	private int viewerNpcId = -1;
	private int viewerGraphicId = -1;

	@Nullable
	private String viewerTargetName;

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

	private volatile Set<String> presentSignatures = Set.of();

	private static class ActiveProjectileProfile
	{
		final int projectileId;
		final ParticleStyle style;
		final ParticleStyleSet styleSet;

		ActiveProjectileProfile(int projectileId, ParticleStyleSet styleSet)
		{
			this.projectileId = projectileId;
			this.styleSet = styleSet;
			this.style = styleSet.primary();
		}
	}

	private static class ProjectileTracker
	{
		float prevX, prevY, prevZ;
		boolean prevValid;
		int stamp;
		final Map<ParticleStyle, double[]> carries = new HashMap<>();
	}

	private final List<ActiveProjectileProfile> activeProjectileProfiles = new ArrayList<>();
	private final Map<Projectile, ProjectileTracker> projectileTrackers = new HashMap<>();
	private int projectileStamp;

	private static class ActiveWeatherZone
	{
		final AABB aabb;
		final ParticleStyle style;
		final ParticleStyleSet styleSet;
		final float particlesPerTile;
		final float densityScale;
		final List<String> globalEffectors;
		final List<String> localEffectorFilter;
		final List<String> embeddedEffectors;
		double spawnAccum;

		ActiveWeatherZone(AABB aabb, ParticleStyleSet styleSet, float particlesPerTile, float densityScale,
			List<String> globalEffectors, List<String> localEffectorFilter, List<String> embeddedEffectors)
		{
			this.aabb = aabb;
			this.styleSet = styleSet;
			this.style = styleSet.primary();
			this.particlesPerTile = particlesPerTile;
			this.densityScale = densityScale;
			this.globalEffectors = globalEffectors;
			this.localEffectorFilter = localEffectorFilter;
			this.embeddedEffectors = embeddedEffectors;
		}
	}

	private final List<ActiveWeatherZone> activeWeatherZones = new ArrayList<>();
	private final Map<String, List<ActiveEffectorState>> activeEffectorsById = new HashMap<>();

	private final LinkedHashMap<Integer, long[]> recentProjectiles = new LinkedHashMap<>();
	private int stylesRevision = -1;

	private final java.util.function.Consumer<Particle> deathStats = this::onParticleDeath;
	private static final java.util.function.Consumer<Particle> DISCARD = p ->
	{
	};

	private long lastNanos;
	private int lastLevel = -1;

	private long statsWindowStart;
	private int windowSpawns;
	private int windowDeaths;
	private float windowDeathAgeSum;

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

	@Getter
	private String oracleLine = "";
	private String lastOracleDump = "";

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
		effectorDefinitions.startup(() ->
		{
			rebuildWeatherZones();
			SwingUtilities.invokeLater(() ->
			{
				if (viewerFrame != null)
				{
					viewerFrame.refreshEffectors();
				}
			});
		});
		effectorDefinitions.loadConfig();
		wintertodtStormController.startUp();

		log.debug("Particles started");
	}

	public void shutDown()
	{
		eventBus.unregister(this);
		wintertodtStormController.shutDown();
		effectorDefinitions.shutdown();
		particleDebugOverlay.setActive(false);
		effectorDebugOverlay.setActive(false);
		particleOverlayActive = false;
		effectorOverlayActive = false;
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

	private void noteGraphic(int id, String source)
	{
		if (!developerMode)
		{

			return;
		}
		long[] seen = recentGraphics.get(id);
		if (seen != null)
		{
			seen[0]++;
			seen[1] = System.currentTimeMillis();
			if (source != null && !"tile".equals(source))
			{

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

				particleSystem.clear(DISCARD);
				renderer.reset();
				objectEmitters.clear();
				npcEmitters.clear();
				graphicCarries.clear();
				break;
			case LOGGED_IN:

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

		dt = Math.min(dt, 0.1f);

		int storeRevision = store.getRevision();
		if (stylesRevision != storeRevision && renderer.rebuildStyles(store.snapshotAll(), store.snapshotDefinitions()))
		{
			stylesRevision = storeRevision;

			invalidateResolutions();
			rebuildWeatherZones();
		}

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

		int level = localPlayer.getWorldView().getPlane();
		if (level != lastLevel)
		{
			particleSystem.clear(DISCARD);

			for (PlayerEmitters pe : playerEmitters.values())
			{
				pe.prevCount = 0;
				Arrays.fill(pe.trailCarry, 0f);
			}
			lastLevel = level;
		}
		anchorWorldView = localPlayer.getLocalLocation().getWorldView();
		anchorLevel = level;

		boolean markers = false;
		anchorCount = 0;
		featherDebugPaths.clear();

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
			SceneContext ctx = hdPlugin.getSceneContext();
			Map<String, List<ActiveEffectorState>> effectors = ctx != null && ctx.sceneBase != null
				? buildActiveEffectorsById(ctx)
				: Map.of();
			particleSystem.update(dt, effectors, effectorDefinitions, deathStats);
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

	private void updateStackOracle(int level)
	{
		oracleLine = "";
		if (!developerMode)
		{
			return;
		}

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

		String predText = (pred == null ? predWhy : pred.getName() + "[" + predWhy + "]")
			+ (!isCentered(winner) ? " n/a-moving"
			: pred == winner ? " MATCH"
			: pred == localPlayer ? " oracle-blind-self"
			: " MISS");

		boolean idxOk = true;
		for (Player p : stack)
		{
			Integer menuIdx = menuIndices.get(p);
			if (menuIdx != null && menuIdx != p.getId())
			{
				idxOk = false;
			}
		}

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

	private void updateNpcStackOracle(int level)
	{
		npcOracleLine = "";
		if (!developerMode)
		{
			return;
		}

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

	private void updateAnchors(PlayerEmitters pe, Actor player, boolean markers)
	{

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

		int orientation = player.getCurrentOrientation();
		int sin = Perspective.SINE[orientation];
		int cos = Perspective.COSINE[orientation];
		LocalPoint lp = player.getLocalLocation();

		int playerBaseZ = Perspective.getFootprintTileHeight(client, lp, anchorLevel, player.getFootprintSize())
			- player.getAnimationHeightOffset();

		for (ActiveEmitter emitter : pe.emitters)
		{
			emitter.anchorStart = pe.anchorCount;
			emitter.anchorCount = 0;

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

	private void resolvePlayer(PlayerEmitters pe, Player player)
	{
		PlayerComposition composition = player.getPlayerComposition();
		if (composition == null)
		{
			pe.emitters.clear();
			pe.equipmentIds = null;
			return;
		}

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
					|| !ParticlesPanel.effectiveEnabled(profile)
					|| (profile.getVertices().isEmpty() && profile.getFaces().isEmpty())
					|| (!developerMode && ParticlesPanel.effectiveWip(profile)))
				{
					continue;
				}

				if (!profile.getItemIds().isEmpty() && Collections.disjoint(profile.getItemIds(), wornItemIds))
				{
					continue;
				}
				ParticleStyleSet styleSet = renderer.getStyleSet(entry.getKey());
				if (styleSet == null)
				{
					continue;
				}

				ActiveEmitter emitter = resolveMeshEmitter(styleSet, snapshot, piece, profile);
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

		activeProjectileProfiles.clear();
		for (Map.Entry<String, EmitterProfile> entry : profiles.entrySet())
		{
			EmitterProfile profile = entry.getValue();
			if (!profile.isProjectileTarget() || !ParticlesPanel.effectiveEnabled(profile)
				|| profile.getProjectileId() < 0
				|| (!developerMode && ParticlesPanel.effectiveWip(profile)))
			{
				continue;
			}
			if (!profile.getItemIds().isEmpty() && Collections.disjoint(profile.getItemIds(), wornItemIds))
			{
				continue;
			}
			ParticleStyleSet styleSet = renderer.getStyleSet(entry.getKey());
			if (styleSet != null)
			{
				activeProjectileProfiles.add(new ActiveProjectileProfile(profile.getProjectileId(), styleSet));
			}
		}
		projectileTrackers.clear();

		if (!present.equals(presentSignatures))
		{
			presentSignatures = present;
			refreshViewer();
		}
	}

	@Nullable
	private ActiveEmitter resolveMeshEmitter(ParticleStyleSet styleSet, ModelSnapshot snapshot,
		ModelSnapshot.Piece piece, EmitterProfile profile)
	{
		ParticleStyle style = styleSet.primary();
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
		return new ActiveEmitter(styleSet, vertices, faceCorners, chains);
	}

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

	private static int[][] buildChains(ModelSnapshot snapshot, ModelSnapshot.Piece piece, int[] emitterVertices)
	{
		Map<Integer, Integer> offsetOf = new HashMap<>();
		for (int i = 0; i < emitterVertices.length; i++)
		{
			offsetOf.put(emitterVertices[i], i);
		}

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

	private static final float CHAIN_BRIDGE_DISTANCE = 40f;

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

	private void invalidateResolutions()
	{
		for (PlayerEmitters pe : playerEmitters.values())
		{
			pe.equipmentIds = null;
		}
	}

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

		int actionAnimation = player.getAnimation();
		int actionFrame = player.getAnimationFrame();
		int poseAnimation = player.getPoseAnimation();
		boolean isLocal = player == client.getLocalPlayer();
		if (isLocal)
		{
			lastActionAnimation = actionAnimation != -1 ? actionAnimation : lastActionAnimation;
		}

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

				emitter.carry = 0;
				continue;
			}

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

					spawnParticle(pe, emitter, a, (i + random.nextFloat()) / n, lifeScale);
				}
			}
		}
	}

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
		spawnAt(emitter.styleSet.pick(random), x, y, z, emitter.cx, emitter.cy, emitter.cz, lifeScale);
	}

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

		spawnAt(emitter.styleSet.pick(random), ax, ay, az, emitter.cx, emitter.cy, emitter.cz, lifeScale);
	}

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

		spawnAt(emitter.styleSet.pick(random), x, y, z, emitter.cx, emitter.cy, emitter.cz, lifeScale);
	}

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

	private static float quadratic(float a, float b, float c, float t)
	{
		float m1 = (a + b) / 2f;
		float m2 = (b + c) / 2f;
		float inv = 1f - t;
		return inv * inv * m1 + 2f * inv * t * b + t * t * m2;
	}

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
					spawnAt(profile.styleSet.pick(random), px + (x - px) * t, py + (y - py) * t, pz + (z - pz) * t, 1f);
				}

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
						spawnAt(profile.styleSet.pick(random), px + (x - px) * t, py + (y - py) * t, pz + (z - pz) * t, 1f);
					}
				}
			}
		}

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

	private static boolean needsCentroid(ParticleStyle style)
	{
		return style.getEmitScale() != 1f || style.getVortex() != 0f;
	}

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

	private void spawnAt(ParticleStyle style, float ax, float ay, float az, float lifeScale)
	{
		spawnAt(style, ax, ay, az, ax, ay, az, lifeScale);
	}

	private void spawnAt(ParticleStyle style, float ax, float ay, float az,
		float cx, float cy, float cz, float lifeScale)
	{

		float emitScale = style.getEmitScale();
		if (emitScale != 1f)
		{
			ax = cx + (ax - cx) * emitScale;
			ay = cy + (ay - cy) * emitScale;
			az = cz + (az - cz) * emitScale;
		}

		float jitter = style.getSpawnJitter();
		double jitterAngle = random.nextFloat() * 2 * Math.PI;
		float jitterRadius = jitter * (float) Math.sqrt(random.nextFloat());
		float x = ax + (float) Math.cos(jitterAngle) * jitterRadius;
		float y = ay + (float) Math.sin(jitterAngle) * jitterRadius;
		float z = az + (random.nextFloat() - 0.5f) * jitter;

		float spread = style.getSpreadSpeed();
		float velX = (random.nextFloat() - 0.5f) * spread;
		float velY = (random.nextFloat() - 0.5f) * spread;

		float velZ = -style.getRiseSpeed() * (0.75f + random.nextFloat() * 0.5f);

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
		viewerFrame.refreshEffectors();
		refreshSnapshot();
	}

	@Override
	public void setParticleOverlayActive(boolean active)
	{
		particleOverlayActive = active;
		particleDebugOverlay.setActive(active);
	}

	@Override
	public void setEffectorOverlayActive(boolean active)
	{
		effectorOverlayActive = active;
		effectorDebugOverlay.setActive(active);
	}

	@Override
	public boolean isParticleOverlayActive()
	{
		return particleOverlayActive;
	}

	@Override
	public boolean isEffectorOverlayActive()
	{
		return effectorOverlayActive;
	}

	@Override
	public List<ModelViewerFrame.EffectorListEntry> effectorEntries()
	{
		List<ModelViewerFrame.EffectorListEntry> out = new ArrayList<>();
		for (EffectorDefinition def : effectorDefinitions.getDefinitions().values())
		{
			if (def == null || def.id == null)
			{
				continue;
			}
			StringBuilder effects = new StringBuilder();
			StringBuilder details = new StringBuilder();
			details.append(def.id).append('\n');
			if (def.radiusTiles > 0f)
			{
				details.append("Radius: ").append(def.radiusTiles).append(" tiles\n");
			}
			else
			{
				details.append("Radius: unlimited\n");
			}
			details.append("Height offset: ").append(def.heightOffset).append('\n');
			details.append("Scope: ").append(def.scope).append("\n\nEffects:\n");
			for (EffectorEffect effect : def.effects)
			{
				if (effects.length() > 0)
				{
					effects.append(", ");
				}
				String type = effect.type != null ? effect.type.name().toLowerCase() : "unknown";
				effects.append(type);
				details.append("• ").append(type);
				if (effect instanceof WindEffect)
				{
					WindEffect wind = (WindEffect) effect;
					details.append("  speed=").append(wind.speed)
						.append(" intensity=").append(wind.intensity)
						.append(" turb=").append(wind.turbulence)
						.append(" gust=").append(wind.gust)
						.append(" lift=").append(wind.lift)
						.append(" response=").append(wind.response);
				}
				else if (effect instanceof WhirlpoolEffect)
				{
					WhirlpoolEffect whirl = (WhirlpoolEffect) effect;
					details.append("  strength=").append(whirl.strength)
						.append(" sink=").append(whirl.sink);
				}
				else if (effect instanceof RadialEffect)
				{
					details.append("  strength=").append(((RadialEffect) effect).strength);
				}
				else if (effect instanceof PushEffect)
				{
					details.append("  strength=").append(((PushEffect) effect).strength);
				}
				details.append('\n');
			}
			List<String> placementLines = new ArrayList<>();
			for (EffectorPlacement placement : effectorDefinitions.getAllPlacements())
			{
				if (!def.id.equals(placement.getEffectorId()))
				{
					continue;
				}
				placementLines.add(placement.getWorldX() + ", "
					+ placement.getWorldY() + ", plane " + placement.getPlane());
			}
			details.append("\nPlacements (").append(placementLines.size()).append("):\n");
			if (placementLines.isEmpty())
			{
				details.append("(none — runtime only)\n");
			}
			else
			{
				for (String line : placementLines)
				{
					details.append("• ").append(line).append('\n');
				}
			}
			String summary = effects.length() == 0 ? "no effects" : effects.toString();
			String placements = placementLines.isEmpty()
				? "no placements"
				: placementLines.size() + " placement" + (placementLines.size() == 1 ? "" : "s");
			out.add(new ModelViewerFrame.EffectorListEntry(def.id, summary + " · " + placements,
				placements, details.toString()));
		}
		return out;
	}

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

	private void resolveObject(ObjectEmitters oe, TileObject object, int revision)
	{
		oe.revision = revision;
		oe.emitters.clear();
		EmitterStore.Snapshot snap = store.snapshot();
		Map<String, EmitterProfile> profiles = snap.profiles;
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
				|| !ParticlesPanel.effectiveEnabled(profile)
				|| (profile.getVertices().isEmpty() && profile.getFaces().isEmpty())
				|| (!developerMode && ParticlesPanel.effectiveWip(profile)))
			{
				continue;
			}
			ParticleStyleSet styleSet = renderer.getStyleSet(entry.getKey());
			if (styleSet == null)
			{
				continue;
			}

			for (ModelSnapshot.Piece piece : snapshot.getPieces())
			{
				if (!piece.matchesSignature(profile.getSignature()))
				{
					continue;
				}
				ActiveEmitter emitter = resolveMeshEmitter(styleSet, snapshot, piece, profile);
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
					spawnAt(emitter.styleSet.pick(random), oe.anchorXs[a], oe.anchorYs[a], oe.anchorZs[a],
						emitter.cx, emitter.cy, emitter.cz, 1f);
				}
			}
		}
	}

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

		if (useCentroid)
		{
			spawnAt(emitter.styleSet.pick(random), x, y, z, emitter.cx, emitter.cy, emitter.cz, 1f);
		}
		else
		{
			spawnAt(emitter.styleSet.pick(random), x, y, z, 1f);
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

	private List<ModelViewerFrame.ObjectSighting> nearbySightings()
	{
		return sceneObjectSightings();
	}

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
		for (Map.Entry<String, EmitterProfile> entry : snap.profiles.entrySet())
		{
			EmitterProfile profile = entry.getValue();
			if (!profile.isGraphicTarget() || profile.getGraphicId() < 0
				|| !ParticlesPanel.effectiveEnabled(profile)
				|| (!developerMode && ParticlesPanel.effectiveWip(profile)))
			{
				continue;
			}
			ParticleStyleSet styleSet = renderer.getStyleSet(entry.getKey());
			if (styleSet == null)
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
				.add(new GraphicEmitter(styleSet, profile.getSignature(), locals, faceLocals));
		}
	}

	private void rebuildWeatherZones()
	{
		activeWeatherZones.clear();
		EmitterStore.Snapshot snap = store.snapshot();
		for (Map.Entry<String, EmitterProfile> entry : snap.profiles.entrySet())
		{
			EmitterProfile profile = entry.getValue();
			if (!profile.isWeatherTarget()
				|| profile.getWeatherAreas() == null || profile.getWeatherAreas().isEmpty()
				|| profile.getWeatherParticlesPerTile() <= 0f
				|| !ParticlesPanel.effectiveEnabled(profile)
				|| (!developerMode && ParticlesPanel.effectiveWip(profile)))
			{
				continue;
			}
			ParticleStyleSet styleSet = renderer.getStyleSet(entry.getKey());
			if (styleSet == null)
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
						aabb, styleSet, profile.getWeatherParticlesPerTile(),
						profile.getWeatherDensityScale(),
						copyEffectorList(profile.getGlobalEffectors()),
						copyEffectorList(profile.getLocalEffectorFilter()),
						copyEffectorList(profile.getEmbeddedEffectors())));
				}
			}
		}
	}

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

			float avgLife = weatherFallDurationSec(zone.style);
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

		float ceilingZ = ground - ParticleSystem.WEATHER_SPAWN_ABOVE_GROUND;
		float hideFromCamera = hdPlugin.cameraPosition[1] - ParticleSystem.WEATHER_SPAWN_ABOVE_CAMERA;
		if (ceilingZ > hideFromCamera)
		{
			ceilingZ = hideFromCamera;
		}
		ceilingZ = Math.max(ceilingZ, ground - ParticleSystem.WEATHER_SPAWN_MAX_ABOVE_GROUND);
		ceilingZ = Math.min(ceilingZ, ground - ParticleSystem.WEATHER_SPAWN_MIN_ABOVE_GROUND);
		float floorZ = ground - ParticleSystem.WEATHER_SPAWN_MIN_ABOVE_GROUND;
		if (floorZ < ceilingZ)
		{
			float swap = floorZ;
			floorZ = ceilingZ;
			ceilingZ = swap;
		}
		float spawnZ = ceilingZ + random.nextFloat() * (floorZ - ceilingZ);
		spawnWeatherAt(zone, lx, ly, spawnZ, worldPlane);
	}

	private Map<String, List<ActiveEffectorState>> buildActiveEffectorsById(SceneContext ctx)
	{
		for (List<ActiveEffectorState> states : activeEffectorsById.values())
		{
			states.clear();
		}
		activeEffectorsById.clear();
		float halfTile = LOCAL_TILE_SIZE / 2f;
		for (EffectorPlacement placement : effectorDefinitions.getAllPlacements())
		{
			EffectorDefinition def = effectorDefinitions.getDefinition(placement.getEffectorId());
			if (def == null)
			{
				continue;
			}
			WorldPoint wp = new WorldPoint(placement.getWorldX(), placement.getWorldY(), placement.getPlane());
			int[] loc = worldToLocalFirst(ctx, wp);
			if (loc == null)
			{
				continue;
			}
			float x = loc[0] + halfTile;
			float y = loc[1] + halfTile;
			var wv = client.getTopLevelWorldView();
			LocalPoint heightLp = new LocalPoint((int) x, (int) y, wv != null ? wv.getId() : -1);
			int ground = Perspective.getTileHeight(client, heightLp, loc[2]);
			float z = ground - def.heightOffset;
			activeEffectorsById.computeIfAbsent(def.id, k -> new ArrayList<>())
				.add(new ActiveEffectorState(def.id, x, y, z, def));
		}
		return activeEffectorsById;
	}

	@Nullable
	private static int[] worldToLocalFirst(SceneContext ctx, WorldPoint worldPoint)
	{
		int[] contiguous = ctx.worldToLocal(worldPoint);
		if (contiguous != null)
		{
			return contiguous;
		}
		return ctx.worldToLocals(worldPoint).findFirst().orElse(null);
	}

	private static float desiredWeatherAlive(ActiveWeatherZone zone)
	{
		AABB aabb = zone.aabb;
		int width = aabb.maxX - aabb.minX + 1;
		int height = aabb.maxY - aabb.minY + 1;
		int radiusTiles = Math.max(1, Math.min(width, height) / 2);
		return (float) (Math.PI * radiusTiles * radiusTiles) * zone.particlesPerTile;
	}

	private static List<String> copyEffectorList(@Nullable List<String> source)
	{
		if (source == null || source.isEmpty())
		{
			return List.of();
		}
		return List.copyOf(source);
	}

	private static float weatherFallSpeed(ParticleStyle style)
	{
		if (style.getRiseSpeed() < 0)
		{
			return -style.getRiseSpeed();
		}
		return Math.max(12, style.getGravity() > 0 ? style.getGravity() : 26);
	}

	private static float weatherFallDurationSec(ParticleStyle style)
	{
		float meanHeight = 0.5f * (ParticleSystem.WEATHER_SPAWN_MIN_ABOVE_GROUND
			+ ParticleSystem.WEATHER_SPAWN_MAX_ABOVE_GROUND);
		return Math.max(1f, meanHeight / Math.max(1f, weatherFallSpeed(style)));
	}

	private void spawnWeatherAt(ActiveWeatherZone zone, float x, float y, float z, int worldPlane)
	{
		ParticleStyle style = zone.styleSet.pick(random);
		float jitter = style.getSpawnJitter();
		double jitterAngle = random.nextFloat() * 2 * Math.PI;
		float jitterRadius = jitter * (float) Math.sqrt(random.nextFloat());
		x += (float) Math.cos(jitterAngle) * jitterRadius;
		y += (float) Math.sin(jitterAngle) * jitterRadius;
		z += (random.nextFloat() - 0.5f) * jitter;

		float spread = style.getSpreadSpeed();
		float velX = (random.nextFloat() - 0.5f) * spread;
		float velY = (random.nextFloat() - 0.5f) * spread;
		float fallSpeed = weatherFallSpeed(style);
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
			p.setEffectorLists(zone.globalEffectors, zone.localEffectorFilter, zone.embeddedEffectors);
			finalizeSpawn(p, style);
			windowSpawns++;
		}
	}

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
			ge.resolved.add(new ActiveEmitter(ge.styleSet, vertices, faceCorners, chains));
		}
	}

	private static final int VISIBLE_ALPHA_MAX = 128;
	private boolean[] visibleMaskScratch = new boolean[0];
	private float[] gfxAnchorXs = new float[0];
	private float[] gfxAnchorYs = new float[0];
	private float[] gfxAnchorZs = new float[0];
	private boolean[] gfxAnchorVisible = new boolean[0];

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
				spawnAt(ge.styleSet.pick(random), ox, oy, oz, 1f);
			}
			return true;
		}
		boolean feathered = style.getFeatherStrength() > 0;
		boolean interpolated = !feathered && style.getInterpolation() > 0;
		boolean[] visible = feathered ? null : visibleVertexMask(model);
		float[] vx = model.getVerticesX();
		float[] vy = model.getVerticesY();
		float[] vz = model.getVerticesZ();

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
				spawnAt(ge.styleSet.pick(random), gfxAnchorXs[a], gfxAnchorYs[a], gfxAnchorZs[a], 1f);
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

				spawnAt(ge.styleSet.pick(random), ox + (vz[v] * sin + vx[v] * cos) / 65536f,
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
		for (Map.Entry<String, EmitterProfile> entry : snap.profiles.entrySet())
		{
			EmitterProfile profile = entry.getValue();
			if (!profile.isNpcTarget() || profile.getNpcId() != npc.getId()
				|| !ParticlesPanel.effectiveEnabled(profile)
				|| (profile.getVertices().isEmpty() && profile.getFaces().isEmpty())
				|| (!developerMode && ParticlesPanel.effectiveWip(profile)))
			{
				continue;
			}
			ParticleStyleSet styleSet = renderer.getStyleSet(entry.getKey());
			if (styleSet == null)
			{
				continue;
			}
			for (ModelSnapshot.Piece piece : snapshot.getPieces())
			{
				if (!piece.matchesSignature(profile.getSignature()))
				{
					continue;
				}
				ActiveEmitter emitter = resolveMeshEmitter(styleSet, snapshot, piece, profile);
				if (emitter != null)
				{
					pe.emitters.add(emitter);
				}
			}
		}
	}

	private void emitActorSpotAnims(float dt, PlayerEmitters pe, Actor actor)
	{
		if (graphicEmitters.isEmpty())
		{
			return;
		}
		int budget = config.particleMaxParticles();
		float densityScale = config.particleDensity().getFactor();
		LocalPoint lp = actor.getLocalLocation();

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

		startRecording(-1, -1, -1);
	}

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

			startRecording(objectId, -1, -1);
		}
	}

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

			startRecording(-1, npcId, -1);
		}
	}

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

	private void captureGraphicSnapshot(int graphicId)
	{
		Model model = findGraphicModel(graphicId);
		if (model == null)
		{

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

	private String targetProfileKey(@Nullable String profileKey, ModelSnapshot.Piece piece)
	{
		String existing = existingProfileKey(profileKey, piece);
		if (existing != null)
		{
			return existing;
		}
		String defaultName = piece.getVertices().length + "v " + piece.getFaces().length + "f";

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

	private Set<Integer> selectedGlobals(ModelSnapshot snapshot, @Nullable String profileKey)
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

	private void editProfile(String profileKey)
	{

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


	@Override
	public void exportBundle()
	{
		java.awt.Component parent = viewerFrame;
		javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
		chooser.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
		chooser.setDialogTitle("Export to resources folder (writes emitters.json + definitions.json)");
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
