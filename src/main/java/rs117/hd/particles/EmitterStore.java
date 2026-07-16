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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

@Slf4j
class EmitterStore
{
	static final ResourcePath EMITTERS_PATH = Props
		.getFile("rlhd.particles-emitters-path", () -> path(EmitterStore.class, "emitters.json"));
	static final ResourcePath DEFINITIONS_PATH = Props
		.getFile("rlhd.particles-definitions-path", () -> path(EmitterStore.class, "definitions.json"));

	private static final String CONFIG_KEY = "particleEmitters";
	private static final String DEFINITIONS_KEY = "particleDefinitions";
	private static final String LEGACY_CONFIG_KEY = "pieceProfiles";
	private static final Type EMITTER_TYPE = new TypeToken<Map<String, ParticleEmitter>>()
	{
	}.getType();
	private static final Type MAP_TYPE = new TypeToken<Map<String, EmitterProfile>>()
	{
	}.getType();
	private static final Type DEFINITIONS_TYPE = new TypeToken<Map<String, ParticleDefinition>>()
	{
	}.getType();

	static final class Snapshot
	{
		final Map<String, EmitterProfile> profiles;
		final Map<String, ParticleDefinition> definitions;

		Snapshot(Map<String, EmitterProfile> profiles, Map<String, ParticleDefinition> definitions)
		{
			this.profiles = profiles;
			this.definitions = definitions;
		}
	}

	private final ConfigManager configManager;
	private final Gson gson;
	private final GamevalManager gamevalManager;

	private final boolean developerMode;
	private final Map<String, EmitterProfile> profiles = new LinkedHashMap<>();
	private final Map<String, ParticleDefinition> definitions = new LinkedHashMap<>();
	private int revision;

	@Setter
	private Runnable changeListener;

