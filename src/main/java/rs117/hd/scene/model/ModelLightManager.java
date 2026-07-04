package rs117.hd.scene.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.PlayerChanged;
import net.runelite.api.events.PlayerDespawned;
import net.runelite.api.events.PlayerSpawned;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import rs117.hd.HdPlugin;
import rs117.hd.scene.LightManager;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.lights.Light;
import rs117.hd.scene.lights.LightDefinition;

import static net.runelite.api.Perspective.COSINE;
import static net.runelite.api.Perspective.SINE;
import static net.runelite.api.Perspective.getFootprintTileHeight;
import static net.runelite.api.Perspective.localToCanvas;

@Singleton
@Slf4j
public class ModelLightManager {
	@Inject
	private Client client;

	@Inject
	private HdPlugin plugin;

	@Inject
	private LightManager lightManager;

	@Inject
	private ModelLightStore store;

	@Inject
	private EventBus eventBus;

	@Inject
	private ClientThread clientThread;

	private final List<PlayerState> playerStates = new ArrayList<>();

	public static final class ViewerCapture {
		@Nullable
		public final ModelSnapshot snapshot;
		public final boolean startRecording;

		ViewerCapture(@Nullable ModelSnapshot snapshot, boolean startRecording) {
			this.snapshot = snapshot;
			this.startRecording = startRecording;
		}
	}

	@lombok.Setter
	@Nullable
	private Runnable lightDefinitionsListener;

	public void startUp() {
		store.load();
		store.setChangeListener(this::invalidateAllActors);
		store.startWatching();
		eventBus.register(this);
		invalidateAllActors();
	}

	public void shutDown() {
		eventBus.unregister(this);
		store.stopWatching();
		store.setChangeListener(null);
		playerStates.clear();
	}

	public void onLightDefinitionsChanged() {
		invalidateAllActors();
		if (lightDefinitionsListener != null)
			lightDefinitionsListener.run();
	}

	public List<String> getAvailableLightDescriptions() {
		return lightManager.getLightDescriptions();
	}

	@Nullable
	public LightDefinition getLightTemplate(String description) {
		return lightManager.getLightDefinitionByDescription(description);
	}

	public void update(@Nonnull SceneContext sceneContext) {
		if (client.getGameState() != GameState.LOGGED_IN)
			return;

		for (PlayerState state : playerStates) {
			if (state.player == null)
				continue;
			var cachedPlayers = client.getTopLevelWorldView().players();
			if (state.player != cachedPlayers.byIndex(state.player.getId()))
				continue;
			updateVertexPositions(state.player, state.lights);
		}
	}

	@Nullable
	public ModelSnapshot captureLocalPlayerSnapshot() {
		ViewerCapture capture = captureViewerSnapshot();
		return capture == null ? null : capture.snapshot;
	}

	@Nullable
	public ViewerCapture captureViewerSnapshot() {
		Player player = client.getLocalPlayer();
		if (player == null)
			return null;
		Model model = player.getModel();
		if (model == null)
			return null;
		return new ViewerCapture(ModelSnapshot.capture(model), true);
	}

	@Nullable
	public PlayerComposition getLocalPlayerComposition() {
		Player player = client.getLocalPlayer();
		return player == null ? null : player.getPlayerComposition();
	}

	public Set<Integer> getWornItemIds(@Nullable PlayerComposition composition) {
		if (composition == null)
			return Set.of();
		Set<Integer> ids = new HashSet<>();
		for (int id : composition.getEquipmentIds()) {
			if (id != -1)
				ids.add(id);
		}
		return ids;
	}

	private void invalidateAllActors() {
		for (PlayerState state : playerStates)
			state.invalidate();

		clientThread.invokeLater(() -> {
			var sceneContext = plugin.getSceneContext();
			if (sceneContext == null)
				return;

			for (Player player : client.getPlayers()) {
				trackPlayer(player);
				for (PlayerState state : playerStates) {
					if (state.player == player) {
						resolvePlayer(state, sceneContext);
						updateVertexPositions(state.player, state.lights);
					}
				}
			}
		});
	}

	private void trackPlayer(Player player) {
		for (PlayerState state : playerStates) {
			if (state.player == player)
				return;
		}
		playerStates.add(new PlayerState(player));
	}

