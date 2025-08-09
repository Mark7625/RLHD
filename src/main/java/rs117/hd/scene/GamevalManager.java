package rs117.hd.scene;

import com.google.gson.TypeAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.HdPlugin;
import rs117.hd.utils.FileWatcher;
import rs117.hd.utils.GsonUtils;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.utils.ResourcePath.path;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

@Slf4j
@Singleton
public class GamevalManager {

	private static final ResourcePath GAMEVAL_PATH = Props
		.getFile("rlhd.gameval-path", () -> path(GamevalManager.class, "gamevals.json"));

	private static final String NPC_KEY = "npcs";
	private static final String OBJECT_KEY = "objects";
	private static final String ANIM_KEY = "anims";
	private static final String SPOTANIM_KEY = "spotanims";

	private static final long KEEP_ALIVE_TIMEOUT_MS = 30 * 1000;
	private static final long CLEANUP_CHECK_INTERVAL_MS = 1000;

	private static final Map<String, Map<String, Integer>> GAMEVALS = new HashMap<>();

	private final AtomicInteger activeLocks = new AtomicInteger(0);
	private volatile long lastAccessTime = System.currentTimeMillis();

	@Inject
	private HdPlugin plugin;
	private FileWatcher.UnregisterCallback fileWatcher;
	private ScheduledExecutorService cleanupExecutor;
	private ScheduledFuture<?> cleanupTask;

	static {
		clearGamevals();
	}

	public void startUp() throws IOException {
		initializeCleanupExecutor();
		initializeFileWatcher();
	}

	public void shutDown() {
		cleanupFileWatcher();
		cleanupScheduledExecutor();
		clearGamevals();
	}

