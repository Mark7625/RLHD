package rs117.hd.particles;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import rs117.hd.HdPluginConfig;
import rs117.hd.scene.GamevalManager;
import rs117.hd.utils.FileWatcher;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.utils.ResourcePath.path;

/**
 * Persistent emitter profiles. Each profile targets a mesh piece by topology
 * signature (see {@link ModelSnapshot.Piece}) and stores piece-local vertex
 * indices, so emitters survive model recomposition. Several profiles may
 * target the same signature, each optionally gated on worn item IDs, so
 * recolored variants of one model can carry different particles.
 *
 * Thread-safe: mutated from the Swing EDT (viewer clicks, sidebar actions)
 * and read from the client thread.
 */
@Slf4j
class EmitterStore
{
	static final ResourcePath EMITTERS_PATH = Props
		.getFile("rlhd.particles-emitters-path", () -> path(EmitterStore.class, "emitters.json"));
	static final ResourcePath FOLDERS_PATH = Props
		.getFile("rlhd.particles-folders-path", () -> path(EmitterStore.class, "folders.json"));
	static final ResourcePath DEFINITIONS_PATH = Props
		.getFile("rlhd.particles-definitions-path", () -> path(EmitterStore.class, "definitions.json"));

	private static final String CONFIG_KEY = "particleEmitters";
	private static final String FOLDERS_KEY = "pieceFolders";
	private static final String DEFINITIONS_KEY = "particleDefinitions";
	private static final String LEGACY_CONFIG_KEY = "pieceProfiles";
	private static final Type EMITTER_TYPE = new TypeToken<Map<String, ParticleEmitter>>()
	{
	}.getType();
	private static final Type MAP_TYPE = new TypeToken<Map<String, EmitterProfile>>()
	{
	}.getType();
	private static final Type FOLDERS_TYPE = new TypeToken<Map<String, ProfileFolder>>()
	{
	}.getType();
	private static final Type DEFINITIONS_TYPE = new TypeToken<Map<String, ParticleDefinition>>()
	{
	}.getType();

	/**
	 * A consistent (profiles, folders) pair - both the merged result and the
	 * atomic read snapshot the emission gates and sidebar resolve against, so a
	 * profile is never scored against a folder map from a different instant.
	 */
	static final class Snapshot
	{
		final Map<String, EmitterProfile> profiles;
		final Map<String, ProfileFolder> folders;
		final Map<String, ParticleDefinition> definitions;

		Snapshot(Map<String, EmitterProfile> profiles, Map<String, ProfileFolder> folders,
			Map<String, ParticleDefinition> definitions)
		{
			this.profiles = profiles;
			this.folders = folders;
			this.definitions = definitions;
		}
	}

	private final ConfigManager configManager;
	private final Gson gson;
	private final GamevalManager gamevalManager;
	/**
	 * In developer mode the config is the source of truth so authoring edits
	 * persist across reloads; shipped users instead take all profile content
	 * from the bundled pack and contribute only their enable/disable toggles,
	 * so preset style updates and new fields reach them on every update.
	 */
	private final boolean developerMode;
	private final Map<String, EmitterProfile> profiles = new LinkedHashMap<>();
	private final Map<String, ProfileFolder> folders = new LinkedHashMap<>();
	private final Map<String, ParticleDefinition> definitions = new LinkedHashMap<>();
	private int revision;

	/**
	 * Notified after any mutation; invoked on the calling thread.
	 */
	@Setter
	private Runnable changeListener;

	@Nullable
	private FileWatcher.UnregisterCallback emittersWatcher;
	@Nullable
	private FileWatcher.UnregisterCallback foldersWatcher;
	@Nullable
	private FileWatcher.UnregisterCallback definitionsWatcher;

	EmitterStore(ConfigManager configManager, Gson gson, boolean developerMode, GamevalManager gamevalManager)
	{
		this.configManager = configManager;
		this.gson = gson;
		this.developerMode = developerMode;
		this.gamevalManager = gamevalManager;
	}

	synchronized void load()
	{
		profiles.clear();
		folders.clear();
		definitions.clear();
		String savedEmitters = configManager.getConfiguration(HdPluginConfig.CONFIG_GROUP, CONFIG_KEY);
		if (savedEmitters == null || savedEmitters.isEmpty())
		{
			savedEmitters = configManager.getConfiguration(HdPluginConfig.CONFIG_GROUP, LEGACY_CONFIG_KEY);
		}
		try (GamevalManager.Handle handle = gamevalManager.obtainHandle())
		{
			Snapshot merged = mergeAll(
				savedEmitters,
				configManager.getConfiguration(HdPluginConfig.CONFIG_GROUP, FOLDERS_KEY),
				configManager.getConfiguration(HdPluginConfig.CONFIG_GROUP, DEFINITIONS_KEY));
			profiles.putAll(merged.profiles);
			folders.putAll(merged.folders);
			definitions.putAll(merged.definitions);
		}
	}

	/**
	 * Combine a user's saved config with the bundled preset pack and run
	 * migrations. Package-private so the contract can be tested without a
	 * ConfigManager.
	 *
	 * Developer mode: the config is the source of truth (authoring edits
	 * persist); the bundle only seeds a fresh, empty config, and presets are
	 * updated by re-exporting the config to emitters.json.
	 *
	 * Shipped users: the bundle is the source of truth for CONTENT - every
	 * emitter is taken wholesale from emitters.json, so style changes and new
	 * fields ship on update - and only the user's enable/disable toggle is
	 * carried over from their saved config (the one thing they can change).
	 * A profile the bundle no longer contains is kept from the saved config.
	 */
	Map<String, EmitterProfile> mergeWithBundle(@Nullable String savedJson)
	{
		return mergeAll(savedJson, null).profiles;
	}

