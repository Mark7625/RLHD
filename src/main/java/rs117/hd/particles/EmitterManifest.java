package rs117.hd.particles;

import com.google.gson.Gson;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import lombok.Getter;
import lombok.Setter;
import rs117.hd.scene.GamevalManager;
import rs117.hd.utils.GsonUtils;

/**
 * List-based emitter manifest: each entry carries a description, particle
 * definition id(s), and typed placement arrays (objectEmitters, playerEmitters,
 * etc.). Gameval fields use {@link GamevalManager} adapters; item ids remain
 * raw integers. Defaults are omitted on write via {@link GsonUtils.ExcludeDefaults}.
 */
final class EmitterManifest
{
	private static final TypeToken<List<Entry>> MANIFEST_TYPE = new TypeToken<List<Entry>>() {};

	private EmitterManifest()
	{
	}

	@Getter
	@Setter
	@GsonUtils.ExcludeDefaults
	static class Entry
	{
		private String description;
		private String particleId;
		private List<String> particleIds;
		private boolean enabled = true;
		private boolean wip;
		private String folder;
		private List<ObjectEmitterPlacement> objectEmitters;
		private List<PlayerEmitterPlacement> playerEmitters;
		private List<NpcEmitterPlacement> npcEmitters;
		private List<ProjectileEmitterPlacement> projectileEmitters;
		private List<GraphicEmitterPlacement> graphicEmitters;
		private List<WeatherEmitterPlacement> weatherEmitters;
	}

	@Getter
	@Setter
	@GsonUtils.ExcludeDefaults
	static class ObjectEmitterPlacement
	{
		@SerializedName("object")
		@JsonAdapter(GamevalManager.ObjectIdAdapter.class)
		private int objectId = -1;
		private String signature;
		private Set<Integer> vertices = new HashSet<>();
		private Set<Integer> faces = new HashSet<>();
		private Set<Integer> itemIds = new HashSet<>();
		private Set<Integer> animationIds = new HashSet<>();
		private String animFrames = "";
		private int offsetX;
		private int offsetY;
		private int offsetZ;
		private int emitScale = 100;
		private int featherStrength;
		private int interpolation;
		private int depthBias;

		void applyTo(EmitterProfile profile)
		{
			profile.setTargetType(EmitterProfile.TARGET_OBJECT);
			profile.setObjectId(objectId);
			profile.setSignature(signature);
			profile.setVertices(new HashSet<>(vertices));
			profile.setFaces(faces == null ? new HashSet<>() : new HashSet<>(faces));
			profile.setItemIds(new HashSet<>(itemIds));
			profile.setAnimationIds(new HashSet<>(animationIds));
			profile.setAnimFrames(animFrames);
			profile.setOffsetX(offsetX);
			profile.setOffsetY(offsetY);
			profile.setOffsetZ(offsetZ);
			profile.setEmitScale(emitScale);
			profile.setFeatherStrength(featherStrength);
			profile.setInterpolation(interpolation);
			profile.setDepthBias(depthBias);
		}

		static ObjectEmitterPlacement from(EmitterProfile profile)
		{
			ObjectEmitterPlacement placement = new ObjectEmitterPlacement();
			placement.objectId = profile.getObjectId();
			placement.signature = profile.getSignature();
			placement.vertices = new HashSet<>(profile.getVertices());
			placement.faces = new HashSet<>(profile.getFaces());
			placement.itemIds = new HashSet<>(profile.getItemIds());
			placement.animationIds = new HashSet<>(profile.getAnimationIds());
			placement.animFrames = profile.getAnimFrames();
			placement.offsetX = profile.getOffsetX();
			placement.offsetY = profile.getOffsetY();
			placement.offsetZ = profile.getOffsetZ();
			placement.emitScale = profile.getEmitScale();
			placement.featherStrength = profile.getFeatherStrength();
			placement.interpolation = profile.getInterpolation();
			placement.depthBias = profile.getDepthBias();
			return placement;
		}
	}

