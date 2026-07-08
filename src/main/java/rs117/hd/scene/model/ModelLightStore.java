package rs117.hd.scene.model;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.HdPlugin;
import rs117.hd.utils.FileWatcher;
import rs117.hd.utils.GsonUtils;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.utils.ResourcePath.path;

@Singleton
@Slf4j
public class ModelLightStore {
	private static final Type PROFILE_MAP_TYPE = new TypeToken<Map<String, ModelLightProfile>>() {}.getType();
	private static final ResourcePath MODEL_LIGHTS_PATH = Props.getFile(
		"rlhd.model-lights-path",
		() -> path(ModelLightStore.class, "model_lights.json")
	);

	private Gson gson;

	@Inject
	private void setGson(Gson gson) {
		this.gson = GsonUtils.wrap(gson);
	}

	private final Map<String, ModelLightProfile> profiles = new LinkedHashMap<>();
	private int revision;

	@Setter
	private Runnable changeListener;

	@Setter
	@Nullable
	private Runnable importListener;

	@Nullable
	private FileWatcher.UnregisterCallback fileWatcher;
	@Nullable
	private String lastWrittenJson;

	public static ResourcePath getResourcePath() {
		return MODEL_LIGHTS_PATH;
	}

	public static ResourcePath getExportPath() {
		return HdPlugin.PLUGIN_DIR.resolve("model_lights_export.json");
	}

	public synchronized void load() {
		if (!MODEL_LIGHTS_PATH.exists()) {
			log.debug("No model_lights.json at {}", MODEL_LIGHTS_PATH);
			return;
		}
		try {
			applyJson(MODEL_LIGHTS_PATH.loadString());
		} catch (IOException ex) {
			log.warn("Failed to load model_lights.json from {}", MODEL_LIGHTS_PATH, ex);
		}
	}

	private void applyJson(String json) {
		profiles.clear();

		try {
			JsonObject root = gson.fromJson(json, JsonObject.class);
			if (root == null || !root.has("profiles"))
				return;
			Map<String, ModelLightProfile> loaded = gson.fromJson(root.get("profiles"), PROFILE_MAP_TYPE);
			if (loaded == null)
				return;
			loadProfiles(loaded);
			lastWrittenJson = json;
		} catch (Exception ex) {
			log.warn("Ignoring invalid model_lights.json", ex);
		}
	}

	private static boolean isReservedKey(String key) {
		return "profiles".equals(key);
	}

	private void loadProfiles(Map<String, ModelLightProfile> loaded) {
		loaded.forEach((key, profile) -> {
			if (isReservedKey(key) || profile == null)
				return;
			if (profile.getMeshKey() == null)
				profile.setMeshKey(key);
			if (profile.getVertices() == null)
				profile.setVertices(new LinkedHashMap<>());
			if (profile.getTriangles() == null)
				profile.setTriangles(new LinkedHashMap<>());
			if (profile.getItemIds() == null)
				profile.setItemIds(new HashSet<>());
			if (profile.getNpcIds() == null)
				profile.setNpcIds(new HashSet<>());
			if (profile.getObjectIds() == null)
				profile.setObjectIds(new HashSet<>());
			normalizeVertices(profile);
			normalizeTriangles(profile);
			profiles.put(key, profile);
		});
	}

	private static void normalizeTriangles(ModelLightProfile profile) {
		Map<Integer, TriangleAnchor> normalized = new LinkedHashMap<>();
		for (var entry : profile.getTriangles().entrySet()) {
			Integer index = entry.getKey();
			TriangleAnchor anchor = entry.getValue();
			if (index == null || index < 0 || anchor == null)
				continue;
			String light = anchor.getLight();
			if (light == null || light.isEmpty())
				light = profile.getLightDescription();
			TriangleAnchor copy = new TriangleAnchor(light, anchor.getBary0(), anchor.getBary1(), anchor.getBary2());
			copy.normalizeBarycentric();
			normalized.put(index, copy);
		}
		profile.setTriangles(normalized);
	}

