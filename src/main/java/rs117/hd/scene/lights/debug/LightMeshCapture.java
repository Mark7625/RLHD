package rs117.hd.scene.lights.debug;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.DecorativeObject;
import net.runelite.api.DynamicObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.GraphicsObject;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.Renderable;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.Actor;
import net.runelite.api.ActorSpotAnim;
import net.runelite.api.coords.LocalPoint;
import rs117.hd.scene.GamevalManager;
import rs117.hd.scene.model.ModelSnapshot;

@Singleton
public class LightMeshCapture {
	public enum ObjectKind {
		GAME_OBJECT("Game object"),
		WALL("Wall"),
		DECORATIVE("Decorative"),
		GROUND("Ground");

		public final String label;

		ObjectKind(String label) {
			this.label = label;
		}

		static ObjectKind of(TileObject object) {
			if (object instanceof GameObject)
				return GAME_OBJECT;
			if (object instanceof WallObject)
				return WALL;
			if (object instanceof DecorativeObject)
				return DECORATIVE;
			return GROUND;
		}
	}

	public static final class Sighting {
		public final int id;
		public final String name;
		public final int distanceTiles;
		public final ObjectKind kind;
		public final int instanceCount;

		public Sighting(int id, String name, int distanceTiles, ObjectKind kind, int instanceCount) {
			this.id = id;
			this.name = name;
			this.distanceTiles = distanceTiles;
			this.kind = kind;
			this.instanceCount = instanceCount;
		}

		public String formatRow() {
			return name + " (" + id + ") — " + instanceCount + "×, " + distanceTiles + " tiles";
		}
	}

	public static final class CaptureResult {
		@Nullable
		public final ModelSnapshot snapshot;
		public final String targetLabel;
		public final boolean startRecording;

		public CaptureResult(@Nullable ModelSnapshot snapshot, String targetLabel, boolean startRecording) {
			this.snapshot = snapshot;
			this.targetLabel = targetLabel;
			this.startRecording = startRecording;
		}
	}

	@Inject
	private Client client;

	@Inject
	private GamevalManager gamevalManager;

	private final Map<Integer, TileObject> nearestObjectById = new HashMap<>();
	private final Map<Integer, NPC> nearestNpcById = new HashMap<>();

	public void clearSceneIndex() {
		nearestObjectById.clear();
		nearestNpcById.clear();
	}

	public CaptureResult capturePlayer() {
		Player player = client.getLocalPlayer();
		if (player == null)
			return null;
		Model model = player.getModel();
		if (model == null)
			return null;
		return new CaptureResult(ModelSnapshot.capture(model), "Player", true);
	}

	@Nullable
	public CaptureResult captureObject(int objectId) {
		TileObject best = nearestObjectById.get(objectId);
		if (best == null)
			best = findNearestObject(objectId);
		Model model = best == null ? null : objectModel(best);
		if (model == null)
			return null;
		String name = objectDisplayName(objectId);
		boolean record = objectAnimFrame(best) >= 0;
		return new CaptureResult(ModelSnapshot.capture(model), name, record);
	}

	@Nullable
	public CaptureResult captureNpc(int npcId) {
		NPC best = nearestNpcById.get(npcId);
		if (best == null)
			best = findNearestNpc(npcId);
		Model model = best == null ? null : best.getModel();
		String name = npcDisplayName(npcId);
		if (model == null) {
			ModelData cached = npcCacheModel(npcId);
			if (cached != null)
				model = cached.light();
		}
		if (model == null)
			return null;
		return new CaptureResult(ModelSnapshot.capture(model), name, best != null);
	}

	@Nullable
	public CaptureResult captureGraphic(int graphicId) {
		Model model = findGraphicModel(graphicId);
		if (model == null)
			return null;
		return new CaptureResult(ModelSnapshot.capture(model), "Graphic " + graphicId, false);
	}