	/**
	 * Merge profiles and folders together and reconcile them: nulls any
	 * folderId that no longer resolves and dissolves folders left with fewer
	 * than two members, so the returned pair always satisfies the folder
	 * invariant. Package-private for testing.
	 */
	Snapshot mergeAll(@Nullable String savedProfilesJson, @Nullable String savedFoldersJson)
	{
		return mergeAll(savedProfilesJson, savedFoldersJson, null);
	}

	Snapshot mergeAll(@Nullable String savedProfilesJson, @Nullable String savedFoldersJson,
		@Nullable String savedDefinitionsJson)
	{
		Map<String, EmitterProfile> savedParsed = parseEmitters(savedProfilesJson);
		if (savedParsed == null)
		{
			savedParsed = parseLegacyProfiles(savedProfilesJson);
		}
		final Map<String, EmitterProfile> saved = savedParsed;
		Map<String, EmitterProfile> bundledParsed = parseEmitters(loadBundledEmitters());
		if (bundledParsed == null)
		{
			bundledParsed = parseLegacyProfiles(loadBundledPresets());
		}
		final Map<String, EmitterProfile> bundled = bundledParsed;

		Map<String, EmitterProfile> result = new LinkedHashMap<>();
		if (developerMode)
		{
			if (saved != null && !saved.isEmpty())
			{
				result.putAll(saved);
			}
			if (bundled != null)
			{
				bundled.forEach(result::putIfAbsent);
			}
		}
		else
		{
			if (bundled != null)
			{
				bundled.forEach((key, profile) ->
				{
					EmitterProfile prior = saved == null ? null : saved.get(key);
					// Content from the bundle; the user's toggle carries over only
					// for profiles the bundle keeps ungrouped - foldered children
					// are dev-controlled (users toggle the folder, not the child).
					if (prior != null && profile.getFolderId() == null)
					{
						profile.setEnabled(prior.isEnabled());
					}
					result.put(key, profile);
				});
			}
			if (saved != null)
			{
				// Anything the bundle dropped (or the user created) survives
				saved.forEach(result::putIfAbsent);
			}
		}

		result.forEach((key, profile) ->
		{
			// Migration: profiles saved before item variants used their
			// signature as the map key
			if (profile.getSignature() == null)
			{
				profile.setSignature(key);
			}
			// Migration: single frame window fields became a range list
			if ((profile.getAnimFrames() == null || profile.getAnimFrames().isEmpty())
				&& (profile.getAnimFrameStart() >= 0 || profile.getAnimFrameEnd() >= 0))
			{
				int start = Math.max(0, profile.getAnimFrameStart());
				int end = profile.getAnimFrameEnd() < 0 ? 999 : profile.getAnimFrameEnd();
				profile.setAnimFrames(start + "-" + end);
				profile.setAnimFrameStart(-1);
				profile.setAnimFrameEnd(-1);
			}
			else if (profile.getAnimFrames() == null)
			{
				profile.setAnimFrames("");
			}
			// Migration: feather flag became a strength
			if (profile.isFeather() && profile.getFeatherStrength() == 0)
			{
				profile.setFeatherStrength(2);
				profile.setFeather(false);
			}
			// Migration: profiles saved before projectile targets
			if (profile.getTargetType() == null)
			{
				profile.setTargetType(EmitterProfile.TARGET_PLAYER);
			}
			// Migration: dynamic lifetime flag became a movement percent
			if (profile.isDynamicLifetime())
			{
				if (profile.getMovementLifetime() == 100)
				{
					profile.setMovementLifetime(50);
				}
				profile.setDynamicLifetime(false);
			}
			// Migration: profiles saved before shapes have no shape
			if (profile.getShape() == null)
			{
				profile.setShape(Shape.DEFAULT);
			}
			// Migration: emit scale is a percent defaulting to 100; a missing
			// key deserializes to 0, which would collapse emitters to a point
			if (profile.getEmitScale() <= 0)
			{
				profile.setEmitScale(100);
			}
			// Migration: end colour defaults to the start colour (no gradient);
			// a missing key deserializes to 0, which would fade to transparent
			if (profile.getColorEnd() == 0)
			{
				profile.setColorEnd(profile.getColor());
			}
			if (profile.getTextureFile() == null)
			{
				profile.setTextureFile("");
			}
			if (profile.getFlipbookColumns() < 0)
			{
				profile.setFlipbookColumns(0);
			}
			if (profile.getFlipbookRows() < 0)
			{
				profile.setFlipbookRows(0);
			}
			if (profile.getFlipbookMode() != null && profile.getFlipbookMode().isEmpty())
			{
				profile.setFlipbookMode(null);
			}
			if (!ParticleIds.hasProperEmitterName(profile.getName(), key, profile.getSignature()))
			{
				profile.setName(ParticleIds.displayName(ParticleIds.emitterLabelKey(key, profile)));
			}
		});

		Map<String, ParticleDefinition> definitionResult = mergeDefinitions(savedDefinitionsJson);
		reconcileDefinitions(result, definitionResult);

		Map<String, ProfileFolder> folderResult = mergeFolders(savedFoldersJson);
		reconcileFolders(result, folderResult);
		return new Snapshot(result, folderResult, definitionResult);
	}

	private Map<String, ParticleDefinition> mergeDefinitions(@Nullable String savedDefinitionsJson)
	{
		Map<String, ParticleDefinition> saved = parseDefinitions(savedDefinitionsJson);
		Map<String, ParticleDefinition> bundled = parseDefinitions(loadBundledDefinitions());
		Map<String, ParticleDefinition> result = new LinkedHashMap<>();
		if (developerMode)
		{
			if (saved != null && !saved.isEmpty())
			{
				result.putAll(saved);
			}
			if (bundled != null)
			{
				bundled.forEach(result::putIfAbsent);
			}
		}
		else
		{
			if (bundled != null)
			{
				result.putAll(bundled);
			}
			if (saved != null)
			{
				saved.forEach(result::putIfAbsent);
			}
		}
		return result;
	}