	private static void normalizeVertices(ModelLightProfile profile) {
		Map<Integer, String> normalized = new LinkedHashMap<>();
		for (var entry : profile.getVertices().entrySet()) {
			Integer index = entry.getKey();
			if (index == null || index < 0)
				continue;
			String light = entry.getValue();
			if (light == null || light.isEmpty())
				light = profile.getLightDescription();
			normalized.put(index, light);
		}
		profile.setVertices(normalized);
	}

	public synchronized int getRevision() {
		return revision;
	}

	public synchronized Map<String, ModelLightProfile> snapshotAll() {
		Map<String, ModelLightProfile> copy = new LinkedHashMap<>();
		profiles.forEach((k, v) -> copy.put(k, v.copy()));
		return copy;
	}

	public synchronized Set<Integer> vertexSet(String profileKey) {
		ModelLightProfile profile = profiles.get(profileKey);
		return profile == null ? Set.of() : profile.vertexIndices();
	}

	public synchronized boolean hasTriangle(String profileKey, int localFace) {
		ModelLightProfile profile = profiles.get(profileKey);
		return profile != null && profile.hasTriangle(localFace);
	}

	public synchronized boolean hasVertex(String profileKey, int localVertex) {
		ModelLightProfile profile = profiles.get(profileKey);
		return profile != null && profile.hasVertex(localVertex);
	}

	@Nullable
	public synchronized ModelLightProfile get(String profileKey) {
		ModelLightProfile profile = profiles.get(profileKey);
		return profile == null ? null : profile.copy();
	}

	@Nullable
	public synchronized String getPieceLabel(String meshKey) {
		return profileNameForMeshKey(meshKey);
	}

	public synchronized void setPieceLabel(String meshKey, @Nullable String name) {
		if (meshKey == null)
			return;

		String trimmed = name == null ? "" : name.trim();
		String newName = trimmed.isEmpty() ? null : trimmed;
		@Nullable String profileKey = findProfileKeyForMeshKey(meshKey);
		if (profileKey == null) {
			if (newName == null)
				return;
			ensureProfileFor(meshKey, newName);
			return;
		}
		rename(profileKey, newName);
	}

	public synchronized String displayNameFor(String meshKey, @Nullable String profileName, String fallback) {
		if (profileName != null && !profileName.isEmpty())
			return profileName;
		String name = profileNameForMeshKey(meshKey);
		if (name != null && !name.isEmpty())
			return name;
		return fallback;
	}

	@Nullable
	private String profileNameForMeshKey(String meshKey) {
		for (ModelLightProfile profile : profiles.values()) {
			if (meshKey.equals(profile.getMeshKey()) && profile.getName() != null && !profile.getName().isEmpty())
				return profile.getName();
		}
		return null;
	}

	@Nullable
	private String findProfileKeyForMeshKey(String meshKey) {
		for (Map.Entry<String, ModelLightProfile> entry : profiles.entrySet()) {
			if (meshKey.equals(entry.getValue().getMeshKey()))
				return entry.getKey();
		}
		return null;
	}

	public synchronized void rename(String profileKey, @Nullable String name) {
		ModelLightProfile profile = profiles.get(profileKey);
		if (profile == null)
			return;

		String trimmed = name == null ? "" : name.trim();
		String newName = trimmed.isEmpty() ? null : trimmed;
		if (Objects.equals(newName, profile.getName()))
			return;

		profile.setName(newName);
		notifyChanged();
	}

	public synchronized void setItemIds(String profileKey, Set<Integer> itemIds) {
		ModelLightProfile profile = profiles.get(profileKey);
		if (profile == null)
			return;
		Set<Integer> normalized = itemIds == null ? new HashSet<>() : new HashSet<>(itemIds);
		if (profile.getItemIds().equals(normalized))
			return;
		profile.setItemIds(normalized);
		notifyChanged();
	}

