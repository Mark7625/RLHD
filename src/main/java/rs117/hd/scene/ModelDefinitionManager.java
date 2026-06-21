package rs117.hd.scene;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import rs117.hd.HdPlugin;
import rs117.hd.scene.model_overrides.ModelDefinition;
import rs117.hd.utils.FileWatcher;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.utils.ResourcePath.path;

@Slf4j
@Singleton
public class ModelDefinitionManager {
	private static final ResourcePath REPLACEMENT_MODELS_PATH = Props
		.getFile("rlhd.replacement-models-path", () -> path(ModelDefinitionManager.class, "replacement_models.json"));

	public static final Map<String, ModelDefinition> MODEL_MAP = new HashMap<>();

	@Inject
	private HdPlugin plugin;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ModelOverrideManager modelOverrideManager;

	private FileWatcher.UnregisterCallback fileWatcher;

	public void startUp() {
		fileWatcher = REPLACEMENT_MODELS_PATH.watch((path, first) -> clientThread.invoke(() -> {
			try {
				load(path);
				if (!first)
					applyHotReload();
			} catch (IOException ex) {
				log.error("Failed to load replacement models:", ex);
			}
		}));

		if (!REPLACEMENT_MODELS_PATH.isFileSystemResource() && Props.DEVELOPMENT) {
			log.info(
				"Hot reload is disabled for {}. Set -Drlhd.resource-path=src/main/resources to watch for changes.",
				REPLACEMENT_MODELS_PATH
			);
		}
	}

	public void shutDown() {
		if (fileWatcher != null)
			fileWatcher.unregister();
		fileWatcher = null;
		MODEL_MAP.clear();
	}

	public void reloadFromDisk() {
		if (!client.isClientThread()) {
			clientThread.invoke(this::reloadFromDisk);
			return;
		}

		try {
			load(REPLACEMENT_MODELS_PATH);
			applyHotReload();
			log.info("Reloaded replacement models from {}", REPLACEMENT_MODELS_PATH);
		} catch (IOException ex) {
			log.error("Failed to reload replacement models:", ex);
		}
	}

	private void applyHotReload() {
		ModelReplacer.releaseCaches();
		modelOverrideManager.reloadFromDisk();
	}

	private void load(ResourcePath path) throws IOException {
		ModelDefinition[] models = path.loadJson(plugin.getGson(), ModelDefinition[].class);
		if (models == null)
			throw new IOException("Empty or invalid: " + path);

		Map<String, ModelDefinition> next = new HashMap<>();
		Set<String> names = new HashSet<>();
		for (ModelDefinition model : models) {
			if (model.name == null || model.name.isEmpty())
				throw new IOException("Replacement model is missing a name in " + path);

			if (!names.add(model.name))
				throw new IOException("Duplicate replacement model name '" + model.name + "' in " + path);

			if (model.modelIds == null || model.modelIds.length == 0)
				throw new IOException("Replacement model '" + model.name + "' is missing modelIds in " + path);

			next.put(model.name, model);
		}

		MODEL_MAP.clear();
		MODEL_MAP.putAll(next);
		log.debug("Loaded {} replacement models", MODEL_MAP.size());
	}
}