	private static void reconcileDefinitions(Map<String, EmitterProfile> profiles,
		Map<String, ParticleDefinition> definitions)
	{
		for (Map.Entry<String, EmitterProfile> entry : profiles.entrySet())
		{
			String profileKey = entry.getKey();
			EmitterProfile profile = entry.getValue();
			String defId = profile.getDefinitionId();
			if (defId == null || defId.isEmpty())
			{
				defId = ParticleIds.definitionIdFor(profile, definitions.keySet());
				profile.setDefinitionId(defId);
			}
			ParticleDefinition definition = definitions.get(defId);
			if (definition == null)
			{
				definition = ParticleDefinition.fromProfile(profile);
				definitions.put(defId, definition);
			}
			else
			{
				definition.applyToProfile(profile);
			}
		}
	}

	static String defaultDefinitionId(EmitterProfile profile, String profileKey)
	{
		return ParticleIds.definitionIdFor(profile, Set.of());
	}

	private void save()
	{
		try (GamevalManager.Handle handle = gamevalManager.obtainHandle())
		{
			String emittersJson = EmitterManifest.serialize(gson, profiles, folders);
			configManager.setConfiguration(HdPluginConfig.CONFIG_GROUP, CONFIG_KEY, emittersJson);
			configManager.setConfiguration(HdPluginConfig.CONFIG_GROUP, FOLDERS_KEY, gson.toJson(folders, FOLDERS_TYPE));
			configManager.setConfiguration(HdPluginConfig.CONFIG_GROUP, DEFINITIONS_KEY,
				gson.toJson(definitions, DEFINITIONS_TYPE));
		}
		revision++;
		if (changeListener != null)
		{
			changeListener.run();
		}
	}

	/**
	 * Merge saved folders with the bundle on the same terms as profiles:
	 * dev mode's config wins; shipped users take folder content from the bundle
	 * and keep only their own enable toggle.
	 */
	private Map<String, ProfileFolder> mergeFolders(@Nullable String savedFoldersJson)
	{
		Map<String, ProfileFolder> saved = parseFolders(savedFoldersJson);
		Map<String, ProfileFolder> bundled = parseFolders(loadBundledFolders());

		Map<String, ProfileFolder> result = new LinkedHashMap<>();
		if (developerMode)
		{
			if (saved != null && !saved.isEmpty())
			{
				result.putAll(saved);
			}
			else if (bundled != null)
			{
				result.putAll(bundled);
			}
		}
		else
		{
			if (bundled != null)
			{
				bundled.forEach((id, folder) ->
				{
					ProfileFolder prior = saved == null ? null : saved.get(id);
					if (prior != null)
					{
						// Content (name, wip) from the bundle, toggle from the user
						folder.setEnabled(prior.isEnabled());
					}
					result.put(id, folder);
				});
			}
			if (saved != null)
			{
				saved.forEach(result::putIfAbsent);
			}
		}
		return result;
	}

	/**
	 * Enforce the folder invariant on a merged pair: null any folderId with no
	 * matching folder, then drop any folder left with fewer than two members
	 * (nulling those members). Runs in both modes; defensive in dev mode where
	 * the mutators already hold the invariant.
	 */
	private static void reconcileFolders(Map<String, EmitterProfile> profiles,
		Map<String, ProfileFolder> folders)
	{
		for (EmitterProfile profile : profiles.values())
		{
			if (profile.getFolderId() != null && !folders.containsKey(profile.getFolderId()))
			{
				profile.setFolderId(null);
			}
		}
		Map<String, Integer> counts = new HashMap<>();
		for (EmitterProfile profile : profiles.values())
		{
			if (profile.getFolderId() != null)
			{
				counts.merge(profile.getFolderId(), 1, Integer::sum);
			}
		}
		folders.keySet().removeIf(id -> counts.getOrDefault(id, 0) < 2);
		for (EmitterProfile profile : profiles.values())
		{
			if (profile.getFolderId() != null && !folders.containsKey(profile.getFolderId()))
			{
				profile.setFolderId(null);
			}
		}
	}

	@Nullable
	private Map<String, EmitterProfile> parseEmitters(@Nullable String json)
	{
		if (json == null || json.isEmpty())
		{
			return null;
		}
		Map<String, ProfileFolder> folderContext = folders.isEmpty() ? parseFolders(loadBundledFolders()) : folders;
		Map<String, EmitterProfile> manifest = EmitterManifest.parse(json, folderContext);
		if (manifest != null)
		{
			manifest.forEach((key, profile) ->
			{
				String display = ParticleIds.displayName(ParticleIds.emitterLabelKey(key, profile));
				if (!ParticleIds.hasProperEmitterName(profile.getName(), key, profile.getSignature()))
				{
					profile.setName(display);
				}
			});
			return manifest;
		}
		try
		{
			Map<String, ParticleEmitter> emitters = gson.fromJson(json, EMITTER_TYPE);
			if (emitters == null)
			{
				return null;
			}
			Map<String, EmitterProfile> result = new LinkedHashMap<>();
			emitters.forEach((key, emitter) ->
			{
				if (emitter != null)
				{
					EmitterProfile profile = emitter.toProfile();
					String display = ParticleIds.displayName(ParticleIds.emitterLabelKey(key, profile));
					if (!ParticleIds.hasProperEmitterName(profile.getName(), key, profile.getSignature()))
					{
						profile.setName(display);
					}
					result.put(key, profile);
				}
			});
			return result;
		}
		catch (Exception e)
		{
			return null;
		}
	}

	@Nullable
	private Map<String, EmitterProfile> parseLegacyProfiles(@Nullable String json)
	{
		return parse(json);
	}