	public synchronized void setNpcIds(String profileKey, Set<Integer> npcIds) {
		ModelLightProfile profile = profiles.get(profileKey);
		if (profile == null)
			return;
		Set<Integer> normalized = npcIds == null ? new HashSet<>() : new HashSet<>(npcIds);
		if (profile.getNpcIds().equals(normalized))
			return;
		profile.setNpcIds(normalized);
		notifyChanged();
	}

	public synchronized void setObjectIds(String profileKey, Set<Integer> objectIds) {
		ModelLightProfile profile = profiles.get(profileKey);
		if (profile == null)
			return;
		Set<Integer> normalized = objectIds == null ? new HashSet<>() : new HashSet<>(objectIds);
		if (profile.getObjectIds().equals(normalized))
			return;
		profile.setObjectIds(normalized);
		notifyChanged();
	}

	@Nullable
	public synchronized String findProfileKey(String meshKey, Set<Integer> itemIds) {
		Set<Integer> ids = itemIds == null ? Set.of() : Set.copyOf(itemIds);
		for (Map.Entry<String, ModelLightProfile> entry : profiles.entrySet()) {
			ModelLightProfile profile = entry.getValue();
			if (profile.isNpcProfile() || profile.isObjectProfile())
				continue;
			if (!meshKey.equals(profile.getMeshKey()))
				continue;
			if (profile.getItemIds().equals(ids))
				return entry.getKey();
		}
		return null;
	}

	@Nullable
	public synchronized String findObjectProfileKey(String meshKey, int objectId) {
		for (Map.Entry<String, ModelLightProfile> entry : profiles.entrySet()) {
			ModelLightProfile profile = entry.getValue();
			if (!profile.isObjectProfile() || !meshKey.equals(profile.getMeshKey()))
				continue;
			if (profile.getObjectIds().contains(objectId))
				return entry.getKey();
		}
		return null;
	}

	@Nullable
	public synchronized String findNpcProfileKey(String meshKey, int npcId) {
		for (Map.Entry<String, ModelLightProfile> entry : profiles.entrySet()) {
			ModelLightProfile profile = entry.getValue();
			if (!profile.isNpcProfile() || !meshKey.equals(profile.getMeshKey()))
				continue;
			if (profile.getNpcIds().contains(npcId))
				return entry.getKey();
		}
		return null;
	}

	public synchronized String ensureProfileFor(String meshKey, String defaultName) {
		return ensureProfileFor(meshKey, defaultName, Set.of());
	}

	public synchronized String ensureProfileFor(String meshKey, String defaultName, Set<Integer> itemIds) {
		Set<Integer> ids = itemIds == null ? Set.of() : Set.copyOf(itemIds);
		@Nullable String existing = findProfileKey(meshKey, ids);
		if (existing != null)
			return existing;

		String key = freeKey(buildModelProfileKey(meshKey, ids));
		String name = displayNameFor(meshKey, null, defaultName);
		ModelLightProfile profile = new ModelLightProfile(name);
		profile.setMeshKey(meshKey);
		profile.setItemIds(new HashSet<>(ids));
		profiles.put(key, profile);
		notifyChanged();
		return key;
	}

	public synchronized String ensureProfileForObject(String meshKey, String defaultName, int objectId) {
		@Nullable String existing = findObjectProfileKey(meshKey, objectId);
		if (existing != null)
			return existing;

		String key = freeKey(meshKey + "@obj" + objectId);
		String name = displayNameFor(meshKey, null, defaultName);
		ModelLightProfile profile = new ModelLightProfile(name);
		profile.setMeshKey(meshKey);
		profile.getObjectIds().add(objectId);
		profiles.put(key, profile);
		notifyChanged();
		return key;
	}