	private void resolvePlayer(PlayerState state, SceneContext sceneContext) {
		Player player = state.player;
		PlayerComposition composition = player.getPlayerComposition();
		if (composition == null) {
			removeModelLights(sceneContext, state);
			state.equipmentIds = null;
			return;
		}

		int[] equipmentIds = composition.getEquipmentIds();
		int revision = store.getRevision();
		if (Arrays.equals(equipmentIds, state.equipmentIds) && revision == state.revision)
			return;

		Model model = player.getModel();
		if (model == null) {
			removeModelLights(sceneContext, state);
			state.equipmentIds = null;
			return;
		}

		state.equipmentIds = equipmentIds.clone();
		state.revision = revision;

		removeModelLights(sceneContext, state);
		state.lights.clear();

		Set<Integer> wornItemIds = getWornItemIds(composition);
		Map<String, ModelLightProfile> profiles = store.snapshotAll();
		ModelSnapshot snapshot = ModelSnapshot.capture(model);

		for (ModelSnapshot.Piece piece : snapshot.getPieces()) {
			for (Map.Entry<String, ModelLightProfile> entry : profiles.entrySet()) {
				ModelLightProfile profile = entry.getValue();
				if (!piece.getMeshKey().equals(profile.getMeshKey())
					|| profile.isNpcProfile()
					|| !profile.isEnabled()
					|| (!profile.getItemIds().isEmpty() && Collections.disjoint(profile.getItemIds(), wornItemIds))
					|| (profile.getVertices().isEmpty() && profile.getTriangles().isEmpty()))
					continue;

				spawnPieceLights(sceneContext, player, profile, entry.getKey(), snapshot, piece, state.lights);
			}
		}
	}

	private void spawnPieceLights(
		SceneContext sceneContext,
		Actor actor,
		ModelLightProfile profile,
		String profileKey,
		ModelSnapshot snapshot,
		ModelSnapshot.Piece piece,
		List<Light> lights
	) {
		for (var entry : profile.getTriangles().entrySet()) {
			int localFace = entry.getKey();
			if (localFace < 0 || localFace >= piece.getFaces().length)
				continue;

			TriangleAnchor anchor = entry.getValue();
			String lightDesc = profile.lightDescriptionForTriangle(localFace);
			LightDefinition template = lightManager.getLightDefinitionByDescription(lightDesc);
			if (template == null) {
				log.debug("Unknown model light template: {}", lightDesc);
				continue;
			}

			int globalFace = piece.getFaces()[localFace];
			int[] i1 = snapshot.getFaceIndices1();
			int[] i2 = snapshot.getFaceIndices2();
			int[] i3 = snapshot.getFaceIndices3();
			int v0 = i1[globalFace], v1 = i2[globalFace], v2 = i3[globalFace];

			Light light = new Light(template);
			light.plane = -1;
			light.actor = actor;
			light.modelVertex = -1;
			light.modelProfileKey = profileKey;
			light.modelFaceV0 = v0;
			light.modelFaceV1 = v1;
			light.modelFaceV2 = v2;
			light.modelBary0 = anchor.getBary0();
			light.modelBary1 = anchor.getBary1();
			light.modelBary2 = anchor.getBary2();
			light.modelOffsetX = profile.getOffsetX();
			light.modelOffsetY = profile.getOffsetY();
			light.modelOffsetZ = profile.getOffsetZ();
			anchorOffsetToTriangle(light, snapshot, v0, v1, v2, anchor);
			sceneContext.lights.add(light);
			lights.add(light);
		}

		for (var entry : profile.getVertices().entrySet()) {
			int local = entry.getKey();
			if (local < 0 || local >= piece.getVertices().length)
				continue;

			int globalVertex = piece.getVertices()[local];
			String lightDesc = entry.getValue();
			LightDefinition template = lightManager.getLightDefinitionByDescription(lightDesc);
			if (template == null) {
				log.debug("Unknown model light template: {}", lightDesc);
				continue;
			}

			Light light = new Light(template);
			light.plane = -1;
			light.actor = actor;
			light.modelVertex = globalVertex;
			light.modelProfileKey = profileKey;
			light.modelOffsetX = profile.getOffsetX();
			light.modelOffsetY = profile.getOffsetY();
			light.modelOffsetZ = profile.getOffsetZ();
			anchorOffsetToFace(light, snapshot, piece, globalVertex);
			anchorFaceForConeDirection(light, snapshot, piece, globalVertex);
			sceneContext.lights.add(light);
			lights.add(light);
		}
	}