	public List<Sighting> objectSightings() {
		Player player = client.getLocalPlayer();
		if (player == null) {
			nearestObjectById.clear();
			return List.of();
		}
		LocalPoint lp = player.getLocalLocation();
		int plane = player.getWorldView().getPlane();
		Map<Integer, Agg> byId = new HashMap<>();
		nearestObjectById.clear();
		for (TileObject object : sceneObjects(plane)) {
			int id = object.getId();
			Agg agg = byId.computeIfAbsent(id, k -> new Agg());
			agg.kind = ObjectKind.of(object);
			agg.count++;
			int localDist = object.getLocalLocation().distanceTo(lp);
			int distTiles = localDist / 128;
			if (distTiles < agg.nearestTiles)
				agg.nearestTiles = distTiles;
			if (localDist < agg.nearestLocalDist) {
				agg.nearestLocalDist = localDist;
				nearestObjectById.put(id, object);
			}
		}
		List<Sighting> out = new ArrayList<>();
		for (Map.Entry<Integer, Agg> entry : byId.entrySet()) {
			int id = entry.getKey();
			Agg agg = entry.getValue();
			out.add(new Sighting(id, objectDisplayName(id), agg.nearestTiles, agg.kind, agg.count));
		}
		out.sort((a, b) -> {
			int byName = a.name.compareToIgnoreCase(b.name);
			return byName != 0 ? byName : Integer.compare(a.id, b.id);
		});
		return out;
	}

	public List<Sighting> npcSightings() {
		Player player = client.getLocalPlayer();
		if (player == null) {
			nearestNpcById.clear();
			return List.of();
		}
		LocalPoint lp = player.getLocalLocation();
		int plane = player.getWorldView().getPlane();
		Map<Integer, Agg> byId = new HashMap<>();
		nearestNpcById.clear();
		for (NPC npc : client.getTopLevelWorldView().npcs()) {
			if (npc == null || npc.getWorldLocation().getPlane() != plane)
				continue;
			int id = npc.getId();
			Agg agg = byId.computeIfAbsent(id, k -> new Agg());
			agg.count++;
			int localDist = npc.getLocalLocation().distanceTo(lp);
			int distTiles = localDist / 128;
			if (distTiles < agg.nearestTiles)
				agg.nearestTiles = distTiles;
			if (localDist < agg.nearestLocalDist) {
				agg.nearestLocalDist = localDist;
				nearestNpcById.put(id, npc);
			}
		}
		List<Sighting> out = new ArrayList<>();
		for (Map.Entry<Integer, Agg> entry : byId.entrySet()) {
			int id = entry.getKey();
			Agg agg = entry.getValue();
			out.add(new Sighting(id, npcDisplayName(id), agg.nearestTiles, ObjectKind.GAME_OBJECT, agg.count));
		}
		out.sort((a, b) -> {
			int byName = a.name.compareToIgnoreCase(b.name);
			return byName != 0 ? byName : Integer.compare(a.id, b.id);
		});
		return out;
	}

	@Nullable
	private TileObject findNearestObject(int objectId) {
		Player player = client.getLocalPlayer();
		if (player == null)
			return null;
		LocalPoint lp = player.getLocalLocation();
		TileObject best = null;
		int bestDist = Integer.MAX_VALUE;
		for (TileObject object : sceneObjects(player.getWorldView().getPlane())) {
			if (object.getId() != objectId)
				continue;
			int dist = object.getLocalLocation().distanceTo(lp);
			if (dist < bestDist) {
				bestDist = dist;
				best = object;
			}
		}
		if (best != null)
			nearestObjectById.put(objectId, best);
		return best;
	}

	@Nullable
	private NPC findNearestNpc(int npcId) {
		Player player = client.getLocalPlayer();
		if (player == null)
			return null;
		LocalPoint lp = player.getLocalLocation();
		int plane = player.getWorldView().getPlane();
		NPC best = null;
		int bestDist = Integer.MAX_VALUE;
		for (NPC npc : client.getTopLevelWorldView().npcs()) {
			if (npc == null || npc.getId() != npcId || npc.getWorldLocation().getPlane() != plane)
				continue;
			int dist = npc.getLocalLocation().distanceTo(lp);
			if (dist < bestDist) {
				bestDist = dist;
				best = npc;
			}
		}
		if (best != null)
			nearestNpcById.put(npcId, best);
		return best;
	}

	@Nullable
	private Model findGraphicModel(int graphicId) {
		for (GraphicsObject graphic : client.getTopLevelWorldView().getGraphicsObjects()) {
			if (!graphic.finished() && graphic.getId() == graphicId) {
				Model model = graphic.getModel();
				if (model != null)
					return model;
			}
		}
		for (Player p : client.getTopLevelWorldView().players()) {
			Model model = p == null ? null : spotAnimModel(p, graphicId);
			if (model != null)
				return model;
		}
		for (NPC npc : client.getTopLevelWorldView().npcs()) {
			Model model = npc == null ? null : spotAnimModel(npc, graphicId);
			if (model != null)
				return model;
		}
		return null;
	}

	@Nullable
	private static Model spotAnimModel(Actor actor, int graphicId) {
		for (ActorSpotAnim spotAnim : actor.getSpotAnims()) {
			if (spotAnim.getId() == graphicId)
				return spotAnim.getModel();
		}
		return null;
	}