	public synchronized String ensureProfileForNpc(String meshKey, String defaultName, int npcId) {
		@Nullable String existing = findNpcProfileKey(meshKey, npcId);
		if (existing != null)
			return existing;

		String key = freeKey(meshKey + "@npc" + npcId);
		String name = displayNameFor(meshKey, null, defaultName);
		ModelLightProfile profile = new ModelLightProfile(name);
		profile.setMeshKey(meshKey);
		profile.getNpcIds().add(npcId);
		profiles.put(key, profile);
		notifyChanged();
		return key;
	}

	private static String buildModelProfileKey(String meshKey, Set<Integer> itemIds) {
		if (itemIds.isEmpty())
			return meshKey;
		StringBuilder suffix = new StringBuilder("@items");
		itemIds.stream().sorted().forEach(id -> suffix.append('_').append(id));
		return meshKey + suffix;
	}

	@Nullable
	public synchronized String duplicate(String profileKey) {
		ModelLightProfile source = profiles.get(profileKey);
		if (source == null)
			return null;
		ModelLightProfile copy = source.copy();
		copy.setName(source.getName() + " copy");
		String key = freeKey(source.getMeshKey());
		profiles.put(key, copy);
		notifyChanged();
		return key;
	}

	public synchronized void setEnabled(String profileKey, boolean enabled) {
		ModelLightProfile profile = profiles.get(profileKey);
		if (profile != null && profile.isEnabled() != enabled) {
			profile.setEnabled(enabled);
			notifyChanged();
		}
	}

	public synchronized void setAllEnabled(boolean enabled) {
		boolean changed = false;
		for (ModelLightProfile profile : profiles.values()) {
			if (profile.isEnabled() != enabled) {
				profile.setEnabled(enabled);
				changed = true;
			}
		}
		if (changed)
			notifyChanged();
	}

	public synchronized void rebind(String profileKey, String newMeshKey) {
		ModelLightProfile profile = profiles.get(profileKey);
		if (profile == null || newMeshKey.equals(profile.getMeshKey()))
			return;
		profile.setMeshKey(newMeshKey);
		profile.getVertices().clear();
		notifyChanged();
	}

	public synchronized boolean toggleTriangle(
		String profileKey,
		int localFace,
		float bary0,
		float bary1,
		float bary2
	) {
		ModelLightProfile profile = profiles.get(profileKey);
		if (profile == null)
			return false;
		if (profile.getTriangles().remove(localFace) != null) {
			notifyChanged();
			return false;
		}
		TriangleAnchor anchor = new TriangleAnchor(profile.getLightDescription(), bary0, bary1, bary2);
		anchor.normalizeBarycentric();
		profile.getTriangles().put(localFace, anchor);
		notifyChanged();
		return true;
	}

	public synchronized void setTriangleLightDescription(String profileKey, int localFace, String description) {
		ModelLightProfile profile = profiles.get(profileKey);
		if (profile == null)
			return;
		TriangleAnchor anchor = profile.getTriangles().get(localFace);
		if (anchor == null)
			return;
		if (description == null || description.isEmpty())
			anchor.setLight(profile.getLightDescription());
		else
			anchor.setLight(description);
		notifyChanged();
	}

	public synchronized boolean toggleVertex(String profileKey, int localVertex) {
		ModelLightProfile profile = profiles.get(profileKey);
		if (profile == null)
			return false;
		if (profile.getVertices().remove(localVertex) != null) {
			notifyChanged();
			return false;
		}
		profile.getVertices().put(localVertex, profile.getLightDescription());
		notifyChanged();
		return true;
	}

	public synchronized void addVertices(String profileKey, Collection<Integer> localVertices) {
		ModelLightProfile profile = profiles.get(profileKey);
		if (profile == null)
			return;
		boolean changed = false;
		for (int local : localVertices) {
			if (profile.hasVertex(local))
				continue;
			profile.getVertices().put(local, profile.getLightDescription());
			changed = true;
		}
		if (changed)
			notifyChanged();
	}

