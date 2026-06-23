package rs117.hd.scene;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Model;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.config.DaylightCycle;
import rs117.hd.scene.lights.LightTimeOfDay;
import rs117.hd.scene.model_overrides.ModelDefinition;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.scene.model_overrides.ModelReplacement;
import rs117.hd.utils.collections.Int2ObjectHashMap;
import rs117.hd.utils.jobs.Job;

@Slf4j
@Singleton
public class ModelReplacer {
	private static final Int2ObjectHashMap<Model> mergedModelCache = new Int2ObjectHashMap<>();
	private static final Int2ObjectHashMap<OverlayStacks> overlayStackCache = new Int2ObjectHashMap<>();
	private static final List<float[]> registeredPhaseWindows = new ArrayList<>(4);

	@Inject
	private Client client;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	private EnvironmentManager environmentManager;

	private float previousNightLightFactor = -1f;
	private Boolean timeOfDayReplacementEnabledLast;
	private DaylightCycle lastEffectiveCycleMode;
	private int nightFactorCycle = -1;
	private float nightFactorCached;
	private static volatile boolean preloadComplete;

	public void syncTimeOfDayReplacementState() {
		timeOfDayReplacementEnabledLast = isTimeOfDayReplacementEnabled();
		lastEffectiveCycleMode = getEffectiveCycleMode();
	}

	public void resetTimeOfDayState() {
		previousNightLightFactor = -1f;
		nightFactorCycle = -1;
		lastEffectiveCycleMode = null;
	}

	public void onDayNightCycleToggled() {
		timeOfDayReplacementEnabledLast = isTimeOfDayReplacementEnabled();
		previousNightLightFactor = -1f;
		nightFactorCycle = -1;
		lastEffectiveCycleMode = getEffectiveCycleMode();
		log.debug(
			"Day/night cycle toggled (enabled={}) - zones will rebuild using prebuilt overlay caches",
			timeOfDayReplacementEnabledLast
		);
	}

	public void preloadReplacementModels() throws InterruptedException {
		runOnClientThread(() -> preloadOnClientThread(null));
	}

