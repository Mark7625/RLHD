package rs117.hd.scene;

import java.io.IOException;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import rs117.hd.HdPlugin;
import rs117.hd.opengl.uniforms.UBOLavaTypes;
import rs117.hd.renderer.zone.SceneManager;
import rs117.hd.scene.lava_types.LavaType;
import rs117.hd.utils.FileWatcher;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.utils.ResourcePath.path;

@Slf4j
@Singleton
public class LavaTypeManager {
	private static final ResourcePath LAVA_TYPES_PATH = Props
		.getFile("rlhd.lava-types-path", () -> path(LavaTypeManager.class, "lava_types.json"));

	@Inject
	private ClientThread clientThread;

	@Inject
	private HdPlugin plugin;

	@Inject
	private SceneManager sceneManager;

	public static LavaType[] LAVA_TYPES = {};

	public UBOLavaTypes uboLavaTypes;

	private FileWatcher.UnregisterCallback fileWatcher;

	public void startUp() {
		fileWatcher = LAVA_TYPES_PATH.watch((path, first) -> clientThread.invoke(() -> {
			try {
				sceneManager.getLoadingLock().lock();
				sceneManager.completeAllStreaming();

				var rawLavaTypes = path.loadJson(plugin.getGson(), LavaType[].class);
				if (rawLavaTypes == null)
					throw new IOException("Empty or invalid: " + path);
				log.debug("Loaded {} lava types", rawLavaTypes.length);

				var lavaTypes = new LavaType[rawLavaTypes.length + 1];
				lavaTypes[0] = LavaType.NONE;
				System.arraycopy(rawLavaTypes, 0, lavaTypes, 1, rawLavaTypes.length);

				var oldLavaTypes = LAVA_TYPES;
				for (int i = 0; i < lavaTypes.length; i++)
					lavaTypes[i].normalize(i);

				LAVA_TYPES = lavaTypes;

				if (uboLavaTypes != null)
					uboLavaTypes.destroy();
				uboLavaTypes = new UBOLavaTypes(lavaTypes);

				if (first)
					return;

				boolean indicesChanged = oldLavaTypes == null || oldLavaTypes.length != lavaTypes.length;
				if (!indicesChanged) {
					for (int i = 0; i < lavaTypes.length; i++) {
						if (!lavaTypes[i].name.equals(oldLavaTypes[i].name)) {
							indicesChanged = true;
							break;
						}
					}
				}

				if (indicesChanged)
					plugin.recompilePrograms();
			} catch (IOException ex) {
				log.error("Failed to load lava types:", ex);
			} finally {
				sceneManager.getLoadingLock().unlock();
				log.trace("loadingLock unlocked - holdCount: {}", sceneManager.getLoadingLock().getHoldCount());
			}
		}));
	}

	public void shutDown() {
		if (fileWatcher != null)
			fileWatcher.unregister();
		fileWatcher = null;

		if (uboLavaTypes != null)
			uboLavaTypes.destroy();
		uboLavaTypes = null;

		LAVA_TYPES = new LavaType[0];
	}

	public void restart() {
		shutDown();
		startUp();
	}
}