	public synchronized void removeVertices(String profileKey, Collection<Integer> localVertices) {
		ModelLightProfile profile = profiles.get(profileKey);
		if (profile == null)
			return;
		if (profile.getVertices().keySet().removeAll(localVertices))
			notifyChanged();
	}

	public synchronized void setProfileOffset(String profileKey, float x, float y, float z) {
		ModelLightProfile profile = profiles.get(profileKey);
		if (profile == null)
			return;
		profile.setOffsetX(x);
		profile.setOffsetY(y);
		profile.setOffsetZ(z);
		notifyChanged();
	}

	public synchronized void setTriangleBarycentric(
		String profileKey,
		int localFace,
		float bary0,
		float bary1,
		float bary2
	) {
		ModelLightProfile profile = profiles.get(profileKey);
		if (profile == null)
			return;
		TriangleAnchor anchor = profile.getTriangles().get(localFace);
		if (anchor == null)
			return;
		anchor.setBary0(bary0);
		anchor.setBary1(bary1);
		anchor.setBary2(bary2);
		anchor.normalizeBarycentric();
		notifyChanged();
	}

	public synchronized void updateProfile(String profileKey, ModelLightProfile settingsSource) {
		ModelLightProfile profile = profiles.get(profileKey);
		if (profile != null) {
			profile.copySettingsFrom(settingsSource);
			notifyChanged();
		}
	}

	public synchronized void setVertexLightDescription(String profileKey, int localVertex, String description) {
		ModelLightProfile profile = profiles.get(profileKey);
		if (profile == null)
			return;
		if (description == null || description.isEmpty())
			profile.getVertices().put(localVertex, profile.getLightDescription());
		else
			profile.getVertices().put(localVertex, description);
		notifyChanged();
	}

	public synchronized void delete(String profileKey) {
		if (profiles.remove(profileKey) != null)
			notifyChanged();
	}

	public void startWatching() {
		if (fileWatcher != null)
			return;
		fileWatcher = MODEL_LIGHTS_PATH.watch((path, first) -> {
			if (first || !path.exists())
				return;
			try {
				reloadFromDisk(path.loadString());
			} catch (IOException ex) {
				log.warn("Failed to read edited model_lights.json", ex);
			}
		});
	}

	public void stopWatching() {
		if (fileWatcher != null) {
			fileWatcher.unregister();
			fileWatcher = null;
		}
	}

	private void reloadFromDisk(String json) {
		Runnable notify = null;
		synchronized (this) {
			if (json.equals(lastWrittenJson))
				return;

			applyJson(json);
			notify = importListener;
			log.debug("Reloaded {} model light profiles from {}", profiles.size(), MODEL_LIGHTS_PATH);
		}
		if (notify != null)
			notify.run();
	}

	private String freeKey(String meshKey) {
		String key = meshKey;
		int n = 2;
		while (profiles.containsKey(key))
			key = meshKey + "#" + n++;
		return key;
	}

	private void notifyChanged() {
		revision++;
		if (changeListener != null)
			changeListener.run();
	}

	private String buildJson() {
		Map<String, ModelLightProfile> out = new LinkedHashMap<>();
		profiles.forEach((k, v) -> {
			if (isReservedKey(k))
				return;
			if (v.getVertices() == null || v.getVertices().isEmpty())
				if (v.getTriangles() == null || v.getTriangles().isEmpty())
					return;
			out.put(k, v.copy());
		});

		JsonObject root = new JsonObject();
		root.add("profiles", gson.toJsonTree(out, PROFILE_MAP_TYPE));
		return gson.toJson(root);
	}

	public synchronized ResourcePath saveToDisk() throws IOException {
		String json = buildJson();
		ResourcePath target = MODEL_LIGHTS_PATH.isFileSystemResource()
			? MODEL_LIGHTS_PATH
			: getExportPath();

		if (!target.isFileSystemResource())
			throw new IOException("Cannot write model lights to " + target);

		target.mkdirs();
		target.writeString(json);
		lastWrittenJson = json;
		return target;
	}
}