	@Nullable
	private ModelData npcCacheModel(int npcId) {
		NPCComposition composition = client.getNpcDefinition(npcId);
		int[] modelIds = composition == null ? null : composition.getModels();
		if (modelIds == null || modelIds.length == 0)
			return null;
		List<ModelData> parts = new ArrayList<>();
		for (int modelId : modelIds) {
			ModelData part = client.loadModelData(modelId);
			if (part != null)
				parts.add(part);
		}
		return parts.isEmpty() ? null : client.mergeModels(parts.toArray(new ModelData[0]));
	}

	private List<TileObject> sceneObjects(int plane) {
		List<TileObject> out = new ArrayList<>();
		Set<TileObject> seen = new HashSet<>();
		Tile[][] tiles = client.getTopLevelWorldView().getScene().getTiles()[plane];
		for (Tile[] column : tiles) {
			for (Tile tile : column) {
				if (tile == null)
					continue;
				GameObject[] gameObjects = tile.getGameObjects();
				if (gameObjects != null) {
					for (GameObject gameObject : gameObjects) {
						if (gameObject != null && seen.add(gameObject))
							out.add(gameObject);
					}
				}
				if (tile.getWallObject() != null && seen.add(tile.getWallObject()))
					out.add(tile.getWallObject());
				if (tile.getDecorativeObject() != null && seen.add(tile.getDecorativeObject()))
					out.add(tile.getDecorativeObject());
				if (tile.getGroundObject() != null && seen.add(tile.getGroundObject()))
					out.add(tile.getGroundObject());
			}
		}
		return out;
	}

	@Nullable
	private static Model objectModel(TileObject object) {
		if (object instanceof GameObject)
			return modelOf(((GameObject) object).getRenderable());
		if (object instanceof WallObject) {
			Model model = modelOf(((WallObject) object).getRenderable1());
			return model != null ? model : modelOf(((WallObject) object).getRenderable2());
		}
		if (object instanceof DecorativeObject) {
			Model model = modelOf(((DecorativeObject) object).getRenderable());
			return model != null ? model : modelOf(((DecorativeObject) object).getRenderable2());
		}
		if (object instanceof GroundObject)
			return modelOf(((GroundObject) object).getRenderable());
		return null;
	}

	@Nullable
	private static Model modelOf(@Nullable Renderable renderable) {
		if (renderable instanceof Model)
			return (Model) renderable;
		if (renderable instanceof DynamicObject)
			return ((DynamicObject) renderable).getModel();
		return null;
	}

	private static int objectAnimFrame(@Nullable TileObject object) {
		Renderable renderable = null;
		if (object instanceof GameObject)
			renderable = ((GameObject) object).getRenderable();
		else if (object instanceof WallObject) {
			renderable = ((WallObject) object).getRenderable1();
			if (!(renderable instanceof DynamicObject))
				renderable = ((WallObject) object).getRenderable2();
		} else if (object instanceof DecorativeObject) {
			renderable = ((DecorativeObject) object).getRenderable();
			if (!(renderable instanceof DynamicObject))
				renderable = ((DecorativeObject) object).getRenderable2();
		} else if (object instanceof GroundObject)
			renderable = ((GroundObject) object).getRenderable();
		return renderable instanceof DynamicObject ? ((DynamicObject) renderable).getAnimFrame() : -1;
	}

	@Nullable
	private String objectDisplayName(int objectId) {
		try (GamevalManager.Handle handle = gamevalManager.obtainHandle()) {
			String gameval = handle.getObjectName(objectId);
			if (gameval != null && !gameval.isEmpty())
				return gameval;
		}
		String name = client.getObjectDefinition(objectId).getName();
		return name != null && !name.isEmpty() ? name : "object_" + objectId;
	}

	private String npcDisplayName(int npcId) {
		try (GamevalManager.Handle handle = gamevalManager.obtainHandle()) {
			String gameval = handle.getNpcName(npcId);
			if (gameval != null && !gameval.isEmpty())
				return gameval;
		}
		NPCComposition composition = client.getNpcDefinition(npcId);
		String name = composition == null ? null : composition.getName();
		return name != null && !name.isEmpty() ? name : "npc_" + npcId;
	}

	private static final class Agg {
		ObjectKind kind = ObjectKind.GAME_OBJECT;
		int count;
		int nearestTiles = Integer.MAX_VALUE;
		int nearestLocalDist = Integer.MAX_VALUE;
	}
}