	private static void anchorOffsetToTriangle(
		Light light,
		ModelSnapshot snapshot,
		int v0,
		int v1,
		int v2,
		TriangleAnchor anchor
	) {
		float ox = light.modelOffsetX;
		float oy = -light.modelOffsetZ;
		float oz = light.modelOffsetY;
		if (ox == 0 && oy == 0 && oz == 0)
			return;

		float[] xs = snapshot.getVerticesX();
		float[] ys = snapshot.getVerticesY();
		float[] zs = snapshot.getVerticesZ();
		float b0 = anchor.getBary0(), b1 = anchor.getBary1(), b2 = anchor.getBary2();
		float ax = b0 * xs[v0] + b1 * xs[v1] + b2 * xs[v2];
		float ay = b0 * ys[v0] + b1 * ys[v1] + b2 * ys[v2];
		float az = b0 * zs[v0] + b1 * zs[v1] + b2 * zs[v2];

		decomposeOffsetInTriangleBasis(light, xs, ys, zs, v0, v1, v2, ax, ay, az, ox, oy, oz);
	}

	private static void decomposeOffsetInTriangleBasis(
		Light light,
		float[] xs,
		float[] ys,
		float[] zs,
		int v0,
		int v1,
		int v2,
		float ax,
		float ay,
		float az,
		float ox,
		float oy,
		float oz
	) {
		float e1x = xs[v1] - xs[v0], e1y = ys[v1] - ys[v0], e1z = zs[v1] - zs[v0];
		float e2x = xs[v2] - xs[v0], e2y = ys[v2] - ys[v0], e2z = zs[v2] - zs[v0];
		float nx = e1y * e2z - e1z * e2y;
		float ny = e1z * e2x - e1x * e2z;
		float nz = e1x * e2y - e1y * e2x;
		float nLen = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (nLen < 1e-3f)
			return;
		nx /= nLen;
		ny /= nLen;
		nz /= nLen;

		float c = ox * nx + oy * ny + oz * nz;
		float qx = ox - c * nx, qy = oy - c * ny, qz = oz - c * nz;
		float d11 = e1x * e1x + e1y * e1y + e1z * e1z;
		float d12 = e1x * e2x + e1y * e2y + e1z * e2z;
		float d22 = e2x * e2x + e2y * e2y + e2z * e2z;
		float det = d11 * d22 - d12 * d12;
		if (det < 1e-3f)
			return;
		float q1 = qx * e1x + qy * e1y + qz * e1z;
		float q2 = qx * e2x + qy * e2y + qz * e2z;

		light.modelOffsetA = (q1 * d22 - q2 * d12) / det;
		light.modelOffsetB = (q2 * d11 - q1 * d12) / det;
		light.modelOffsetC = c;
	}

	private static void anchorOffsetToFace(Light light, ModelSnapshot snapshot, ModelSnapshot.Piece piece, int globalVertex) {
		float ox = light.modelOffsetX;
		float oy = -light.modelOffsetZ;
		float oz = light.modelOffsetY;
		if (ox == 0 && oy == 0 && oz == 0)
			return;

		int[] i1 = snapshot.getFaceIndices1();
		int[] i2 = snapshot.getFaceIndices2();
		int[] i3 = snapshot.getFaceIndices3();
		float[] xs = snapshot.getVerticesX();
		float[] ys = snapshot.getVerticesY();
		float[] zs = snapshot.getVerticesZ();

		for (int f : piece.getFaces()) {
			int v0 = i1[f], v1 = i2[f], v2 = i3[f];
			if (v1 == globalVertex) {
				int t = v0;
				v0 = v1;
				v1 = v2;
				v2 = t;
			} else if (v2 == globalVertex) {
				int t = v2;
				v2 = v1;
				v1 = v0;
				v0 = t;
			} else if (v0 != globalVertex) {
				continue;
			}

			light.modelFaceV0 = v0;
			light.modelFaceV1 = v1;
			light.modelFaceV2 = v2;
			light.modelBary0 = 1f;
			light.modelBary1 = 0f;
			light.modelBary2 = 0f;
			decomposeOffsetInTriangleBasis(light, xs, ys, zs, v0, v1, v2,
				xs[v0], ys[v0], zs[v0], ox, oy, oz);
			return;
		}
	}

