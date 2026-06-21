package rs117.hd.scene;

import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.TileObject;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.config.DaylightCycle;
import rs117.hd.scene.model_overrides.ModelDefinition;
import rs117.hd.scene.lights.LightTimeOfDay;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.scene.model_overrides.ModelReplacement;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.jobs.Job;

@Singleton
public class ModelReplacer {
	private static final float NIGHT_STAGGER_RAMP_WIDTH = 0.08f;

	@Inject
	private Client client;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	private EnvironmentManager environmentManager;

	private float previousNightLightFactor = -1f;

	public void resetTimeOfDayState() {
		previousNightLightFactor = -1f;
	}

	public Model replaceModel(ModelOverride override, Model original, int objectId, TileObject tileObject) {
		return replaceModel(override, original, objectId, tileObject, null);
	}

	public Model replaceModel(
		ModelOverride override,
		Model original,
		int objectId,
		TileObject tileObject,
		int[] worldPos
	) {
		try {
			return replaceModel(
				override,
				original,
				objectId,
				HDUtils.getObjectConfig(tileObject),
				worldPos,
				tileObject.getPlane(),
				tileObject.getLocalLocation().getX(),
				tileObject.getLocalLocation().getY()
			);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return original;
		}
	}

	public Model replaceModel(
		ModelOverride override,
		Model original,
		int objectId,
		int objectConfig,
		int[] worldPos,
		int plane,
		int staggerX,
		int staggerZ
	) throws InterruptedException {
		ModelReplacement replacement = override.modelReplacement;
		if (replacement == null)
			return original;

		int type = objectConfig & 0x3F;
		int orientation = (objectConfig >> 6) & 0x3;
		int seed = computeSeed(objectId, type, orientation, worldPos);

		Model result = original;

		if (replacement.model != null) {
			Model base = getReplacementModel(replacement.model, type, orientation, seed);
			if (base != null)
				result = replacement.mergeWithOriginal
					? mergeModelsOnClientThread(original, base)
					: base;
		}

		if (replacement.nightModel != null && shouldApplyReplacement(replacement, staggerX, staggerZ, plane)) {
			Model night = getReplacementModel(replacement.nightModel, type, orientation, seed);
			if (night != null) {
				result = replacement.mergeWithOriginal
					? mergeModelsOnClientThread(result, night)
					: night;
			}
		}

		return result;
	}

	public Model replaceModelForUpload(
		ModelOverride override,
		Model original,
		int objectId,
		int objectConfig,
		int[] worldPos,
		int plane,
		int staggerX,
		int staggerZ,
		List<TimeOfDayMarker> markers
	) throws InterruptedException {
		Model result = replaceModel(override, original, objectId, objectConfig, worldPos, plane, staggerX, staggerZ);
		ModelReplacement replacement = override.modelReplacement;
		if (markers != null && replacement != null && replacement.isTimeOfDayControlled()) {
			boolean appliedAtUpload = replacement.nightModel != null
				? shouldApplyReplacement(replacement, staggerX, staggerZ, plane)
				: result != original;
			markers.add(TimeOfDayMarker.from(replacement, staggerX, staggerZ, plane, appliedAtUpload));
		}
		return result;
	}

	public boolean needsTimeOfDayInvalidation(List<TimeOfDayMarker> markers) {
		for (TimeOfDayMarker marker : markers) {
			if (shouldApplyReplacement(marker.toReplacement(), marker.staggerX, marker.staggerZ, marker.plane)
				!= marker.appliedAtUpload)
				return true;
		}
		return false;
	}

	public void finishTimeOfDaySwapUpdate() {
		advanceNightFactorTracking();
	}

	public boolean shouldApplyReplacement(ModelReplacement replacement, int staggerX, int staggerZ, int plane) {
		if (replacement == null || replacement.timeOfDay == null)
			return true;

		float nightLightFactor = getCurrentNightLightFactor();
		boolean nightLightsActive = isNightLightsActive();
		if (replacement.dayNightOnly && !nightLightsActive && nightLightFactor <= 0f)
			return false;

		boolean rising = previousNightLightFactor < 0 || nightLightFactor >= previousNightLightFactor;
		float phaseFactor = getEffectiveNightFactor(replacement, staggerX, staggerZ, plane, nightLightFactor, rising);
		return phaseFactor > 0f;
	}

	public float getCurrentNightLightFactor() {
		if (!isNightLightsActive()) {
			DaylightCycle forcedMode = environmentManager.getForcedCycleMode();
			if (forcedMode == DaylightCycle.FIXED_NIGHT || forcedMode == DaylightCycle.ALWAYS_NIGHT)
				return 1f;
			return 0f;
		}

		DaylightCycle forcedMode = environmentManager.getForcedCycleMode();
		DaylightCycle daylightCycle = forcedMode != null ? forcedMode : config.daylightCycle();
		TimeOfDay.setCycleMode(daylightCycle);
		TimeOfDay.setDayLength(config.dayLength());
		return TimeOfDay.getNightLightFactor(plugin.latLong, config.cycleDurationMinutes());
	}