	public void preloadReplacementModelsForOverrides(Iterable<ModelOverride> overrides) {
		try {
			runOnClientThread(() -> preloadOnClientThread(overrides));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void preloadOnClientThread(Iterable<ModelOverride> overrides) {
		if (preloadComplete && overrides == null)
			return;
		if (ModelDefinitionManager.MODEL_MAP.isEmpty())
			return;

		long start = System.nanoTime();
		ModelDefinition.preloadModelData(client);
		for (ModelDefinition def : ModelDefinitionManager.MODEL_MAP.values()) {
			for (int type : ModelDefinition.PRELOAD_TYPES) {
				for (int orientation = 0; orientation < 4; orientation++)
					def.getModel(client, type, orientation);
			}
		}

		Set<ModelReplacement> seen = new HashSet<>();
		if (overrides != null) {
			for (ModelOverride override : overrides)
				collectReplacementConfigs(override, seen);
		}
		for (ModelReplacement replacement : seen)
			buildOverlayStacks(replacement);

		preloadComplete = true;
		log.debug(
			"Replacement model preload finished in {} ms ({} overlay configs, {} overlay stacks, {} merged)",
			(System.nanoTime() - start) / 1_000_000L,
			seen.size(),
			overlayStackCache.size(),
			mergedModelCache.size()
		);
	}

	private void collectReplacementConfigs(ModelOverride override, Set<ModelReplacement> seen) {
		if (override == null)
			return;
		if (override.modelReplacement != null && !override.isDummy) {
			seen.add(override.modelReplacement);
			registerTimeOfDayPhase(override.modelReplacement);
		}
		if (override.areaOverrides != null) {
			for (ModelOverride areaOverride : override.areaOverrides.values())
				collectReplacementConfigs(areaOverride, seen);
		}
	}

	private static void registerTimeOfDayPhase(ModelReplacement replacement) {
		if (replacement == null || !replacement.isTimeOfDayControlled())
			return;
		LightTimeOfDay off = replacement.timeOfDayOff != null ? replacement.timeOfDayOff : replacement.timeOfDay;
		float[] window = {
			replacement.timeOfDay.start, replacement.timeOfDay.end,
			off.start, off.end
		};
		for (float[] existing : registeredPhaseWindows) {
			if (existing[0] == window[0] && existing[1] == window[1]
				&& existing[2] == window[2] && existing[3] == window[3])
				return;
		}
		registeredPhaseWindows.add(window);
	}

	public boolean shouldCheckTimeOfDaySwaps() {
		if (!isTimeOfDayReplacementEnabled())
			return false;
		if (registeredPhaseWindows.isEmpty())
			return true;

		float current = getCurrentNightLightFactor();
		float prev = previousNightLightFactor;
		if (prev < 0f)
			return true;

		for (float[] window : registeredPhaseWindows) {
			if (isInPhaseTransition(current, prev, window[0], window[1])
				|| isInPhaseTransition(current, prev, window[2], window[3]))
				return true;
		}
		return false;
	}

	private static boolean isInPhaseTransition(float current, float prev, float start, float end) {
		if (current >= start && current <= end)
			return true;
		if (prev >= start && prev <= end)
			return true;
		return (prev < start && current >= start) || (prev >= start && current < start)
			|| (prev < end && current >= end) || (prev >= end && current < end);
	}

	public boolean consumeTimeOfDayReplacementToggle() {
		boolean enabled = isTimeOfDayReplacementEnabled();
		if (timeOfDayReplacementEnabledLast == null) {
			timeOfDayReplacementEnabledLast = enabled;
			lastEffectiveCycleMode = getEffectiveCycleMode();
			return false;
		}
		if (timeOfDayReplacementEnabledLast != enabled) {
			onDayNightCycleToggled();
			return true;
		}
		DaylightCycle cycleMode = getEffectiveCycleMode();
		if (lastEffectiveCycleMode != cycleMode) {
			lastEffectiveCycleMode = cycleMode;
			onDayNightCycleToggled();
			return true;
		}
		return false;
	}

	public Model replaceModel(
		ModelOverride override,
		Model original,
		int objectId,
		int objectConfig
	) throws InterruptedException {
		ModelReplacement replacement = override.modelReplacement;
		if (replacement == null)
			return original;
		if (replacement.isTimeOfDayControlled() && !isTimeOfDayReplacementEnabled())
			return original;

		int type = objectConfig & 0x3F;
		int orientation = (objectConfig >> 6) & 0x3;
		boolean night = replacement.nightModel != null && shouldApplyReplacement(replacement);
		OverlayStacks stacks = getOverlayStacks(replacement, type, orientation);

		if (isDayNightSplit(replacement)) {
			Model stack = night ? stacks.night : stacks.day;
			if (stack == null)
				return original;
			if (!replacement.mergeWithOriginal)
				return stack;
			return getMergedModel(objectId, type, orientation, original, stack, night);
		}

		Model result = original;
		if (replacement.model != null && stacks.day != null) {
			result = replacement.mergeWithOriginal
				? mergeModels(result, stacks.day)
				: stacks.day;
		}
		if (night && stacks.night != null) {
			result = replacement.mergeWithOriginal
				? mergeModels(result, stacks.night)
				: stacks.night;
		}
		return result;
	}

	public Model replaceModelForUpload(
		ModelOverride override,
		Model original,
		int objectId,
		int objectConfig,
		List<TimeOfDayMarker> markers
	) throws InterruptedException {
		Model result = replaceModel(override, original, objectId, objectConfig);
		ModelReplacement replacement = override.modelReplacement;
		if (markers != null && replacement != null && replacement.isTimeOfDayControlled()
			&& isTimeOfDayReplacementEnabled()) {
			registerTimeOfDayPhase(replacement);
			boolean appliedAtUpload = replacement.nightModel != null
				? shouldApplyReplacement(replacement)
				: result != original;
			markers.add(TimeOfDayMarker.from(replacement, appliedAtUpload));
		}
		return result;
	}

	public boolean needsTimeOfDayInvalidation(List<TimeOfDayMarker> markers) {
		for (TimeOfDayMarker marker : markers) {
			if (shouldApplyReplacement(marker) != marker.appliedAtUpload)
				return true;
		}
		return false;
	}

	public void finishTimeOfDaySwapUpdate() {
		previousNightLightFactor = getCurrentNightLightFactor();
	}

	public boolean shouldApplyReplacement(ModelReplacement replacement) {
		if (replacement == null || replacement.timeOfDay == null)
			return true;
		float nightLightFactor = getCurrentNightLightFactor();
		if (replacement.dayNightOnly && !isNightLightsActive() && nightLightFactor <= 0f)
			return false;
		boolean rising = previousNightLightFactor < 0 || nightLightFactor >= previousNightLightFactor;
		return getEffectiveNightFactor(replacement, nightLightFactor, rising) > 0f;
	}

	private boolean shouldApplyReplacement(TimeOfDayMarker marker) {
		if (marker.timeOfDay == null)
			return true;
		float nightLightFactor = getCurrentNightLightFactor();
		if (marker.dayNightOnly && !isNightLightsActive() && nightLightFactor <= 0f)
			return false;
		boolean rising = previousNightLightFactor < 0 || nightLightFactor >= previousNightLightFactor;
		return getEffectiveNightFactor(marker.timeOfDay, marker.timeOfDayOff, nightLightFactor, rising) > 0f;
	}

	private static float getEffectiveNightFactor(
		ModelReplacement replacement,
		float nightLightFactor,
		boolean rising
	) {
		return getEffectiveNightFactor(
			replacement.timeOfDay,
			replacement.timeOfDayOff,
			nightLightFactor,
			rising
		);
	}

	private static float getEffectiveNightFactor(
		LightTimeOfDay on,
		LightTimeOfDay timeOfDayOff,
		float nightLightFactor,
		boolean rising
	) {
		if (on == null)
			return 1f;
		if (rising)
			return remapNightWindow(nightLightFactor, on.start, on.end);
		LightTimeOfDay off = timeOfDayOff != null ? timeOfDayOff : on;
		if (nightLightFactor >= off.end)
			return 1f;
		if (nightLightFactor <= off.start)
			return 0f;
		float t = (nightLightFactor - off.start) / (off.end - off.start);
		return smoothstep(t);
	}

	private static float remapNightWindow(float nightLightFactor, float start, float end) {
		if (nightLightFactor <= start)
			return 0f;
		if (nightLightFactor >= end)
			return 1f;
		return smoothstep((nightLightFactor - start) / (end - start));
	}

	private static float smoothstep(float t) {
		return t * t * (3f - 2f * t);
	}

	public float getCurrentNightLightFactor() {
		int cycle = client.getGameCycle();
		if (cycle == nightFactorCycle)
			return nightFactorCached;
		nightFactorCycle = cycle;
		if (!isNightLightsActive()) {
			DaylightCycle forcedMode = environmentManager.getForcedCycleMode();
			nightFactorCached = forcedMode == DaylightCycle.FIXED_NIGHT || forcedMode == DaylightCycle.ALWAYS_NIGHT
				? 1f : 0f;
			return nightFactorCached;
		}
		DaylightCycle forcedMode = environmentManager.getForcedCycleMode();
		DaylightCycle daylightCycle = forcedMode != null ? forcedMode : config.daylightCycle();
		TimeOfDay.setCycleMode(daylightCycle);
		TimeOfDay.setDayLength(config.dayLength());
		nightFactorCached = TimeOfDay.getNightLightFactor(plugin.latLong, config.cycleDurationMinutes());
		return nightFactorCached;
	}

	public boolean isNightLightsActive() {
		return environmentManager.isOverworld() && config.enableDaylightCycle();
	}

	private boolean isTimeOfDayReplacementEnabled() {
		if (config.enableDaylightCycle())
			return true;
		DaylightCycle forcedMode = environmentManager.getForcedCycleMode();
		return forcedMode == DaylightCycle.FIXED_NIGHT || forcedMode == DaylightCycle.ALWAYS_NIGHT;
	}

	private DaylightCycle getEffectiveCycleMode() {
		if (!isTimeOfDayReplacementEnabled())
			return null;
		DaylightCycle forcedMode = environmentManager.getForcedCycleMode();
		return forcedMode != null ? forcedMode : config.daylightCycle();
	}

	private static boolean isDayNightSplit(ModelReplacement replacement) {
		return getBaseOverlay(replacement) != null
			&& replacement.nightModel != null
			&& replacement.isTimeOfDayControlled();
	}

	private static ModelDefinition getBaseOverlay(ModelReplacement replacement) {
		if (replacement.baseModel != null)
			return replacement.baseModel;
		if (replacement.model != null && replacement.nightModel != null && replacement.isTimeOfDayControlled())
			return replacement.model;
		return null;
	}

	private OverlayStacks getOverlayStacks(ModelReplacement replacement, int type, int orientation) {
		int key = packOverlayStackKey(replacementSignature(replacement), type, orientation);
		OverlayStacks stacks = overlayStackCache.get(key);
		if (stacks != null)
			return stacks;
		buildOverlayStacks(replacement);
		stacks = overlayStackCache.get(key);
		return stacks != null ? stacks : OverlayStacks.EMPTY;
	}

	private void buildOverlayStacks(ModelReplacement replacement) {
		try {
			runOnClientThread(() -> buildOverlayStacksOnClientThread(replacement));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void buildOverlayStacksOnClientThread(ModelReplacement replacement) {
		int signature = replacementSignature(replacement);
		ModelDefinition baseOverlay = getBaseOverlay(replacement);
		for (int type : ModelDefinition.PRELOAD_TYPES) {
			for (int orientation = 0; orientation < 4; orientation++) {
				int key = packOverlayStackKey(signature, type, orientation);
				if (overlayStackCache.containsKey(key))
					continue;
				Model dayStack = null;
				if (baseOverlay != null)
					dayStack = baseOverlay.getModel(client, type, orientation);
				else if (replacement.model != null)
					dayStack = replacement.model.getModel(client, type, orientation);
				Model nightStack = null;
				if (replacement.nightModel != null) {
					Model nightOverlay = replacement.nightModel.getModel(client, type, orientation);
					if (nightOverlay != null) {
						nightStack = dayStack != null
							? client.mergeModels(dayStack, nightOverlay)
							: nightOverlay;
					}
				}
				overlayStackCache.put(key, new OverlayStacks(dayStack, nightStack));
			}
		}
	}

	private Model getMergedModel(
		int objectId,
		int type,
		int orientation,
		Model original,
		Model stack,
		boolean night
	) throws InterruptedException {
		int cacheKey = packMergeCacheKey(objectId, type, orientation, night);
		synchronized (mergedModelCache) {
			Model cached = mergedModelCache.get(cacheKey);
			if (cached != null)
				return cached;
		}
		Model result = mergeModels(original, stack);
		if (result == null)
			return original;
		synchronized (mergedModelCache) {
			mergedModelCache.put(cacheKey, result);
		}
		return result;
	}

	private Model mergeModels(Model original, Model overlay) throws InterruptedException {
		if (client.isClientThread())
			return client.mergeModels(original, overlay);
		Model[] result = new Model[1];
		Job.runOnClientThread(() -> result[0] = client.mergeModels(original, overlay));
		return result[0];
	}

	private void runOnClientThread(Runnable task) throws InterruptedException {
		if (client.isClientThread())
			task.run();
		else
			Job.runOnClientThread(task);
	}

	private static int replacementSignature(ModelReplacement replacement) {
		int signature = 1;
		if (replacement.baseModel != null)
			signature = signature * 31 + replacement.baseModel.name.hashCode();
		if (replacement.model != null)
			signature = signature * 31 + replacement.model.name.hashCode();
		if (replacement.nightModel != null)
			signature = signature * 31 + replacement.nightModel.name.hashCode();
		return signature * 31 + (replacement.mergeWithOriginal ? 1 : 0);
	}

	private static int packOverlayStackKey(int replacementSignature, int type, int orientation) {
		return replacementSignature ^ (type << 24) ^ (orientation << 20);
	}

	private static int packMergeCacheKey(int objectId, int type, int orientation, boolean night) {
		int key = objectId * 31 + type;
		key = key * 31 + orientation;
		return key ^ (night ? 668265263 : 0);
	}

	public static void releaseCaches() {
		preloadComplete = false;
		registeredPhaseWindows.clear();
		synchronized (mergedModelCache) {
			mergedModelCache.clear();
			mergedModelCache.trimToSize();
		}
		synchronized (overlayStackCache) {
			overlayStackCache.clear();
			overlayStackCache.trimToSize();
		}
		ModelDefinition.release();
	}

	private static final class OverlayStacks {
		static final OverlayStacks EMPTY = new OverlayStacks(null, null);

		final Model day;
		final Model night;

		OverlayStacks(Model day, Model night) {
			this.day = day;
			this.night = night;
		}
	}

	public static final class TimeOfDayMarker {
		public LightTimeOfDay timeOfDay;
		public LightTimeOfDay timeOfDayOff;
		public boolean dayNightOnly;
		public boolean appliedAtUpload;

		public static TimeOfDayMarker from(ModelReplacement replacement, boolean appliedAtUpload) {
			TimeOfDayMarker marker = new TimeOfDayMarker();
			marker.timeOfDay = replacement.timeOfDay;
			marker.timeOfDayOff = replacement.timeOfDayOff;
			marker.dayNightOnly = replacement.dayNightOnly;
			marker.appliedAtUpload = appliedAtUpload;
			return marker;
		}
	}
}