	private void updateVertexPositions(Actor actor, List<Light> lights) {
		Model model = actor.getModel();
		if (model == null)
			return;

		int vertexCount = model.getVerticesCount();
		int orientation = actor.getCurrentOrientation();
		int sin = SINE[orientation];
		int cos = COSINE[orientation];
		var lp = actor.getLocalLocation();
		int anchorLevel = actor.getWorldLocation().getPlane();
		int baseZ = getFootprintTileHeight(client, lp, anchorLevel, actor.getFootprintSize())
			- actor.getAnimationHeightOffset();

		float[] verticesX = model.getVerticesX();
		float[] verticesY = model.getVerticesY();
		float[] verticesZ = model.getVerticesZ();

		for (Light light : lights) {
			if (light.markedForRemoval || light.modelProfileKey == null)
				continue;

			float vx, vy, vz;
			float nx = 0, ny = 0, nz = 0;
			boolean hasTriangleBasis = false;
			if (light.modelFaceV0 >= 0
				&& light.modelFaceV1 < vertexCount
				&& light.modelFaceV2 < vertexCount) {
				int v0 = light.modelFaceV0, v1 = light.modelFaceV1, v2 = light.modelFaceV2;
				float b0 = light.modelBary0, b1 = light.modelBary1, b2 = light.modelBary2;
				float ax = b0 * verticesX[v0] + b1 * verticesX[v1] + b2 * verticesX[v2];
				float ay = b0 * verticesY[v0] + b1 * verticesY[v1] + b2 * verticesY[v2];
				float az = b0 * verticesZ[v0] + b1 * verticesZ[v1] + b2 * verticesZ[v2];
				float e1x = verticesX[v1] - verticesX[v0], e1y = verticesY[v1] - verticesY[v0], e1z = verticesZ[v1] - verticesZ[v0];
				float e2x = verticesX[v2] - verticesX[v0], e2y = verticesY[v2] - verticesY[v0], e2z = verticesZ[v2] - verticesZ[v0];
				nx = e1y * e2z - e1z * e2y;
				ny = e1z * e2x - e1x * e2z;
				nz = e1x * e2y - e1y * e2x;
				float nLen = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
				if (nLen > 1e-6f) {
					nx /= nLen;
					ny /= nLen;
					nz /= nLen;
					hasTriangleBasis = true;
				}
				float a = light.modelOffsetA, b = light.modelOffsetB, c = light.modelOffsetC;
				vx = ax + a * e1x + b * e2x + c * nx;
				vy = ay + a * e1y + b * e2y + c * ny;
				vz = az + a * e1z + b * e2z + c * nz;

				if (hasTriangleBasis)
					updateModelConeDirection(light, nx, ny, nz, ax, ay, az, sin, cos);
			} else {
				int v = light.modelVertex;
				if (v < 0 || v >= vertexCount)
					continue;
				vx = verticesX[v] + light.modelOffsetX;
				vy = verticesY[v] - light.modelOffsetZ;
				vz = verticesZ[v] + light.modelOffsetY;
			}

			light.origin[0] = lp.getX() + (vz * sin + vx * cos) / 65536f;
			light.origin[2] = lp.getY() + (vz * cos - vx * sin) / 65536f;
			light.origin[1] = baseZ + vy;
			light.orientation = orientation;
			light.plane = client.getPlane();
		}
	}

	private static void updateModelConeDirection(
		Light light,
		float nx,
		float ny,
		float nz,
		float anchorX,
		float anchorY,
		float anchorZ,
		int sin,
		int cos
	) {
		if (light.def.outerConeAngle <= 0)
			return;

		if (nx * anchorX + ny * anchorY + nz * anchorZ < 0) {
			nx = -nx;
			ny = -ny;
			nz = -nz;
		}

		float mx = nx, my = ny, mz = nz;
		if (light.def.conePitch != 0) {
			float[] pitched = new float[3];
			rotateToward(mx, my, mz, 0, 1, 0, -light.def.conePitch, pitched);
			mx = pitched[0];
			my = pitched[1];
			mz = pitched[2];
		}

		float dx = mz * sin + mx * cos;
		float dz = mz * cos - mx * sin;
		float dy = my;
		float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len < 1e-6f)
			return;