	@Nullable
	private FileWatcher.UnregisterCallback emittersWatcher;
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
				configManager.getConfiguration(HdPluginConfig.CONFIG_GROUP, DEFINITIONS_KEY));
			profiles.putAll(merged.profiles);
			definitions.putAll(merged.definitions);
		}
	}

	Map<String, EmitterProfile> mergeWithBundle(@Nullable String savedJson)
	{
		return mergeAll(savedJson, null).profiles;
	}

	Snapshot mergeAll(@Nullable String savedProfilesJson, @Nullable String savedDefinitionsJson)
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

					if (prior != null)
					{
						profile.setEnabled(prior.isEnabled());
					}
					result.put(key, profile);
				});
			}
			if (saved != null)
			{

				saved.forEach(result::putIfAbsent);
			}
		}

		result.forEach((key, profile) ->
		{

			if (profile.getSignature() == null)
			{
				profile.setSignature(key);
			}

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

			if (profile.isFeather() && profile.getFeatherStrength() == 0)
			{
				profile.setFeatherStrength(2);
				profile.setFeather(false);
			}

			if (profile.getTargetType() == null)
			{
				profile.setTargetType(EmitterProfile.TARGET_PLAYER);
			}

			if (profile.isDynamicLifetime())
			{
				if (profile.getMovementLifetime() == 100)
				{
					profile.setMovementLifetime(50);
				}
				profile.setDynamicLifetime(false);
			}

			if (profile.getShape() == null)
			{
				profile.setShape(Shape.DEFAULT);
			}

			if (profile.getEmitScale() <= 0)
			{
				profile.setEmitScale(100);
			}

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
		return new Snapshot(result, definitionResult);
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
			String emittersJson = EmitterManifest.serialize(gson, profiles);
			configManager.setConfiguration(HdPluginConfig.CONFIG_GROUP, CONFIG_KEY, emittersJson);
			configManager.setConfiguration(HdPluginConfig.CONFIG_GROUP, DEFINITIONS_KEY,
				gson.toJson(definitions, DEFINITIONS_TYPE));
		}
		revision++;
		if (changeListener != null)
		{
			changeListener.run();
		}
	}

	@Nullable
	private Map<String, EmitterProfile> parseEmitters(@Nullable String json)
	{
		if (json == null || json.isEmpty())
		{
			return null;
		}
		Map<String, EmitterProfile> manifest = EmitterManifest.parse(json);
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
		definitionsWatcher = DEFINITIONS_PATH.watch(path -> reload.run());
	}

	void shutDownWatching()
	{
		if (emittersWatcher != null)
		{
			emittersWatcher.unregister();
			emittersWatcher = null;
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
		definitions.clear();
		try (GamevalManager.Handle handle = gamevalManager.obtainHandle())
		{
			Snapshot merged = mergeAll(
				loadBundledJson(EMITTERS_PATH, "/rs117/hd/particles/emitters.json", "emitters"),
				loadBundledJson(DEFINITIONS_PATH, "/rs117/hd/particles/definitions.json", "definitions"));
			profiles.putAll(merged.profiles);
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

	synchronized int getRevision()
	{
		return revision;
	}

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

		profile.setTrailDensity(40);
		profile.setRiseSpeed(0);
		profile.setSpreadSpeed(4);
		String key = allocateKey(profile);
		profiles.put(key, profile);
		save();
		return key;
	}

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

	synchronized Set<Integer> vertexSet(String profileKey)
	{
		EmitterProfile profile = profiles.get(profileKey);
		return profile == null ? Set.of() : Set.copyOf(profile.getVertices());
	}

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

	synchronized void addVertices(String profileKey, Collection<Integer> localVertices)
	{
		EmitterProfile profile = profiles.get(profileKey);
		if (profile != null && profile.getVertices().addAll(localVertices))
		{
			save();
		}
	}

	synchronized void removeVertices(String profileKey, Collection<Integer> localVertices)
	{
		EmitterProfile profile = profiles.get(profileKey);
		if (profile != null && profile.getVertices().removeAll(localVertices))
		{
			save();
		}
	}

	synchronized void addFaces(String profileKey, Collection<Integer> localFaces)
	{
		EmitterProfile profile = profiles.get(profileKey);
		if (profile != null && profile.getFaces().addAll(localFaces))
		{
			save();
		}
	}

	synchronized void removeFaces(String profileKey, Collection<Integer> localFaces)
	{
		EmitterProfile profile = profiles.get(profileKey);
		if (profile != null && profile.getFaces().removeAll(localFaces))
		{
			save();
		}
	}

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

	synchronized void setWip(String profileKey, boolean wip)
	{
		EmitterProfile profile = profiles.get(profileKey);
		if (profile != null && profile.isWip() != wip)
		{
			profile.setWip(wip);
			save();
		}
	}

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
		if (profiles.remove(profileKey) != null)
		{
			save();
		}
	}

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

	synchronized Snapshot snapshot()
	{
		Map<String, EmitterProfile> p = new LinkedHashMap<>();
		profiles.forEach((k, v) ->
		{
			EmitterProfile profile = v.copy();
			hydrateProfileStyle(profile);
			p.put(k, profile);
		});
		Map<String, ParticleDefinition> d = new LinkedHashMap<>();
		definitions.forEach((k, v) -> d.put(k, v.copy()));
		return new Snapshot(p, d);
	}

	synchronized String exportBundle(File dir) throws IOException
	{
		try (GamevalManager.Handle handle = gamevalManager.obtainHandle())
		{
			String emittersJson = EmitterManifest.serialize(gson, profiles);
			Files.write(new File(dir, "emitters.json").toPath(),
				emittersJson.getBytes(StandardCharsets.UTF_8));
		}
		Files.write(new File(dir, "definitions.json").toPath(),
			gson.toJson(definitions, DEFINITIONS_TYPE).getBytes(StandardCharsets.UTF_8));
		return "Wrote " + profiles.size() + " emitters and " + definitions.size() + " definitions to\n"
			+ dir.getAbsolutePath();
	}

	synchronized void setEnabledMany(Collection<String> profileKeys, boolean enabled)
	{
		boolean changed = false;
		for (String key : profileKeys)
		{
			EmitterProfile profile = profiles.get(key);
			if (profile != null && profile.isEnabled() != enabled)
			{
				profile.setEnabled(enabled);
				changed = true;
			}
		}
		if (changed)
		{
			save();
		}
	}
}