	/**
	 * Parse a profile-map JSON string, or null on empty/blank/malformed.
	 */
	@Nullable
	private Map<String, EmitterProfile> parse(@Nullable String json)
	{
		if (json == null || json.isEmpty())
		{
			return null;
		}
		try
		{
			return gson.fromJson(json, MAP_TYPE);
		}
		catch (Exception e)
		{
			log.warn("Failed to parse emitter profiles", e);
			return null;
		}
	}

	@Nullable
	private Map<String, ProfileFolder> parseFolders(@Nullable String json)
	{
		if (json == null || json.isEmpty())
		{
			return null;
		}
		try
		{
			return gson.fromJson(json, FOLDERS_TYPE);
		}
		catch (Exception e)
		{
			log.warn("Failed to parse profile folders", e);
			return null;
		}
	}

	@Nullable
	private Map<String, ParticleDefinition> parseDefinitions(@Nullable String json)
	{
		if (json == null || json.isEmpty())
		{
			return null;
		}
		try
		{
			return gson.fromJson(json, DEFINITIONS_TYPE);
		}
		catch (Exception e)
		{
			log.warn("Failed to parse particle definitions", e);
			return null;
		}
	}

	@Nullable
	private static String loadBundledEmitters()
	{
		return loadBundledJson(EMITTERS_PATH, "/rs117/hd/particles/emitters.json", "emitters");
	}

	@Nullable
	private static String loadBundledPresets()
	{
		return loadResource("/rs117/hd/particles/presets.json", "presets");
	}

	@Nullable
	private static String loadBundledFolders()
	{
		return loadBundledJson(FOLDERS_PATH, "/rs117/hd/particles/folders.json", "folders");
	}

	@Nullable
	private static String loadBundledDefinitions()
	{
		return loadBundledJson(DEFINITIONS_PATH, "/rs117/hd/particles/definitions.json", "definitions");
	}

	@Nullable
	private static String loadBundledJson(ResourcePath diskPath, String classpathResource, String what)
	{
		try
		{
			return diskPath.loadString();
		}
		catch (IOException e)
		{
			return loadResource(classpathResource, what);
		}
	}

	void startWatching(ClientThread clientThread)
	{
		if (!developerMode)
		{
			return;
		}
		Runnable reload = () -> clientThread.invoke(this::reloadFromDiskFiles);
		emittersWatcher = EMITTERS_PATH.watch(path -> reload.run());
		foldersWatcher = FOLDERS_PATH.watch(path -> reload.run());
		definitionsWatcher = DEFINITIONS_PATH.watch(path -> reload.run());
	}

	void shutDownWatching()
	{
		if (emittersWatcher != null)
		{
			emittersWatcher.unregister();
			emittersWatcher = null;
		}
		if (foldersWatcher != null)
		{
			foldersWatcher.unregister();
			foldersWatcher = null;
		}
		if (definitionsWatcher != null)
		{
			definitionsWatcher.unregister();
			definitionsWatcher = null;
		}
	}

	synchronized void reloadFromDiskFiles()
	{
		if (!developerMode)
		{
			return;
		}
		profiles.clear();
		folders.clear();
		definitions.clear();
		try (GamevalManager.Handle handle = gamevalManager.obtainHandle())
		{
			Snapshot merged = mergeAll(
				loadBundledJson(EMITTERS_PATH, "/rs117/hd/particles/emitters.json", "emitters"),
				loadBundledJson(FOLDERS_PATH, "/rs117/hd/particles/folders.json", "folders"),
				loadBundledJson(DEFINITIONS_PATH, "/rs117/hd/particles/definitions.json", "definitions"));
			profiles.putAll(merged.profiles);
			folders.putAll(merged.folders);
			definitions.putAll(merged.definitions);
			revision++;
			if (changeListener != null)
			{
				changeListener.run();
			}
			log.debug("Reloaded particle configs from disk ({} emitters, {} definitions)",
				profiles.size(), definitions.size());
		}
	}