	@Getter
	@Setter
	@GsonUtils.ExcludeDefaults
	static class PlayerEmitterPlacement
	{
		private String signature;
		private Set<Integer> vertices = new HashSet<>();
		private Set<Integer> faces = new HashSet<>();
		private Set<Integer> itemIds = new HashSet<>();
		private Set<Integer> animationIds = new HashSet<>();
		private String animFrames = "";
		private int offsetX;
		private int offsetY;
		private int offsetZ;
		private int emitScale = 100;
		private int featherStrength;
		private int interpolation;
		private int depthBias;

		void applyTo(EmitterProfile profile)
		{
			profile.setTargetType(EmitterProfile.TARGET_PLAYER);
			profile.setSignature(signature);
			profile.setVertices(new HashSet<>(vertices));
			profile.setFaces(faces == null ? new HashSet<>() : new HashSet<>(faces));
			profile.setItemIds(new HashSet<>(itemIds));
			profile.setAnimationIds(new HashSet<>(animationIds));
			profile.setAnimFrames(animFrames);
			profile.setOffsetX(offsetX);
			profile.setOffsetY(offsetY);
			profile.setOffsetZ(offsetZ);
			profile.setEmitScale(emitScale);
			profile.setFeatherStrength(featherStrength);
			profile.setInterpolation(interpolation);
			profile.setDepthBias(depthBias);
		}

		static PlayerEmitterPlacement from(EmitterProfile profile)
		{
			PlayerEmitterPlacement placement = new PlayerEmitterPlacement();
			placement.signature = profile.getSignature();
			placement.vertices = new HashSet<>(profile.getVertices());
			placement.faces = new HashSet<>(profile.getFaces());
			placement.itemIds = new HashSet<>(profile.getItemIds());
			placement.animationIds = new HashSet<>(profile.getAnimationIds());
			placement.animFrames = profile.getAnimFrames();
			placement.offsetX = profile.getOffsetX();
			placement.offsetY = profile.getOffsetY();
			placement.offsetZ = profile.getOffsetZ();
			placement.emitScale = profile.getEmitScale();
			placement.featherStrength = profile.getFeatherStrength();
			placement.interpolation = profile.getInterpolation();
			placement.depthBias = profile.getDepthBias();
			return placement;
		}
	}

	@Getter
	@Setter
	@GsonUtils.ExcludeDefaults
	static class NpcEmitterPlacement
	{
		@SerializedName("npc")
		@JsonAdapter(GamevalManager.NpcIdAdapter.class)
		private int npcId = -1;
		private String signature;
		private Set<Integer> vertices = new HashSet<>();
		private Set<Integer> faces = new HashSet<>();
		private Set<Integer> itemIds = new HashSet<>();
		private Set<Integer> animationIds = new HashSet<>();
		private String animFrames = "";
		private int offsetX;
		private int offsetY;
		private int offsetZ;
		private int emitScale = 100;
		private int featherStrength;
		private int interpolation;
		private int depthBias;

		void applyTo(EmitterProfile profile)
		{
			profile.setTargetType(EmitterProfile.TARGET_NPC);
			profile.setNpcId(npcId);
			profile.setSignature(signature);
			profile.setVertices(new HashSet<>(vertices));
			profile.setFaces(faces == null ? new HashSet<>() : new HashSet<>(faces));
			profile.setItemIds(new HashSet<>(itemIds));
			profile.setAnimationIds(new HashSet<>(animationIds));
			profile.setAnimFrames(animFrames);
			profile.setOffsetX(offsetX);
			profile.setOffsetY(offsetY);
			profile.setOffsetZ(offsetZ);
			profile.setEmitScale(emitScale);
			profile.setFeatherStrength(featherStrength);
			profile.setInterpolation(interpolation);
			profile.setDepthBias(depthBias);
		}

		static NpcEmitterPlacement from(EmitterProfile profile)
		{
			NpcEmitterPlacement placement = new NpcEmitterPlacement();
			placement.npcId = profile.getNpcId();
			placement.signature = profile.getSignature();
			placement.vertices = new HashSet<>(profile.getVertices());
			placement.faces = new HashSet<>(profile.getFaces());
			placement.itemIds = new HashSet<>(profile.getItemIds());
			placement.animationIds = new HashSet<>(profile.getAnimationIds());
			placement.animFrames = profile.getAnimFrames();
			placement.offsetX = profile.getOffsetX();
			placement.offsetY = profile.getOffsetY();
			placement.offsetZ = profile.getOffsetZ();
			placement.emitScale = profile.getEmitScale();
			placement.featherStrength = profile.getFeatherStrength();
			placement.interpolation = profile.getInterpolation();
			placement.depthBias = profile.getDepthBias();
			return placement;
		}
	}