		light.direction[0] = dx / len;
		light.direction[1] = dy / len;
		light.direction[2] = dz / len;
	}

	private static void rotateToward(
		float nx,
		float ny,
		float nz,
		float tx,
		float ty,
		float tz,
		float degrees,
		float[] out
	) {
		float nLen = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		float tLen = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
		if (nLen < 1e-6f || tLen < 1e-6f) {
			out[0] = nx;
			out[1] = ny;
			out[2] = nz;
			return;
		}
		nx /= nLen;
		ny /= nLen;
		nz /= nLen;
		tx /= tLen;
		ty /= tLen;
		tz /= tLen;

		float dot = Math.max(-1f, Math.min(1f, nx * tx + ny * ty + nz * tz));
		float totalAngle = (float) Math.acos(dot);
		float rotateBy = (float) Math.toRadians(degrees);
		if (totalAngle < 1e-6f || Math.abs(rotateBy) < 1e-6f) {
			out[0] = nx;
			out[1] = ny;
			out[2] = nz;
			return;
		}
		float t = Math.min(1f, Math.abs(rotateBy) / totalAngle);
		if (rotateBy < 0)
			t = -t;
		float sinTotal = (float) Math.sin(totalAngle);
		float w0 = (float) Math.sin((1 - t) * totalAngle) / sinTotal;
		float w1 = (float) Math.sin(t * totalAngle) / sinTotal;
		out[0] = nx * w0 + tx * w1;
		out[1] = ny * w0 + ty * w1;
		out[2] = nz * w0 + tz * w1;
	}

	private void anchorFaceForConeDirection(Light light, ModelSnapshot snapshot, ModelSnapshot.Piece piece, int globalVertex) {
		if (light.def.outerConeAngle <= 0 || light.modelFaceV0 >= 0)
			return;

		int[] i1 = snapshot.getFaceIndices1();
		int[] i2 = snapshot.getFaceIndices2();
		int[] i3 = snapshot.getFaceIndices3();

		for (int f : piece.getFaces()) {
			int v0 = i1[f], v1 = i2[f], v2 = i3[f];
			if (v1 == globalVertex) {
				int t = v0;
				v0 = v1;
				v1 = v2;
				v2 = t;
			} else if (v2 == globalVertex) {
				int t = v2;
				v2 = v1;
				v1 = v0;
				v0 = t;
			} else if (v0 != globalVertex) {
				continue;
			}

			light.modelFaceV0 = v0;
			light.modelFaceV1 = v1;
			light.modelFaceV2 = v2;
			light.modelBary0 = 1f;
			light.modelBary1 = 0f;
			light.modelBary2 = 0f;
			return;
		}
	}

	private void updateVertexPositions(PlayerState state) {
		updateVertexPositions(state.player, state.lights);
	}

	private void removeModelLights(SceneContext sceneContext, PlayerState state) {
		removeModelLights(sceneContext, state.lights);
	}

	private void removeModelLights(SceneContext sceneContext, List<Light> lights) {
		for (Light light : lights)
			light.markedForRemoval = true;
		lights.clear();
	}

	private void onPlayerEvent(Player player) {
		var sceneContext = plugin.getSceneContext();
		if (sceneContext == null)
			return;
		trackPlayer(player);
		for (PlayerState state : playerStates) {
			if (state.player == player) {
				state.invalidate();
				resolvePlayer(state, sceneContext);
				updateVertexPositions(state);
			}
		}
	}

	@Subscribe
	public void onPlayerSpawned(PlayerSpawned event) {
		onPlayerEvent(event.getPlayer());
	}

	@Subscribe
	public void onPlayerChanged(PlayerChanged event) {
		onPlayerEvent(event.getPlayer());
	}

	@Subscribe
	public void onPlayerDespawned(PlayerDespawned event) {
		var sceneContext = plugin.getSceneContext();
		if (sceneContext == null)
			return;
		Player player = event.getPlayer();
		playerStates.removeIf(state -> {
			if (state.player == player) {
				removeModelLights(sceneContext, state);
				return true;
			}
			return false;
		});
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		if (event.getGameState() == GameState.LOGGED_IN)
			invalidateAllActors();
	}

	public Map<String, ModelLightProfile> getProfilesForEditor() {
		return store.snapshotAll();
	}

	public void onSceneReload(SceneContext sceneContext) {
		for (PlayerState state : playerStates) {
			state.lights.clear();
			state.invalidate();
			resolvePlayer(state, sceneContext);
			updateVertexPositions(state);
		}
	}

	public Set<String> getPresentMeshKeys(@Nullable ModelSnapshot snapshot) {
		if (snapshot == null)
			return Set.of();
		return snapshot.getPieces().stream()
			.map(ModelSnapshot.Piece::getMeshKey)
			.collect(Collectors.toCollection(TreeSet::new));
	}

	public static final class ProjectedVertex {
		public final int globalVertex;
		public final int localVertex;
		public final int canvasX;
		public final int canvasY;
		public final boolean selected;

		ProjectedVertex(int globalVertex, int localVertex, int canvasX, int canvasY, boolean selected) {
			this.globalVertex = globalVertex;
			this.localVertex = localVertex;
			this.canvasX = canvasX;
			this.canvasY = canvasY;
			this.selected = selected;
		}
	}

	public int[] vertexToSceneLocal(Player player, Model model, int globalVertex, float offsetX, float offsetY, float offsetZ) {
		if (globalVertex < 0 || globalVertex >= model.getVerticesCount())
			return null;

		int orientation = player.getCurrentOrientation();
		int sin = SINE[orientation];
		int cos = COSINE[orientation];
		var lp = player.getLocalLocation();

		float vx = model.getVerticesX()[globalVertex] + offsetX;
		float vy = model.getVerticesY()[globalVertex] - offsetZ;
		float vz = model.getVerticesZ()[globalVertex] + offsetY;

		int x = lp.getX() + (int) ((vz * sin + vx * cos) / 65536f);
		int y = lp.getY() + (int) ((vz * cos - vx * sin) / 65536f);
		int anchorLevel = player.getWorldLocation().getPlane();
		int z = getFootprintTileHeight(client, lp, anchorLevel, player.getFootprintSize())
			- player.getAnimationHeightOffset()
			+ (int) vy;

		return new int[] { x, y, z };
	}

	@Nullable
	public Point projectVertexToCanvas(Player player, Model model, int globalVertex, float offsetX, float offsetY, float offsetZ) {
		int[] local = vertexToSceneLocal(player, model, globalVertex, offsetX, offsetY, offsetZ);
		if (local == null)
			return null;
		return localToCanvas(client, local[0], local[1], local[2], client.getPlane());
	}

	public List<ProjectedVertex> projectPieceVertices(
		Player player,
		ModelSnapshot snapshot,
		int pieceIndex,
		Set<Integer> selectedLocals,
		float offsetX,
		float offsetY,
		float offsetZ
	) {
		if (snapshot == null || pieceIndex < 0 || pieceIndex >= snapshot.getPieces().size())
			return List.of();

		Model model = player.getModel();
		if (model == null)
			return List.of();

		var piece = snapshot.getPieces().get(pieceIndex);
		List<ProjectedVertex> result = new ArrayList<>();
		for (int local = 0; local < piece.getVertices().length; local++) {
			int global = piece.getVertices()[local];
			Point p = projectVertexToCanvas(player, model, global, offsetX, offsetY, offsetZ);
			if (p == null)
				continue;
			result.add(new ProjectedVertex(
				global,
				local,
				p.getX(),
				p.getY(),
				selectedLocals.contains(local)
			));
		}
		return result;
	}

	public int findVertexAt(
		List<ProjectedVertex> projected,
		int mouseX,
		int mouseY,
		int hitRadius
	) {
		int best = -1;
		int bestDistSq = hitRadius * hitRadius;
		for (ProjectedVertex v : projected) {
			int dx = v.canvasX - mouseX;
			int dy = v.canvasY - mouseY;
			int distSq = dx * dx + dy * dy;
			if (distSq < bestDistSq) {
				bestDistSq = distSq;
				best = v.globalVertex;
			}
		}
		return best;
	}

	private static final class PlayerState {
		final Player player;
		int[] equipmentIds;
		int revision = -1;
		final List<Light> lights = new ArrayList<>();

		PlayerState(Player player) {
			this.player = player;
		}

		void invalidate() {
			equipmentIds = null;
			revision = -1;
		}
	}
}