	private void initializeCleanupExecutor() {
		cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "GamevalManager-Cleanup");
			t.setDaemon(true);
			return t;
		});

		cleanupTask = cleanupExecutor.scheduleWithFixedDelay(
			this::checkForCleanup,
			0,
			CLEANUP_CHECK_INTERVAL_MS,
			TimeUnit.MILLISECONDS
		);
	}

	private void initializeFileWatcher() {
		fileWatcher = GAMEVAL_PATH.watch(this::loadGamevalsFromFile);
	}

	/**
	 * Creates a file watcher that automatically manages GamevalManager locks.
	 * The provided lambda will be wrapped with try-with-resources for Gamevals.
	 */
	public FileWatcher.UnregisterCallback watchWithGamevals(ResourcePath path, BiConsumer<ResourcePath, Boolean> handler) {
		return path.watch((watchedPath, first) -> {
			try (Gamevals gamevals = acquire()) {
				lock();
				handler.accept(watchedPath, first);
				unlock();
			}
		});
	}

	/**
	 * Creates a file watcher that automatically manages GamevalManager locks.
	 * The provided lambda will be wrapped with try-with-resources for Gamevals.
	 */
	public FileWatcher.UnregisterCallback watchWithGamevals(ResourcePath path, Consumer<ResourcePath> handler) {
		return path.watch((watchedPath, first) -> {
			try (Gamevals gamevals = acquire()) {
				handler.accept(watchedPath);
			}
		});
	}

	private void cleanupFileWatcher() {
		if (fileWatcher != null) {
			fileWatcher.unregister();
			fileWatcher = null;
		}
	}

	private void cleanupScheduledExecutor() {
		if (cleanupTask != null) {
			cleanupTask.cancel(false);
			cleanupTask = null;
		}

		if (cleanupExecutor != null) {
			cleanupExecutor.shutdown();
			try {
				if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
					cleanupExecutor.shutdownNow();
				}
			} catch (InterruptedException e) {
				cleanupExecutor.shutdownNow();
				Thread.currentThread().interrupt();
			}
			cleanupExecutor = null;
		}
	}

	public Gamevals acquire() {
		ensureGamevalsLoaded();
		return createGamevals(false);
	}

	public Gamevals acquireReverse() {
		ensureGamevalsLoaded();
		return createGamevals(true);
	}

	public void lock() {
		ensureGamevalsLoaded();
		incrementLock("locked");
	}

	public void unlock() {
		updateLastAccessTime();
		decrementLock("unlocked");
	}

	private void ensureGamevalsLoaded() {
		if (areGamevalsEmpty()) {
			log.info("Gamevals are empty, reloading from file");
			loadGamevalsFromFile(GAMEVAL_PATH, false);
		}
	}

	private Gamevals createGamevals(boolean isReverseLock) {
		String lockType = isReverseLock ? "reverse" : "temporary";
		incrementLock("acquired (" + lockType + ")");
		return new Gamevals(isReverseLock);
	}

	private void incrementLock(String action) {
		int newLockCount = activeLocks.incrementAndGet();
		updateLastAccessTime();
		log.debug("Gamevals {}: {} active locks", action, newLockCount);
	}

	private void decrementLock(String action) {
		int newLockCount = activeLocks.updateAndGet(current -> Math.max(0, current - 1));
		updateLastAccessTime();
		log.debug("Gamevals {}: {} active locks remaining", action, newLockCount);
	}

	private void updateLastAccessTime() {
		lastAccessTime = System.currentTimeMillis();
	}

	private void loadGamevalsFromFile(ResourcePath path, boolean isInitialLoad) {
		try {
			String loadType = isInitialLoad ? "Loading" : "Reloading";
			log.info("{} gamevals from file", loadType);

			Map<String, Map<String, Integer>> gamevals = path
				.loadJson(plugin.getGson(), new TypeToken<Map<String, Map<String, Integer>>>() {}.getType());

			GAMEVALS.replaceAll((k, v) -> gamevals.getOrDefault(k, Collections.emptyMap()));
			updateLastAccessTime();

			log.info("{} gameval mappings: {} NPCs, {} Objects, {} Anims, {} Spotanims",
				loadType.toLowerCase(),
				gamevals.getOrDefault(NPC_KEY, Collections.emptyMap()).size(),
				gamevals.getOrDefault(OBJECT_KEY, Collections.emptyMap()).size(),
				gamevals.getOrDefault(ANIM_KEY, Collections.emptyMap()).size(),
				gamevals.getOrDefault(SPOTANIM_KEY, Collections.emptyMap()).size());
		} catch (IOException ex) {
			log.error("Failed to load gamevals:", ex);
		}
	}

	private static void clearGamevals() {
		log.info("Clearing gamevals from memory");
		GAMEVALS.put(NPC_KEY, Collections.emptyMap());
		GAMEVALS.put(OBJECT_KEY, Collections.emptyMap());
		GAMEVALS.put(ANIM_KEY, Collections.emptyMap());
		GAMEVALS.put(SPOTANIM_KEY, Collections.emptyMap());
	}

	private boolean areGamevalsEmpty() {
		return GAMEVALS.get(NPC_KEY).isEmpty() &&
			   GAMEVALS.get(OBJECT_KEY).isEmpty() &&
			   GAMEVALS.get(ANIM_KEY).isEmpty() &&
			   GAMEVALS.get(SPOTANIM_KEY).isEmpty();
	}

	private void checkForCleanup() {
		try {
			if (areGamevalsEmpty()) {
				return;
			}

			long currentTime = System.currentTimeMillis();
			long timeSinceLastAccess = currentTime - lastAccessTime;
			int activeLocksCount = activeLocks.get();
			long timeUntilCleanup = KEEP_ALIVE_TIMEOUT_MS - timeSinceLastAccess;

			logCleanupStatus(activeLocksCount, timeSinceLastAccess, timeUntilCleanup);

			if (activeLocksCount <= 0 && timeSinceLastAccess > KEEP_ALIVE_TIMEOUT_MS) {
				log.info("Cleaning up gamevals after {} ms of inactivity", timeSinceLastAccess);
				clearGamevals();
			}
		} catch (Exception e) {
			log.error("Error during gameval cleanup check", e);
		}
	}

	private void logCleanupStatus(int activeLocksCount, long timeSinceLastAccess, long timeUntilCleanup) {
		if (activeLocksCount > 0) {
			log.debug("Gamevals in use: {} active locks, last access {} ms ago", activeLocksCount, timeSinceLastAccess);
		} else if (timeUntilCleanup > 0) {
			log.debug("Gamevals idle: {} ms until cleanup (last access {} ms ago)", timeUntilCleanup, timeSinceLastAccess);
		}
	}

	// Getter methods for gameval maps
	public Map<String, Integer> getNpcs() {
		updateLastAccessTime();
		return GAMEVALS.get(NPC_KEY);
	}

	public Map<String, Integer> getObjects() {
		updateLastAccessTime();
		return GAMEVALS.get(OBJECT_KEY);
	}

	public Map<String, Integer> getAnims() {
		updateLastAccessTime();
		return GAMEVALS.get(ANIM_KEY);
	}

	public Map<String, Integer> getSpotanims() {
		updateLastAccessTime();
		return GAMEVALS.get(SPOTANIM_KEY);
	}

	// Getter methods for specific IDs
	public int getNpcId(String name) {
		updateLastAccessTime();
		return getNpcs().get(name);
	}

	public int getObjectId(String name) {
		updateLastAccessTime();
		return getObjects().get(name);
	}

	public int getAnimId(String name) {
		updateLastAccessTime();
		return getAnims().get(name);
	}

	public int getSpotanimId(String name) {
		updateLastAccessTime();
		return getSpotanims().get(name);
	}

	// Getter methods for names by ID
	public String getNpcName(int id) {
		updateLastAccessTime();
		return getName(NPC_KEY, id);
	}

	public String getObjectName(int id) {
		updateLastAccessTime();
		return getName(OBJECT_KEY, id);
	}

	public String getAnimName(int id) {
		updateLastAccessTime();
		return getName(ANIM_KEY, id);
	}

	public String getSpotanimName(int id) {
		updateLastAccessTime();
		return getName(SPOTANIM_KEY, id);
	}

	private String getName(String key, int id) {
		return GAMEVALS
			.get(key)
			.entrySet()
			.stream()
			.filter(e -> e.getValue() == id)
			.map(Map.Entry::getKey)
			.findFirst()
			.orElse(null);
	}

	/**
	 * Gamevals wrapper that automatically manages keep-alive locks
	 */
	public class Gamevals implements AutoCloseable {
		private final boolean isReverseLock;
		private boolean closed = false;

		private Gamevals(boolean isReverseLock) {
			this.isReverseLock = isReverseLock;
		}

		@Override
		public void close() {
			if (!closed) {
				closed = true;
				decrementLock("released");
			}
		}
	}

	@Slf4j
	@RequiredArgsConstructor
	private abstract static class GamevalAdapter extends TypeAdapter<HashSet<Integer>> {
		private final String key;

		@Override
		public HashSet<Integer> read(JsonReader in) throws IOException {
			var map = GAMEVALS.get(key);
			HashSet<Integer> result = new HashSet<>();

			in.beginArray();
			while (in.hasNext()) {
				var type = in.peek();
				switch (type) {
					case NUMBER: {
						int id = in.nextInt();
						if (id != -1) {
							log.debug("Adding raw {} ID: {} at {}. Should be replaced with a gameval.", key, id, GsonUtils.location(in));
						}
						result.add(id);
						break;
					}
					case STRING:
						String name = in.nextString();
						Integer id = map.get(name);
						if (id == null) {
							String suggestion = findSuggestion(name);
							log.error("Missing {} gameval: {}{} at {}", key, name, suggestion, GsonUtils.location(in), new Throwable());
						} else {
							result.add(id);
						}
						break;
					default:
						log.error("Unexpected {} gameval type: {} at {}", key, type, GsonUtils.location(in), new Throwable());
						break;
				}
			}
			in.endArray();

			return result;
		}

		private String findSuggestion(String name) {
			for (var gamevalMapEntry : GAMEVALS.entrySet()) {
				if (gamevalMapEntry.getValue().get(name) != null) {
					return String.format(", did you mean to match %s?", gamevalMapEntry.getKey());
				}
			}
			return "";
		}

		@Override
		public void write(JsonWriter out, HashSet<Integer> ids) throws IOException {
			var remainingIds = new ArrayList<>(ids);
			var map = GAMEVALS.get(key);
			var names = map.entrySet().stream()
				.filter(e -> remainingIds.remove(e.getValue()))
				.map(Map.Entry::getKey)
				.sorted()
				.toArray(String[]::new);

			if (!remainingIds.isEmpty()) {
				remainingIds.sort(Integer::compareTo);
				log.warn(
					"Exporting IDs with no corresponding gamevals: {}", remainingIds.stream()
						.filter(i -> i != -1)
						.map(Object::toString)
						.collect(Collectors.joining(", "))
				);
			}

			out.beginArray();
			for (var id : remainingIds) {
				out.value(id);
			}
			for (var name : names) {
				out.value(name);
			}
			out.endArray();
		}
	}

	public static class NpcAdapter extends GamevalAdapter {
		public NpcAdapter() {
			super(NPC_KEY);
		}
	}

	public static class ObjectAdapter extends GamevalAdapter {
		public ObjectAdapter() {
			super(OBJECT_KEY);
		}
	}

	public static class AnimationAdapter extends GamevalAdapter {
		public AnimationAdapter() {
			super(ANIM_KEY);
		}
	}

	public static class SpotanimAdapter extends GamevalAdapter {
		public SpotanimAdapter() {
			super(SPOTANIM_KEY);
		}
	}
}