	public boolean isNightFactorRising() {
		float nightLightFactor = getCurrentNightLightFactor();
		return previousNightLightFactor < 0 || nightLightFactor >= previousNightLightFactor;
	}

	public void advanceNightFactorTracking() {
		previousNightLightFactor = getCurrentNightLightFactor();
	}

	public boolean isNightLightsActive() {
		return environmentManager.isOverworld() && config.enableDaylightCycle();
	}

	private float getEffectiveNightFactor(
		ModelReplacement replacement,
		int staggerX,
		int staggerZ,
		int plane,
		float nightLightFactor,
		boolean rising
	) {
		LightTimeOfDay on = replacement.timeOfDay;
		if (on == null)
			return 1f;

		if (rising) {
			float[] window = getPhaseWindow(replacement, on, staggerX, staggerZ, plane);
			return remapNightWindow(nightLightFactor, window[0], window[1]);
		}

		LightTimeOfDay offPhase = replacement.timeOfDayOff != null ? replacement.timeOfDayOff : on;
		float[] window = getPhaseWindow(replacement, offPhase, staggerX, staggerZ, plane);
		float offStart = window[0];
		float offEnd = window[1];

		if (nightLightFactor >= offEnd)
			return 1f;
		if (nightLightFactor <= offStart)
			return 0f;

		float t = (nightLightFactor - offStart) / (offEnd - offStart);
		return t * t * (3f - 2f * t);
	}

	private static float[] getPhaseWindow(
		ModelReplacement replacement,
		LightTimeOfDay phase,
		int staggerX,
		int staggerZ,
		int plane
	) {
		if (!replacement.staggered)
			return new float[] { phase.start, phase.end };

		float phaseSpan = phase.end - phase.start;
		float rampWidth = Math.min(NIGHT_STAGGER_RAMP_WIDTH, phaseSpan);
		float maxOffset = Math.max(0, phaseSpan - rampWidth);
		float offset = getNightStaggerOffset(staggerX, staggerZ, plane) * maxOffset;
		return new float[] { phase.start + offset, phase.start + offset + rampWidth };
	}

	private static float remapNightWindow(float nightLightFactor, float start, float end) {
		if (nightLightFactor <= start)
			return 0f;
		if (nightLightFactor >= end)
			return 1f;

		float t = (nightLightFactor - start) / (end - start);
		return t * t * (3f - 2f * t);
	}

	private static float getNightStaggerOffset(int staggerX, int staggerZ, int plane) {
		int hash = Float.floatToIntBits(staggerX)
			^ Float.floatToIntBits(staggerZ)
			^ plane * 668265263;
		return (hash & 0xFFFF) / 65535f;
	}

	private Model mergeModelsOnClientThread(Model original, Model overlay) throws InterruptedException {
		if (client.isClientThread())
			return client.mergeModels(original, overlay);

		Model[] result = new Model[1];
		Job.runOnClientThread(() -> result[0] = client.mergeModels(original, overlay));
		return result[0];
	}

	private Model getReplacementModel(
		ModelDefinition definition,
		int type,
		int orientation,
		int seed
	) throws InterruptedException {
		if (definition == null)
			return null;

		if (client.isClientThread())
			return definition.getModel(client, type, orientation, seed);

		Model[] result = new Model[1];
		Job.runOnClientThread(() -> result[0] = definition.getModel(client, type, orientation, seed));
		return result[0];
	}

	static int computeSeed(int objectId, int type, int orientation, int[] worldPos) {
		int seed = objectId;
		seed = seed * 31 + type;
		seed = seed * 31 + orientation;
		if (worldPos != null) {
			seed = seed * 31 + worldPos[0];
			seed = seed * 31 + worldPos[1];
			seed = seed * 31 + worldPos[2];
		}
		return seed;
	}

	public static void releaseCaches() {
		ModelDefinition.release();
	}

	public static final class TimeOfDayMarker {
		public int staggerX;
		public int staggerZ;
		public int plane;
		public LightTimeOfDay timeOfDay;
		public LightTimeOfDay timeOfDayOff;
		public boolean staggered;
		public boolean dayNightOnly;
		public boolean appliedAtUpload;

		public static TimeOfDayMarker from(
			ModelReplacement replacement,
			int staggerX,
			int staggerZ,
			int plane,
			boolean appliedAtUpload
		) {
			TimeOfDayMarker marker = new TimeOfDayMarker();
			marker.staggerX = staggerX;
			marker.staggerZ = staggerZ;
			marker.plane = plane;
			marker.timeOfDay = replacement.timeOfDay;
			marker.timeOfDayOff = replacement.timeOfDayOff;
			marker.staggered = replacement.staggered;
			marker.dayNightOnly = replacement.dayNightOnly;
			marker.appliedAtUpload = appliedAtUpload;
			return marker;
		}

		public ModelReplacement toReplacement() {
			ModelReplacement replacement = new ModelReplacement();
			replacement.timeOfDay = timeOfDay;
			replacement.timeOfDayOff = timeOfDayOff;
			replacement.staggered = staggered;
			replacement.dayNightOnly = dayNightOnly;
			return replacement;
		}
	}
}