	@Getter
	@Setter
	@GsonUtils.ExcludeDefaults
	static class ProjectileEmitterPlacement
	{
		@SerializedName("projectile")
		@JsonAdapter(GamevalManager.SpotanimIdAdapter.class)
		private int projectileId = -1;
		private String signature;
		private Set<Integer> itemIds = new HashSet<>();
		private int offsetX;
		private int offsetY;
		private int offsetZ;
		private int emitScale = 100;
		private int featherStrength;
		private int interpolation;
		private int depthBias;

		void applyTo(EmitterProfile profile)
		{
			profile.setTargetType(EmitterProfile.TARGET_PROJECTILE);
			profile.setProjectileId(projectileId);
			profile.setSignature(signature);
			profile.setItemIds(new HashSet<>(itemIds));
			profile.setOffsetX(offsetX);
			profile.setOffsetY(offsetY);
			profile.setOffsetZ(offsetZ);
			profile.setEmitScale(emitScale);
			profile.setFeatherStrength(featherStrength);
			profile.setInterpolation(interpolation);
			profile.setDepthBias(depthBias);
		}

		static ProjectileEmitterPlacement from(EmitterProfile profile)
		{
			ProjectileEmitterPlacement placement = new ProjectileEmitterPlacement();
			placement.projectileId = profile.getProjectileId();
			placement.signature = profile.getSignature();
			placement.itemIds = new HashSet<>(profile.getItemIds());
			placement.offsetX = profile.getOffsetX();
			placement.offsetY = profile.getOffsetY();
			placement.offsetZ = profile.getOffsetZ();
			placement.emitScale = profile.getEmitScale();
			placement.featherStrength = profile.getFeatherStrength();
			placement.interpolation = profile.getInterpolation();
			placement.depthBias = profile.getDepthBias();
			return placement;
		}
	}

	@Getter
	@Setter
	@GsonUtils.ExcludeDefaults
	static class GraphicEmitterPlacement
	{
		@SerializedName("graphic")
		@JsonAdapter(GamevalManager.SpotanimIdAdapter.class)
		private int graphicId = -1;
		private String signature;
		private Set<Integer> vertices = new HashSet<>();
		private Set<Integer> faces = new HashSet<>();
		private int offsetX;
		private int offsetY;
		private int offsetZ;
		private int emitScale = 100;
		private int featherStrength;
		private int interpolation;
		private int depthBias;

		void applyTo(EmitterProfile profile)
		{
			profile.setTargetType(EmitterProfile.TARGET_GRAPHIC);
			profile.setGraphicId(graphicId);
			profile.setSignature(signature);
			profile.setVertices(vertices == null ? new HashSet<>() : new HashSet<>(vertices));
			profile.setFaces(faces == null ? new HashSet<>() : new HashSet<>(faces));
			profile.setOffsetX(offsetX);
			profile.setOffsetY(offsetY);
			profile.setOffsetZ(offsetZ);
			profile.setEmitScale(emitScale);
			profile.setFeatherStrength(featherStrength);
			profile.setInterpolation(interpolation);
			profile.setDepthBias(depthBias);
		}

		static GraphicEmitterPlacement from(EmitterProfile profile)
		{
			GraphicEmitterPlacement placement = new GraphicEmitterPlacement();
			placement.graphicId = profile.getGraphicId();
			placement.signature = profile.getSignature();
			placement.vertices = new HashSet<>(profile.getVertices());
			placement.faces = new HashSet<>(profile.getFaces());
			placement.offsetX = profile.getOffsetX();
			placement.offsetY = profile.getOffsetY();
			placement.offsetZ = profile.getOffsetZ();
			placement.emitScale = profile.getEmitScale();
			placement.featherStrength = profile.getFeatherStrength();
			placement.interpolation = profile.getInterpolation();
			placement.depthBias = profile.getDepthBias();
			return placement;
		}
	}

	@Getter
	@Setter
	@GsonUtils.ExcludeDefaults
	static class WeatherEmitterPlacement
	{
		private List<String> weatherAreas;
		private float weatherParticlesPerTile = 10f;
		private float weatherDensityScale = 1f;