	@Nullable
	private static String loadResource(String path, String what)
	{
		try (InputStream in = EmitterStore.class.getResourceAsStream(path))
		{
			if (in == null)
			{
				return null;
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException e)
		{
			log.warn("Failed to read bundled {}", what, e);
			return null;
		}
	}

	/**
	 * Bumped on every mutation; lets the client thread cheaply detect that
	 * emitters need re-resolving against the current model.
	 */
	synchronized int getRevision()
	{
		return revision;
	}

	/**
	 * @return the key of some existing profile for this signature, or a newly
	 * created one
	 */
	synchronized String ensureProfileFor(String signature, String defaultName)
	{
		for (Map.Entry<String, EmitterProfile> entry : profiles.entrySet())
		{
			if (signature.equals(entry.getValue().getSignature())
				&& EmitterProfile.TARGET_PLAYER.equals(entry.getValue().getTargetType()))
			{
				return entry.getKey();
			}
		}
		EmitterProfile profile = new EmitterProfile(defaultName);
		profile.setSignature(signature);
		String key = allocateKey(profile);
		profiles.put(key, profile);
		save();
		return key;
	}

	/**
	 * Clone a profile (vertices, style, filter) under a new key, e.g. to give
	 * another item variant of the same mesh its own particles.
	 *
	 * @return the new profile's key, or null
	 */
	@Nullable
	synchronized String duplicate(String profileKey)
	{
		EmitterProfile source = profiles.get(profileKey);
		if (source == null)
		{
			return null;
		}
		EmitterProfile copy = source.copy();
		copy.setName(source.getName() + " copy");
		String key = allocateKey(copy);
		profiles.put(key, copy);
		save();
		return key;
	}

	/**
	 * @return the key of an existing object profile for this piece signature
	 * on this object ID, or a newly created one
	 */
	synchronized String ensureObjectPieceProfile(String signature, String defaultName, int objectId)
	{
		for (Map.Entry<String, EmitterProfile> entry : profiles.entrySet())
		{
			EmitterProfile profile = entry.getValue();
			if (profile.isObjectTarget() && profile.getObjectId() == objectId
				&& signature.equals(profile.getSignature()))
			{
				return entry.getKey();
			}
		}
		EmitterProfile profile = new EmitterProfile(defaultName);
		profile.setTargetType(EmitterProfile.TARGET_OBJECT);
		profile.setSignature(signature);
		profile.setObjectId(objectId);
		String key = allocateKey(profile);
		profiles.put(key, profile);
		save();
		return key;
	}

	/**
	 * @return the key of an existing NPC profile for this piece signature on
	 * this NPC ID, or a newly created one
	 */
	synchronized String ensureNpcPieceProfile(String signature, String defaultName, int npcId)
	{
		for (Map.Entry<String, EmitterProfile> entry : profiles.entrySet())
		{
			EmitterProfile profile = entry.getValue();
			if (profile.isNpcTarget() && profile.getNpcId() == npcId
				&& signature.equals(profile.getSignature()))
			{
				return entry.getKey();
			}
		}
		EmitterProfile profile = new EmitterProfile(defaultName);
		profile.setTargetType(EmitterProfile.TARGET_NPC);
		profile.setSignature(signature);
		profile.setNpcId(npcId);
		String key = allocateKey(profile);
		profiles.put(key, profile);
		save();
		return key;
	}

	/**
	 * The graphic profile for this piece signature on this spot anim ID. A
	 * point-based profile (no signature yet) for the same ID is upgraded in
	 * place rather than duplicated.
	 */
	synchronized String ensureGraphicPieceProfile(String signature, String defaultName, int graphicId)
	{
		String pointKey = null;
		for (Map.Entry<String, EmitterProfile> entry : profiles.entrySet())
		{
			EmitterProfile profile = entry.getValue();
			if (!profile.isGraphicTarget() || profile.getGraphicId() != graphicId)
			{
				continue;
			}
			if (signature.equals(profile.getSignature()))
			{
				return entry.getKey();
			}
			if (profile.getSignature() == null && pointKey == null)
			{
				pointKey = entry.getKey();
			}
		}
		if (pointKey != null)
		{
			profiles.get(pointKey).setSignature(signature);
			save();
			return pointKey;
		}
		EmitterProfile profile = new EmitterProfile(defaultName);
		profile.setTargetType(EmitterProfile.TARGET_GRAPHIC);
		profile.setGraphicId(graphicId);
		profile.setSignature(signature);
		String key = allocateKey(profile);
		profiles.put(key, profile);
		save();
		return key;
	}

	/**
	 * @return the key of an existing graphic profile for this spot anim ID,
	 * or a newly created one
	 */
	synchronized String ensureGraphicProfile(int graphicId, String defaultName)
	{
		for (Map.Entry<String, EmitterProfile> entry : profiles.entrySet())
		{
			EmitterProfile profile = entry.getValue();
			if (profile.isGraphicTarget() && profile.getGraphicId() == graphicId)
			{
				return entry.getKey();
			}
		}
		EmitterProfile profile = new EmitterProfile(defaultName);
		profile.setTargetType(EmitterProfile.TARGET_GRAPHIC);
		profile.setGraphicId(graphicId);
		profile.setRiseSpeed(20);
		String key = allocateKey(profile);
		profiles.put(key, profile);
		save();
		return key;
	}

	/**
	 * @return the key of an existing profile for this projectile ID, or a
	 * newly created one with trail-friendly defaults
	 */
	synchronized String ensureProjectileProfile(int projectileId, String defaultName)
	{
		for (Map.Entry<String, EmitterProfile> entry : profiles.entrySet())
		{
			EmitterProfile profile = entry.getValue();
			if (profile.isProjectileTarget() && profile.getProjectileId() == projectileId)
			{
				return entry.getKey();
			}
		}
		EmitterProfile profile = new EmitterProfile(defaultName);
		profile.setTargetType(EmitterProfile.TARGET_PROJECTILE);
		profile.setProjectileId(projectileId);
		// Trails are what projectile particles are usually for
		profile.setTrailDensity(40);
		profile.setRiseSpeed(0);
		profile.setSpreadSpeed(4);
		String key = allocateKey(profile);
		profiles.put(key, profile);
		save();
		return key;
	}

	/**
	 * @return the key of an existing profile covering this area set, or a
	 * newly created one with weather-friendly fall defaults
	 */
	synchronized String ensureWeatherProfile(List<String> weatherAreas, String defaultName)
	{
		for (Map.Entry<String, EmitterProfile> entry : profiles.entrySet())
		{
			EmitterProfile profile = entry.getValue();
			if (profile.isWeatherTarget()
				&& profile.getWeatherAreas() != null
				&& profile.getWeatherAreas().equals(weatherAreas))
			{
				return entry.getKey();
			}
		}
		EmitterProfile profile = new EmitterProfile(defaultName);
		profile.setTargetType(EmitterProfile.TARGET_WEATHER);
		profile.setWeatherAreas(new ArrayList<>(weatherAreas));
		profile.setWeatherParticlesPerTile(10f);
		profile.setRiseSpeed(-26);
		profile.setGravity(32);
		profile.setSpreadSpeed(4);
		String key = allocateKey(profile);
		profiles.put(key, profile);
		save();
		return key;
	}

	private String allocateKey(EmitterProfile profile)
	{
		return freeKey(ParticleIds.emitterKeyFor(profile, profiles.keySet()));
	}

	private String freeKey(String base)
	{
		String key = base;
		int n = 2;
		while (profiles.containsKey(key))
		{
			key = base + "_" + n++;
		}
		return key;
	}

	/**
	 * @return a copy of the piece-local vertex set of a profile
	 */
	synchronized Set<Integer> vertexSet(String profileKey)
	{
		EmitterProfile profile = profiles.get(profileKey);
		return profile == null ? Set.of() : Set.copyOf(profile.getVertices());
	}

	/**
	 * Add or remove a piece-local vertex on an existing profile.
	 */
	synchronized void toggleVertex(String profileKey, int localVertex)
	{
		EmitterProfile profile = profiles.get(profileKey);
		if (profile == null)
		{
			return;
		}
		if (!profile.getVertices().remove(localVertex))
		{
			profile.getVertices().add(localVertex);
		}
		save();
	}

	/**
	 * Bulk-add piece-local vertices (box selection). Persists once.
	 */
	synchronized void addVertices(String profileKey, Collection<Integer> localVertices)
	{
		EmitterProfile profile = profiles.get(profileKey);
		if (profile != null && profile.getVertices().addAll(localVertices))
		{
			save();
		}
	}

	/**
	 * Bulk-remove piece-local vertices (box selection). Persists once.
	 */
	synchronized void removeVertices(String profileKey, Collection<Integer> localVertices)
	{
		EmitterProfile profile = profiles.get(profileKey);
		if (profile != null && profile.getVertices().removeAll(localVertices))
		{
			save();
		}
	}

	/**
	 * Bulk-add piece-local faces (triangle selection). Persists once.
	 */
	synchronized void addFaces(String profileKey, Collection<Integer> localFaces)
	{
		EmitterProfile profile = profiles.get(profileKey);
		if (profile != null && profile.getFaces().addAll(localFaces))
		{
			save();
		}
	}

	/**
	 * Bulk-remove piece-local faces (triangle selection). Persists once.
	 */
	synchronized void removeFaces(String profileKey, Collection<Integer> localFaces)
	{
		EmitterProfile profile = profiles.get(profileKey);
		if (profile != null && profile.getFaces().removeAll(localFaces))
		{
			save();
		}
	}

	/**
	 * Copy particle style into the profile's linked definition.
	 */
	synchronized void updateStyle(String profileKey, EmitterProfile settingsSource)
	{
		EmitterProfile profile = profiles.get(profileKey);
		if (profile == null)
		{
			return;
		}
		String defId = profile.getDefinitionId();
		if (defId == null || defId.isEmpty())
		{
			defId = defaultDefinitionId(profile, profileKey);
			profile.setDefinitionId(defId);
		}
		ParticleDefinition definition = definitions.computeIfAbsent(defId,
			id -> new ParticleDefinition());
		definition.copyFieldsFrom(ParticleDefinition.fromProfile(settingsSource));
		definition.applyToProfile(profile);
		profile.setItemIds(new HashSet<>(settingsSource.getItemIds()));
		profile.setAnimationIds(new HashSet<>(settingsSource.getAnimationIds()));
		profile.setAnimFrames(settingsSource.getAnimFrames());
		profile.setEmitScale(settingsSource.getEmitScale());
		profile.setFeatherStrength(settingsSource.getFeatherStrength());
		profile.setInterpolation(settingsSource.getInterpolation());
		profile.setDepthBias(settingsSource.getDepthBias());
		profile.setOffsetX(settingsSource.getOffsetX());
		profile.setOffsetY(settingsSource.getOffsetY());
		profile.setOffsetZ(settingsSource.getOffsetZ());
		save();
	}

	/**
	 * Persist emitter placement and gating without touching the linked
	 * particle definition.
	 */
	synchronized void updateEmitter(String profileKey, EmitterProfile source)
	{
		EmitterProfile profile = profiles.get(profileKey);
		if (profile == null || source == null)
		{
			return;
		}
		if (source.getDefinitionId() != null && !source.getDefinitionId().isEmpty())
		{
			profile.setDefinitionId(source.getDefinitionId());
		}
		profile.setItemIds(new HashSet<>(source.getItemIds()));
		profile.setAnimationIds(new HashSet<>(source.getAnimationIds()));
		profile.setAnimFrames(source.getAnimFrames());
		profile.setEmitScale(source.getEmitScale());
		profile.setFeatherStrength(source.getFeatherStrength());
		profile.setInterpolation(source.getInterpolation());
		profile.setDepthBias(source.getDepthBias());
		profile.setOffsetX(source.getOffsetX());
		profile.setOffsetY(source.getOffsetY());
		profile.setOffsetZ(source.getOffsetZ());
		save();
	}

	synchronized void updateDefinition(String definitionId, ParticleDefinition source)
	{
		ParticleDefinition definition = definitions.get(definitionId);
		if (definition == null || source == null)
		{
			return;
		}
		definition.copyFieldsFrom(source);
		for (EmitterProfile profile : profiles.values())
		{
			if (definitionId.equals(profile.getDefinitionId()))
			{
				definition.applyToProfile(profile);
			}
		}
		save();
	}

	@Nullable
	synchronized ParticleDefinition definition(String definitionId)
	{
		ParticleDefinition definition = definitions.get(definitionId);
		return definition == null ? null : definition.copy();
	}

	/**
	 * Re-attach a profile to a different mesh piece, keeping its style, name
	 * and item filter but clearing its vertices (local indices are meaningless
	 * on another piece). Recovery path for profiles orphaned by signature
	 * changes, and for copying a style setup onto new gear.
	 */
	synchronized void rebind(String profileKey, String newSignature)
	{
		EmitterProfile profile = profiles.get(profileKey);
		if (profile == null || newSignature.equals(profile.getSignature()))
		{
			return;
		}
		profile.setSignature(newSignature);
		profile.getVertices().clear();
		profile.getFaces().clear();
		save();
	}

	synchronized void rename(String profileKey, String name)
	{
		EmitterProfile profile = profiles.get(profileKey);
		if (profile != null && name != null && !name.trim().isEmpty())
		{
			profile.setName(name.trim());
			save();
		}
	}

	synchronized void setEnabled(String profileKey, boolean enabled)
	{
		EmitterProfile profile = profiles.get(profileKey);
		if (profile != null && profile.isEnabled() != enabled)
		{
			profile.setEnabled(enabled);
			save();
		}
	}

	/**
	 * Mark a profile work-in-progress (or ship-ready), a developer-only flag
	 * that hides and force-disables it for shipped users. Persisted so it ships
	 * to presets.json.
	 */
	synchronized void setWip(String profileKey, boolean wip)
	{
		EmitterProfile profile = profiles.get(profileKey);
		if (profile != null && profile.isWip() != wip)
		{
			profile.setWip(wip);
			save();
		}
	}

	/**
	 * Point a profile at another profile's particle definition, or copy inline
	 * style when the source has no linked definition yet.
	 */
	synchronized void pasteStyle(String profileKey, EmitterProfile source)
	{
		EmitterProfile profile = profiles.get(profileKey);
		if (profile == null)
		{
			return;
		}
		String sourceDefId = source.getDefinitionId();
		if (sourceDefId != null && !sourceDefId.isEmpty() && definitions.containsKey(sourceDefId))
		{
			profile.setDefinitionId(sourceDefId);
		}
		else
		{
			String defId = profile.getDefinitionId();
			if (defId == null || defId.isEmpty())
			{
				defId = defaultDefinitionId(profile, profileKey);
				profile.setDefinitionId(defId);
			}
			ParticleDefinition copied = ParticleDefinition.fromProfile(source);
			definitions.put(defId, copied);
		}
		ParticleDefinition definition = definitions.get(profile.getDefinitionId());
		if (definition != null)
		{
			definition.applyToProfile(profile);
		}
		save();
	}

	synchronized void setDefinitionId(String profileKey, String definitionId)
	{
		EmitterProfile profile = profiles.get(profileKey);
		if (profile == null || definitionId == null || definitionId.isEmpty())
		{
			return;
		}
		ParticleDefinition definition = definitions.get(definitionId);
		if (definition == null)
		{
			return;
		}
		profile.setDefinitionId(definitionId);
		definition.applyToProfile(profile);
		save();
	}

	void hydrateProfileStyle(EmitterProfile profile)
	{
		String defId = profile.getDefinitionId();
		if (defId == null)
		{
			return;
		}
		ParticleDefinition definition = definitions.get(defId);
		if (definition != null)
		{
			definition.applyToProfile(profile);
		}
	}

	@Nullable
	synchronized ParticleDefinition definitionForProfile(String profileKey)
	{
		EmitterProfile profile = profiles.get(profileKey);
		if (profile == null)
		{
			return null;
		}
		String defId = profile.getDefinitionId();
		if (defId == null)
		{
			return null;
		}
		return definitions.get(defId);
	}

	synchronized Map<String, ParticleDefinition> snapshotDefinitions()
	{
		Map<String, ParticleDefinition> copy = new LinkedHashMap<>();
		definitions.forEach((k, v) -> copy.put(k, v.copy()));
		return copy;
	}

	synchronized List<String> definitionIds()
	{
		return new ArrayList<>(definitions.keySet());
	}

	synchronized void delete(String profileKey)
	{
		EmitterProfile removed = profiles.remove(profileKey);
		if (removed != null)
		{
			if (removed.getFolderId() != null)
			{
				autoDissolve(removed.getFolderId());
			}
			save();
		}
	}

	/**
	 * @return a deep copy of all profiles by key, for the non-gating lookups
	 * (rename/edit dialogs, worn-model scans) that only need profiles
	 */
	synchronized Map<String, EmitterProfile> snapshotAll()
	{
		Map<String, EmitterProfile> copy = new LinkedHashMap<>();
		profiles.forEach((k, v) ->
		{
			EmitterProfile profile = v.copy();
			hydrateProfileStyle(profile);
			copy.put(k, profile);
		});
		return copy;
	}

	/**
	 * @return an atomic deep copy of both maps, for the emission gates and the
	 * sidebar - so a profile is always scored against a coherent folder view
	 */
	synchronized Snapshot snapshot()
	{
		Map<String, EmitterProfile> p = new LinkedHashMap<>();
		profiles.forEach((k, v) ->
		{
			EmitterProfile profile = v.copy();
			hydrateProfileStyle(profile);
			p.put(k, profile);
		});
		Map<String, ProfileFolder> f = new LinkedHashMap<>();
		folders.forEach((k, v) -> f.put(k, v.copy()));
		Map<String, ParticleDefinition> d = new LinkedHashMap<>();
		definitions.forEach((k, v) -> d.put(k, v.copy()));
		return new Snapshot(p, f, d);
	}

	/**
	 * Developer helper: write the live emitters, definitions and folders out as
	 * the bundled pack in a chosen directory.
	 */
	synchronized String exportBundle(File dir) throws IOException
	{
		List<String> problems = new ArrayList<>();
		Map<String, Integer> counts = new HashMap<>();
		for (EmitterProfile profile : profiles.values())
		{
			String folderId = profile.getFolderId();
			if (folderId == null)
			{
				continue;
			}
			if (!folders.containsKey(folderId))
			{
				problems.add("profile \"" + profile.getName() + "\" points at missing folder " + folderId);
			}
			else
			{
				counts.merge(folderId, 1, Integer::sum);
			}
		}
		for (Map.Entry<String, ProfileFolder> entry : folders.entrySet())
		{
			int members = counts.getOrDefault(entry.getKey(), 0);
			if (members < 2)
			{
				problems.add("folder \"" + entry.getValue().getName() + "\" has " + members
					+ " member(s); needs at least 2");
			}
		}
		if (!problems.isEmpty())
		{
			return "Nothing written - fix these first:\n - " + String.join("\n - ", problems);
		}

		try (GamevalManager.Handle handle = gamevalManager.obtainHandle())
		{
			String emittersJson = EmitterManifest.serialize(gson, profiles, folders);
			Files.write(new File(dir, "emitters.json").toPath(),
				emittersJson.getBytes(StandardCharsets.UTF_8));
		}
		Files.write(new File(dir, "folders.json").toPath(),
			gson.toJson(folders, FOLDERS_TYPE).getBytes(StandardCharsets.UTF_8));
		Files.write(new File(dir, "definitions.json").toPath(),
			gson.toJson(definitions, DEFINITIONS_TYPE).getBytes(StandardCharsets.UTF_8));
		return "Wrote " + profiles.size() + " emitters, " + definitions.size() + " definitions and "
			+ folders.size() + " folders to\n" + dir.getAbsolutePath();
	}

	// --- Folders -------------------------------------------------------------

	/**
	 * Group two profiles: if the target is already in a folder the member joins
	 * it, otherwise a new folder is created named after the target. Rejects a
	 * cross-category or self grouping. The member leaves any prior folder.
	 */
	synchronized void createFolder(String targetKey, String memberKey)
	{
		EmitterProfile target = profiles.get(targetKey);
		EmitterProfile member = profiles.get(memberKey);
		if (target == null || member == null || targetKey.equals(memberKey)
			|| !sameCategory(target, member))
		{
			return;
		}
		String folderId = target.getFolderId();
		if (folderId == null)
		{
			folderId = freeFolderId();
			folders.put(folderId, new ProfileFolder(folderId, target.getName()));
			target.setFolderId(folderId);
		}
		moveToFolder(member, folderId);
		save();
	}

	/**
	 * Add a profile to an existing folder (dropping onto its header or a child),
	 * rejecting a cross-category join. The member leaves any prior folder.
	 */
	synchronized void addToFolder(String folderId, String memberKey)
	{
		ProfileFolder folder = folders.get(folderId);
		EmitterProfile member = profiles.get(memberKey);
		if (folder == null || member == null || folderId.equals(member.getFolderId()))
		{
			return;
		}
		EmitterProfile reference = anyMember(folderId);
		if (reference != null && !sameCategory(reference, member))
		{
			return;
		}
		moveToFolder(member, folderId);
		save();
	}

	/**
	 * Orphan one profile back to a loose row; dissolves the folder if it falls
	 * below two members.
	 */
	synchronized void removeFromFolder(String profileKey)
	{
		EmitterProfile profile = profiles.get(profileKey);
		if (profile == null || profile.getFolderId() == null)
		{
			return;
		}
		String folderId = profile.getFolderId();
		profile.setFolderId(null);
		autoDissolve(folderId);
		save();
	}

	/**
	 * Remove a folder, orphaning every member back to a loose row.
	 */
	synchronized void dissolveFolder(String folderId)
	{
		if (folders.remove(folderId) == null)
		{
			return;
		}
		for (EmitterProfile profile : profiles.values())
		{
			if (folderId.equals(profile.getFolderId()))
			{
				profile.setFolderId(null);
			}
		}
		save();
	}

	synchronized void setFolderEnabled(String folderId, boolean enabled)
	{
		ProfileFolder folder = folders.get(folderId);
		if (folder != null && folder.isEnabled() != enabled)
		{
			folder.setEnabled(enabled);
			save();
		}
	}

	synchronized void setFolderWip(String folderId, boolean wip)
	{
		ProfileFolder folder = folders.get(folderId);
		if (folder != null && folder.isWip() != wip)
		{
			folder.setWip(wip);
			save();
		}
	}

	synchronized void renameFolder(String folderId, String name)
	{
		ProfileFolder folder = folders.get(folderId);
		if (folder != null && name != null && !name.trim().isEmpty())
		{
			folder.setName(name.trim());
			save();
		}
	}

	/**
	 * Bulk enable/disable the rendered rows in one write: folder preferences for
	 * folders and profile toggles for loose profiles. Foldered children are
	 * never touched - users control the folder, not the child.
	 */
	synchronized void setEnabledMany(Collection<String> looseProfileKeys,
		Collection<String> folderIds, boolean enabled)
	{
		boolean changed = false;
		for (String key : looseProfileKeys)
		{
			EmitterProfile profile = profiles.get(key);
			if (profile != null && profile.isEnabled() != enabled)
			{
				profile.setEnabled(enabled);
				changed = true;
			}
		}
		for (String id : folderIds)
		{
			ProfileFolder folder = folders.get(id);
			if (folder != null && folder.isEnabled() != enabled)
			{
				folder.setEnabled(enabled);
				changed = true;
			}
		}
		if (changed)
		{
			save();
		}
	}

	private void moveToFolder(EmitterProfile member, String folderId)
	{
		String old = member.getFolderId();
		member.setFolderId(folderId);
		if (old != null && !old.equals(folderId))
		{
			autoDissolve(old);
		}
	}

	/**
	 * Dissolve a folder that has dropped below two members, orphaning any lone
	 * remaining member. Mutates without saving; the caller persists.
	 */
	private void autoDissolve(String folderId)
	{
		int count = 0;
		for (EmitterProfile profile : profiles.values())
		{
			if (folderId.equals(profile.getFolderId()))
			{
				count++;
			}
		}
		if (count < 2)
		{
			folders.remove(folderId);
			for (EmitterProfile profile : profiles.values())
			{
				if (folderId.equals(profile.getFolderId()))
				{
					profile.setFolderId(null);
				}
			}
		}
	}

	@Nullable
	private EmitterProfile anyMember(String folderId)
	{
		for (EmitterProfile profile : profiles.values())
		{
			if (folderId.equals(profile.getFolderId()))
			{
				return profile;
			}
		}
		return null;
	}

	private static boolean sameCategory(EmitterProfile a, EmitterProfile b)
	{
		return Objects.equals(a.getTargetType(), b.getTargetType());
	}

	private String freeFolderId()
	{
		int n = 1;
		while (folders.containsKey("folder:" + n))
		{
			n++;
		}
		return "folder:" + n;
	}
}