		void applyTo(EmitterProfile profile)
		{
			profile.setTargetType(EmitterProfile.TARGET_WEATHER);
			profile.setWeatherAreas(weatherAreas == null ? List.of() : new ArrayList<>(weatherAreas));
			profile.setWeatherParticlesPerTile(weatherParticlesPerTile);
			profile.setWeatherDensityScale(weatherDensityScale);
		}

		static WeatherEmitterPlacement from(EmitterProfile profile)
		{
			WeatherEmitterPlacement placement = new WeatherEmitterPlacement();
			if (profile.getWeatherAreas() != null && !profile.getWeatherAreas().isEmpty())
			{
				placement.weatherAreas = new ArrayList<>(profile.getWeatherAreas());
			}
			if (profile.getWeatherParticlesPerTile() != 10f)
			{
				placement.weatherParticlesPerTile = profile.getWeatherParticlesPerTile();
			}
			if (profile.getWeatherDensityScale() != 1f)
			{
				placement.weatherDensityScale = profile.getWeatherDensityScale();
			}
			return placement;
		}
	}

	@Nullable
	static Map<String, EmitterProfile> parseLegacyMap(@Nullable String json, Gson gson)
	{
		if (json == null || json.isEmpty() || isManifestJson(json))
		{
			return null;
		}
		try
		{
			Map<String, ParticleEmitter> emitters = gson.fromJson(
				json,
				new TypeToken<Map<String, ParticleEmitter>>() {}.getType());
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

	static boolean isManifestJson(@Nullable String json)
	{
		if (json == null)
		{
			return false;
		}
		String trimmed = json.stripLeading();
		return trimmed.startsWith("[");
	}

	@Nullable
	static Map<String, EmitterProfile> parse(
		@Nullable String json,
		@Nullable Map<String, ProfileFolder> folders)
	{
		if (json == null || json.isEmpty() || !isManifestJson(json))
		{
			return null;
		}
		try
		{
			List<Entry> entries = manifestGson(new Gson())
				.fromJson(json, MANIFEST_TYPE.getType());
			if (entries == null || entries.isEmpty())
			{
				return null;
			}
			Map<String, EmitterProfile> result = new LinkedHashMap<>();
			Set<String> reserved = new HashSet<>();
			for (Entry entry : entries)
			{
				if (entry != null)
				{
					expandEntry(entry, folders, result, reserved);
				}
			}
			return result.isEmpty() ? null : result;
		}
		catch (Exception e)
		{
			return null;
		}
	}

	static String serialize(
		Gson gson,
		Map<String, EmitterProfile> profiles,
		Map<String, ProfileFolder> folders)
	{
		List<Entry> entries = new ArrayList<>();
		profiles.forEach((key, profile) -> entries.add(toEntry(key, profile, folders)));
		return manifestGson(gson).toJson(entries, MANIFEST_TYPE.getType());
	}

	private static Gson manifestGson(Gson gson)
	{
		return GsonUtils.wrap(gson);
	}

	private static void expandEntry(
		Entry entry,
		@Nullable Map<String, ProfileFolder> folders,
		Map<String, EmitterProfile> out,
		Set<String> reserved)
	{
		List<String> particleIds = new ArrayList<>();
		if (entry.getParticleIds() != null && !entry.getParticleIds().isEmpty())
		{
			particleIds.addAll(entry.getParticleIds());
		}
		else if (entry.getParticleId() != null && !entry.getParticleId().isEmpty())
		{
			particleIds.add(entry.getParticleId());
		}
		if (particleIds.isEmpty())
		{
			return;
		}

		List<EmitterProfile> placements = collectPlacements(entry);
		if (placements.isEmpty())
		{
			return;
		}

		String folderId = resolveFolderId(entry.getFolder(), folders);
		for (String particleId : particleIds)
		{
			for (EmitterProfile placement : placements)
			{
				EmitterProfile profile = placement.copy();
				profile.setDefinitionId(particleId);
				profile.setEnabled(entry.isEnabled());
				profile.setWip(entry.isWip());
				profile.setFolderId(folderId);
				if (entry.getDescription() != null)
				{
					profile.setName(entry.getDescription());
				}
				String key = ParticleIds.emitterKeyFor(profile, reserved);
				reserved.add(key);
				out.put(key, profile);
			}
		}
	}

	private static List<EmitterProfile> collectPlacements(Entry entry)
	{
		List<EmitterProfile> placements = new ArrayList<>();
		if (entry.getObjectEmitters() != null)
		{
			for (ObjectEmitterPlacement placement : entry.getObjectEmitters())
			{
				if (placement != null)
				{
					EmitterProfile profile = new EmitterProfile();
					placement.applyTo(profile);
					placements.add(profile);
				}
			}
		}
		if (entry.getPlayerEmitters() != null)
		{
			for (PlayerEmitterPlacement placement : entry.getPlayerEmitters())
			{
				if (placement != null)
				{
					EmitterProfile profile = new EmitterProfile();
					placement.applyTo(profile);
					placements.add(profile);
				}
			}
		}
		if (entry.getNpcEmitters() != null)
		{
			for (NpcEmitterPlacement placement : entry.getNpcEmitters())
			{
				if (placement != null)
				{
					EmitterProfile profile = new EmitterProfile();
					placement.applyTo(profile);
					placements.add(profile);
				}
			}
		}
		if (entry.getProjectileEmitters() != null)
		{
			for (ProjectileEmitterPlacement placement : entry.getProjectileEmitters())
			{
				if (placement != null)
				{
					EmitterProfile profile = new EmitterProfile();
					placement.applyTo(profile);
					placements.add(profile);
				}
			}
		}
		if (entry.getGraphicEmitters() != null)
		{
			for (GraphicEmitterPlacement placement : entry.getGraphicEmitters())
			{
				if (placement != null)
				{
					EmitterProfile profile = new EmitterProfile();
					placement.applyTo(profile);
					placements.add(profile);
				}
			}
		}
		if (entry.getWeatherEmitters() != null)
		{
			for (WeatherEmitterPlacement placement : entry.getWeatherEmitters())
			{
				if (placement != null)
				{
					EmitterProfile profile = new EmitterProfile();
					placement.applyTo(profile);
					placements.add(profile);
				}
			}
		}
		return placements;
	}

	@Nullable
	private static String resolveFolderId(@Nullable String folderName, @Nullable Map<String, ProfileFolder> folders)
	{
		if (folderName == null || folderName.isEmpty() || folders == null)
		{
			return null;
		}
		for (ProfileFolder folder : folders.values())
		{
			if (folderName.equals(folder.getName()))
			{
				return folder.getId();
			}
		}
		return null;
	}

	private static Entry toEntry(
		String key,
		EmitterProfile profile,
		Map<String, ProfileFolder> folders)
	{
		Entry entry = new Entry();
		entry.setDescription(ParticleIds.emitterListName(key, profile));
		entry.setParticleId(profile.getDefinitionId());
		if (!profile.isEnabled())
		{
			entry.setEnabled(false);
		}
		if (profile.isWip())
		{
			entry.setWip(true);
		}
		if (profile.getFolderId() != null && folders.containsKey(profile.getFolderId()))
		{
			entry.setFolder(folders.get(profile.getFolderId()).getName());
		}

		switch (profile.getTargetType())
		{
			case EmitterProfile.TARGET_OBJECT:
				entry.setObjectEmitters(List.of(ObjectEmitterPlacement.from(profile)));
				break;
			case EmitterProfile.TARGET_PLAYER:
				entry.setPlayerEmitters(List.of(PlayerEmitterPlacement.from(profile)));
				break;
			case EmitterProfile.TARGET_NPC:
				entry.setNpcEmitters(List.of(NpcEmitterPlacement.from(profile)));
				break;
			case EmitterProfile.TARGET_PROJECTILE:
				entry.setProjectileEmitters(List.of(ProjectileEmitterPlacement.from(profile)));
				break;
			case EmitterProfile.TARGET_GRAPHIC:
				entry.setGraphicEmitters(List.of(GraphicEmitterPlacement.from(profile)));
				break;
			case EmitterProfile.TARGET_WEATHER:
				entry.setWeatherEmitters(List.of(WeatherEmitterPlacement.from(profile)));
				break;
		}
		return entry;
	}
}
