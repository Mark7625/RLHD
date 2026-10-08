package rs117.hd.renderer.zone;

import com.google.inject.Injector;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import org.lwjgl.BufferUtils;
import rs117.hd.HdPlugin;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.lights.Light;
import rs117.hd.utils.DestructibleHandler;
import rs117.hd.utils.ResourcePath;
import rs117.hd.utils.buffer.GLBuffer;
import rs117.hd.utils.buffer.GLTextureBuffer;
import rs117.hd.utils.jobs.GenericJob;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Keeps hold of static, opaque-only zone geometry for chunks that have fallen outside the live/extended scene
 * window, so it can be redrawn as a distant backdrop to give the illusion of a much larger draw distance.
 * <p>
 * Entries are kept GPU-resident in a bounded in-memory LRU cache, and are additionally persisted to disk, one
 * gzip-compressed file per 64x64 tile region (matching the game's own region grid, under
 * {@code 117hd/backdrop-cache}), so that previously seen terrain remains available as a backdrop across client
 * sessions without needing thousands of tiny per-zone files. Cached zones are a frozen, alpha-free snapshot: no
 * shadows, no dynamic objects, and no later edits to the real scene are reflected in them.
 */
@Slf4j
@Singleton
public class BackdropZoneCache {
	private static final ResourcePath CACHE_DIR = HdPlugin.PLUGIN_DIR.resolve("backdrop-cache");
	private static final int FORMAT_MAGIC = 0x48444243; // "HDBC"
	private static final int FORMAT_VERSION = 3; // v3: added a per-zone static point light snapshot
	private static final float[] EMPTY_FLOAT_ARRAY = new float[0];
	// Matches CHUNK_WORLD_UNITS in LoginScreenBackdropRenderer - 8 tiles * 128 local units/tile.
	private static final int CHUNK_WORLD_UNITS = 1024;
	// A radius of just 8 chunks already produces close to 1000 candidate chunks in one search, and radius can be
	// configured much higher - if the cache can't hold everything currently in demand, LRU eviction causes
	// permanent visible gaps (not just flicker), since there's nowhere to keep the overflow resident.
	private static final int MAX_MEMORY_ZONES = 2048;
	private static final long MAX_DISK_BYTES = 512L * 1024 * 1024;
	private static final int AUTO_FLUSH_THRESHOLD = 16;

	// Absolute last-resort cap in case a caller ever miscalculates a byte count - a single zone's geometry should
	// never remotely approach this, so hitting it means something upstream is wrong and should be investigated,
	// not that this is a legitimate size to allocate.
	private static final int MAX_READBACK_BYTES = 64 * 1024 * 1024;

	@Inject
	private Injector injector;

	@Inject
	private ClientThread clientThread;

	@Inject
	private HdPlugin plugin;

	private final LinkedHashMap<Long, Zone> memCache = new LinkedHashMap<Long, Zone>(256, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, Zone> eldest) {
			if (size() <= MAX_MEMORY_ZONES)
				return false;
			DestructibleHandler.queueDestruction(eldest.getValue());
			return true;
		}
	};

	private List<ZoneRecord> pendingWrites = new ArrayList<>();
	private final HashSet<Long> cachedChunksThisSession = new HashSet<>();

	// Zones awaiting their (client-thread-only) GPU buffer readback, drained a few at a time per frame by
	// processQueuedCaching() instead of all at once, to avoid hanging a frame when hundreds of zones need
	// caching at once (e.g. right after login or a teleport).
	private final ArrayDeque<PendingCache> cacheQueue = new ArrayDeque<>();

	// Regions (64x64 tiles, matching both the game's own region grid and how disk files are now laid out) already
	// requested for load this session, whether or not a file existed - prevents re-reading the same region file,
	// or re-queuing a load for a region already known to have no data, every single frame it stays in range.
	private final HashSet<Long> loadedRegions = new HashSet<>();

	// Which specific chunk keys are already known to be persisted on disk, per region (keyed the same way as
	// memCache/regionFile). Lazily populated the first time a region is touched by processQueuedCaching(), and
	// kept up to date as new chunks are written - lets a region that was only partially walked on an earlier visit
	// still pick up newly-explored chunks on a later visit, instead of the old region-file-exists() check treating
	// "this region has a file at all" as "every chunk in it is already saved", which silently dropped any chunk
	// beyond whatever was captured the first time the file was created.
	private final Map<Long, HashSet<Long>> knownDiskChunksByRegion = new HashMap<>();

	private HashSet<Long> getKnownDiskChunks(int regionX, int regionZ) {
		long regionKey = key(regionX, regionZ);
		return knownDiskChunksByRegion.computeIfAbsent(regionKey, k -> {
			HashSet<Long> chunks = new HashSet<>();
			ResourcePath file = regionFile(regionX, regionZ);
			if (file.exists()) {
				List<ZoneRecord> existing = readRegionFile(file);
				if (existing != null)
					for (ZoneRecord r : existing)
						chunks.add(key(r.chunkX, r.chunkZ));
			}
			return chunks;
		});
	}

	// All region file reads/writes are serialized through this, since a read-modify-write merge of a region file
	// would otherwise race if two write batches or a write and a read ever touched the same region concurrently.
	private final Object regionFileLock = new Object();

	public static long key(int x, int z) {
		return ((long) (x + 0x100000) << 32) | ((long) (z + 0x100000) & 0xFFFFFFFFL);
	}

	/**
	 * Called from {@link SceneManager#swapScene} right before a culled zone would otherwise be destroyed.
	 * Returns true if the zone was adopted by the cache, in which case the caller must not destroy it.
	 */
	public boolean offer(SceneContext oldSceneContext, int zx, int zz, Zone zone) {
		if (!plugin.configBackdropCaching)
			return false;
		if (oldSceneContext == null || oldSceneContext.sceneBase == null || oldSceneContext.scene.isInstance())
			return false;
		if (!zone.initialized || zone.uploadJob != null || zone.dirty || zone.rebuild)
			return false;
		if (zone.vboO == null)
			return false;
		if (vertexCount(zone) == 0)
			return false;

		int chunkX = zx - (oldSceneContext.sceneOffset >> 3) + (oldSceneContext.sceneBase[0] >> 3);
		int chunkZ = zz - (oldSceneContext.sceneOffset >> 3) + (oldSceneContext.sceneBase[1] >> 3);

		// Backdrops never render transparent geometry, so free it up front to save VRAM. This is cheap (no GPU
		// readback), so it's fine to do immediately rather than deferring it like the actual disk write below.
		if (zone.vboA != null) {
			zone.vboA.destroy();
			zone.vboA = null;
		}
		if (zone.glVaoA != 0) {
			glDeleteVertexArrays(zone.glVaoA);
			zone.glVaoA = 0;
		}
		zone.alphaModels.clear();
		zone.sizeA = 0;
		zone.bufLenA = 0;
		zone.isBackdrop = true;

		long key = key(chunkX, chunkZ);
		Zone old = memCache.remove(key);
		if (old != null && old != zone)
			DestructibleHandler.queueDestruction(old);
		memCache.put(key, zone);
		cachedChunksThisSession.add(key);

		cacheQueue.add(new PendingCache(chunkX, chunkZ, zone, captureLights(oldSceneContext, chunkX, chunkZ)));
		log.debug("Adopted backdrop zone for chunk ({}, {})", chunkX, chunkZ);
		return true;
	}

	/**
	 * Called from {@link SceneManager#swapScene} for every zone that's part of the newly active scene, live or
	 * not, so that terrain gets persisted to disk the moment it's first seen this session - on login, on
	 * teleport, on a fresh region load - rather than only once it's walked away from and evicted. Unlike
	 * {@link #offer}, the zone keeps rendering live as normal; this only schedules a copy of its opaque geometry
	 * to be written to disk, and skips chunks already written this session to avoid redoing the work every swap.
	 */
	public void cacheLiveZone(SceneContext sceneContext, int x, int z, Zone zone) {
		if (!plugin.configBackdropCaching)
			return;
		if (sceneContext == null || sceneContext.sceneBase == null || sceneContext.scene.isInstance())
			return;
		if (!zone.initialized || zone.uploadJob != null || zone.dirty || zone.rebuild)
			return;
		if (zone.vboO == null || vertexCount(zone) == 0)
			return;

		int chunkX = x - (sceneContext.sceneOffset >> 3) + (sceneContext.sceneBase[0] >> 3);
		int chunkZ = z - (sceneContext.sceneOffset >> 3) + (sceneContext.sceneBase[1] >> 3);

		if (!cachedChunksThisSession.add(key(chunkX, chunkZ)))
			return;

		cacheQueue.add(new PendingCache(chunkX, chunkZ, zone, captureLights(sceneContext, chunkX, chunkZ)));
	}

	/**
	 * Flat-packs whichever currently-active point lights fall within this chunk's 8x8 tile footprint - 8 floats
	 * per light: zone-local x/y/z (matching the scale and origin convention of the zone's own vertex data),
	 * r/g/b, radius, strength. Cheap CPU-side filtering only, done synchronously here (rather than deferred like
	 * the GPU vertex/face readback below) since a scene's light list can change from frame to frame and this
	 * needs to reflect the zone's lights at the moment it was actually adopted/seen, not whatever happens to
	 * still be active several frames later when the GPU readback runs.
	 */
	private static float[] captureLights(SceneContext sceneContext, int chunkX, int chunkZ) {
		if (sceneContext == null || sceneContext.lights.isEmpty())
			return EMPTY_FLOAT_ARRAY;

		float baseX = chunkX * CHUNK_WORLD_UNITS;
		float baseZ = chunkZ * CHUNK_WORLD_UNITS;

		List<Light> inZone = new ArrayList<>();
		for (Light light : sceneContext.lights) {
			if (!light.visible || light.strength <= 0 || light.radius <= 0)
				continue;
			int lightChunkX = Math.floorDiv((int) light.pos[0], CHUNK_WORLD_UNITS);
			int lightChunkZ = Math.floorDiv((int) light.pos[2], CHUNK_WORLD_UNITS);
			if (lightChunkX == chunkX && lightChunkZ == chunkZ)
				inZone.add(light);
		}
		if (inZone.isEmpty())
			return EMPTY_FLOAT_ARRAY;

		float[] data = new float[inZone.size() * 8];
		int i = 0;
		for (Light light : inZone) {
			data[i++] = light.pos[0] - baseX;
			data[i++] = light.pos[1];
			data[i++] = light.pos[2] - baseZ;
			data[i++] = light.color[0];
			data[i++] = light.color[1];
			data[i++] = light.color[2];
			data[i++] = light.radius;
			data[i++] = light.strength;
		}
		return data;
	}

	private static final int CACHE_QUEUE_BATCH_SIZE = 4;

	/**
	 * Drains a small batch of the queue built up by {@link #offer} and {@link #cacheLiveZone} each frame. The
	 * actual GPU buffer readback can only happen on the client/GL thread, so it can't be pushed to a background
	 * job the way the disk I/O already is - instead, it's spread across many frames (a handful of zones each)
	 * rather than reading back everything in one go, which previously caused a frame hang on login/teleport
	 * when a few hundred zones all needed reading back synchronously within a single scene swap.
	 */
	public void processQueuedCaching() {
		if (!plugin.configBackdropCaching) {
			cacheQueue.clear();
			return;
		}

		for (int i = 0; i < CACHE_QUEUE_BATCH_SIZE && !cacheQueue.isEmpty(); i++) {
			PendingCache p = cacheQueue.poll();
			Zone zone = p.zone;
			// Several frames may have passed since this was queued - re-validate that the zone hasn't since
			// been destroyed, rebuilt, or otherwise changed before reading from its GPU buffers.
			if (!zone.initialized || zone.uploadJob != null || zone.dirty || zone.rebuild || zone.vboO == null)
				continue;

			// If this exact chunk is already known to be saved (from a previous session, or an earlier write
			// this session), don't bother reading it back and rewriting it - outdated is fine, the goal here is
			// just to avoid redundant GPU readback/disk I/O for ground already cached. Checked per-chunk, not
			// per-region file, so a region that was only partially walked before can still pick up new chunks.
			int regionX = Math.floorDiv(p.chunkX, 8), regionZ = Math.floorDiv(p.chunkZ, 8);
			HashSet<Long> knownChunks = getKnownDiskChunks(regionX, regionZ);
			long chunkKey = key(p.chunkX, p.chunkZ);
			if (knownChunks.contains(chunkKey))
				continue;

			int vertexCount = vertexCount(zone);
			if (vertexCount == 0)
				continue;

			knownChunks.add(chunkKey);
			queueWrite(buildRecord(p.chunkX, p.chunkZ, zone, vertexCount, p.lightData));
		}
	}

	/**
	 * Computes the number of opaque vertices actually written to a zone's buffer directly from the cumulative
	 * level offsets set by {@link SceneUploader} during upload, rather than from {@link Zone#bufLen}, which is
	 * only populated by {@link Zone#unmap()} - a call that happens on an inconsistent delay (or not at all by
	 * the time we need it) via unrelated code paths elsewhere, and isn't used anywhere in actual rendering.
	 */
	private static int vertexCount(Zone zone) {
		if (zone.vboO == null || zone.levelOffsets == null || zone.levelOffsets.length == 0)
			return 0;
		int totalInts = zone.levelOffsets[zone.levelOffsets.length - 1];
		int vertexCount = totalInts * 4 / Zone.VERT_SIZE;

		// Hard safety bound: never attempt to read more than what the GPU buffer was actually allocated to hold,
		// no matter what levelOffsets computed to.
		int maxVertices = (int) (zone.vboO.size / Zone.VERT_SIZE);
		return Math.max(0, Math.min(vertexCount, maxVertices));
	}

	private ZoneRecord buildRecord(int chunkX, int chunkZ, Zone zone, int vertexCount, float[] lightData) {
		byte[] vertexBytes = readBufferBytes(zone.vboO.id, vertexCount * Zone.VERT_SIZE);
		int faceBytesLen = zone.tboF != null
			? (int) Math.min((long) zone.sizeF * Zone.TEXTURE_SIZE, zone.tboF.size)
			: 0;
		byte[] faceBytes = faceBytesLen > 0 ? readBufferBytes(zone.tboF.id, faceBytesLen) : new byte[0];

		ZoneRecord r = new ZoneRecord();
		r.chunkX = chunkX;
		r.chunkZ = chunkZ;
		r.vertexBytes = vertexBytes;
		r.faceBytes = faceBytes;
		r.levelOffsets = zone.levelOffsets.clone();
		r.rids = copy2D(zone.rids);
		r.roofStart = copy2D(zone.roofStart);
		r.roofEnd = copy2D(zone.roofEnd);
		r.hasWater = zone.hasWater;
		r.onlyWater = zone.onlyWater;
		r.lightData = lightData != null ? lightData : EMPTY_FLOAT_ARRAY;
		return r;
	}

	private void queueWrite(ZoneRecord record) {
		pendingWrites.add(record);

		// Don't let an entire scene's worth of zones (hundreds, on a fresh login/teleport) pile up as live byte[]
		// arrays in memory at once waiting for a single end-of-swap flush - drain early and often instead.
		if (pendingWrites.size() >= AUTO_FLUSH_THRESHOLD)
			flushPendingWrites();
	}

	/**
	 * Flushes any zones adopted since the last flush as a single low-priority background write, so that a scene
	 * swap which culls hundreds of zones at once can't flood the shared job queue and starve out the
	 * {@code ZoneUploadJob}s the next scene actually needs in order to finish loading.
	 */
	public void flushPendingWrites() {
		if (pendingWrites.isEmpty())
			return;

		List<ZoneRecord> batch = pendingWrites;
		pendingWrites = new ArrayList<>();
		log.debug("Flushing {} pending backdrop zone write(s) to {}", batch.size(), CACHE_DIR);

		GenericJob
			.build("BackdropZoneCache::write", task -> writeBatch(batch))
			.queue(false);
	}

	private void writeBatch(List<ZoneRecord> batch) {
		Map<Long, List<ZoneRecord>> byRegion = new HashMap<>();
		for (ZoneRecord r : batch) {
			long regionKey = key(Math.floorDiv(r.chunkX, 8), Math.floorDiv(r.chunkZ, 8));
			byRegion.computeIfAbsent(regionKey, k -> new ArrayList<>()).add(r);
		}

		synchronized (regionFileLock) {
			for (List<ZoneRecord> zonesForRegion : byRegion.values()) {
				ZoneRecord first = zonesForRegion.get(0);
				int regionX = Math.floorDiv(first.chunkX, 8);
				int regionZ = Math.floorDiv(first.chunkZ, 8);
				mergeAndWriteRegion(regionX, regionZ, zonesForRegion);
			}
			pruneDiskCacheIfNeeded();
		}
	}

	private void mergeAndWriteRegion(int regionX, int regionZ, List<ZoneRecord> newZones) {
		ResourcePath file = regionFile(regionX, regionZ);

		LinkedHashMap<Long, ZoneRecord> merged = new LinkedHashMap<>();
		if (file.exists()) {
			List<ZoneRecord> existing = readRegionFile(file);
			if (existing != null)
				for (ZoneRecord r : existing)
					merged.put(key(r.chunkX, r.chunkZ), r);
		}
		for (ZoneRecord r : newZones)
			merged.put(key(r.chunkX, r.chunkZ), r);

		writeRegionFile(file, regionX, regionZ, merged.values());
	}

	private void writeRegionFile(ResourcePath file, int regionX, int regionZ, Collection<ZoneRecord> zones) {
		try {
			CACHE_DIR.mkdirs();
			File tempFile = File.createTempFile("r_" + regionX + "_" + regionZ, ".tmp", CACHE_DIR.toFile());

			try (
				DataOutputStream out = new DataOutputStream(
					new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(tempFile)))
				)
			) {
				out.writeInt(FORMAT_MAGIC);
				out.writeInt(FORMAT_VERSION);
				out.writeInt(regionX);
				out.writeInt(regionZ);
				out.writeInt(zones.size());
				for (ZoneRecord r : zones)
					writeZoneRecord(out, r);
			}

			Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			log.debug("Wrote backdrop region file: {} ({} zone(s))", file, zones.size());
		} catch (Throwable ex) {
			log.warn("Failed to write backdrop region file for region ({}, {}):", regionX, regionZ, ex);
		}
	}

	/**
	 * Returns the cached zone for the given chunk, if it's already resident in memory. If it's not resident,
	 * but caching is enabled, its region's file may be loaded in the background for next time.
	 */
	public Zone get(int chunkX, int chunkZ) {
		Zone zone = memCache.get(key(chunkX, chunkZ));
		if (zone != null)
			return zone;
		if (!plugin.configBackdropCaching)
			return null;

		ensureRegionLoaded(Math.floorDiv(chunkX, 8), Math.floorDiv(chunkZ, 8));
		return null;
	}

	/**
	 * Bulk-loads every cached zone in the given chunk range (typically the live scene window plus the configured
	 * backdrop radius, called once per scene load) instead of waiting for the per-frame backdrop search to
	 * discover misses one chunk at a time.
	 */
	public void prefetchArea(int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
		if (!plugin.configBackdropCaching)
			return;

		int minRegionX = Math.floorDiv(minChunkX, 8);
		int maxRegionX = Math.floorDiv(maxChunkX, 8);
		int minRegionZ = Math.floorDiv(minChunkZ, 8);
		int maxRegionZ = Math.floorDiv(maxChunkZ, 8);

		for (int regionX = minRegionX; regionX <= maxRegionX; regionX++)
			for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++)
				ensureRegionLoaded(regionX, regionZ);
	}

	private void ensureRegionLoaded(int regionX, int regionZ) {
		long regionKey = key(regionX, regionZ);
		if (!loadedRegions.add(regionKey))
			return;

		GenericJob
			.build("BackdropZoneCache::loadRegion", task -> loadRegion(regionX, regionZ))
			.queue(false);
	}

	private void loadRegion(int regionX, int regionZ) {
		ResourcePath file = regionFile(regionX, regionZ);
		if (!file.exists())
			return;

		List<ZoneRecord> records;
		synchronized (regionFileLock) {
			records = readRegionFile(file);
		}
		if (records == null || records.isEmpty())
			return;

		clientThread.invoke(() -> {
			for (ZoneRecord r : records)
				finishLoad(r, true);
		});
	}

	/**
	 * Scans the on-disk cache (off the client thread) for any region file at all, and hands its coordinates to
	 * the callback on the client thread - or {@code (Integer.MIN_VALUE, Integer.MIN_VALUE)} if nothing is cached
	 * yet. Used by the login screen backdrop, which just wants to show whatever the player has already explored
	 * rather than any particular place.
	 */
	public void findRegionForLoginScreen(java.util.function.BiConsumer<Integer, Integer> callback) {
		GenericJob.build("BackdropZoneCache::findLoginScreenRegion", task -> {
			File[] files = CACHE_DIR.toFile().listFiles((d, name) -> name.startsWith("r_") && name.endsWith(".bin"));
			int[] found = null;
			long bestSize = -1;
			if (files != null) {
				// Prefer the most fleshed-out region (by file size, a cheap proxy for how much of it was
				// actually explored) over an arbitrary one, since a thinly-cached region can look like little
				// more than a narrow strip of terrain.
				for (File f : files) {
					if (f.length() <= bestSize)
						continue;
					String[] parts = f.getName().substring(2, f.getName().length() - 4).split("_");
					if (parts.length != 2)
						continue;
					try {
						found = new int[] { Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) };
						bestSize = f.length();
					} catch (NumberFormatException ignored) {
					}
				}
			}
			int[] result = found;
			clientThread.invoke(() -> callback.accept(
				result == null ? Integer.MIN_VALUE : result[0],
				result == null ? Integer.MIN_VALUE : result[1]
			));
		}).queue(false);
	}

	/**
	 * Scans the on-disk cache (off the client thread) for every region file that exists at all, and hands the
	 * full list of (regionX, regionZ) pairs to the callback on the client thread - or an empty list if nothing
	 * is cached yet. Used by the login screen backdrop's "load everything explored" mode, as an alternative to
	 * {@link #findRegionForLoginScreen}'s single-region-block mode.
	 */
	public void findAllCachedRegions(java.util.function.Consumer<List<int[]>> callback) {
		GenericJob.build("BackdropZoneCache::findAllCachedRegions", task -> {
			File[] files = CACHE_DIR.toFile().listFiles((d, name) -> name.startsWith("r_") && name.endsWith(".bin"));
			List<int[]> found = new ArrayList<>();
			if (files != null) {
				for (File f : files) {
					String[] parts = f.getName().substring(2, f.getName().length() - 4).split("_");
					if (parts.length != 2)
						continue;
					try {
						found.add(new int[] { Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) });
					} catch (NumberFormatException ignored) {
					}
				}
			}
			clientThread.invoke(() -> callback.accept(found));
		}).queue(false);
	}

	/**
	 * Loads a region directly from disk for the login screen backdrop, bypassing the
	 * {@link rs117.hd.HdPluginConfig#backdropCaching()} gate that the normal gameplay loading path ({@link #get})
	 * is subject to - the login screen may be shown with caching otherwise disabled, using whatever was cached in
	 * a previous session.
	 */
	public void loadRegionForLoginScreen(int regionX, int regionZ, Runnable onComplete) {
		ResourcePath file = regionFile(regionX, regionZ);
		GenericJob.build("BackdropZoneCache::loadLoginScreenRegion", task -> {
			List<ZoneRecord> records = null;
			if (file.exists()) {
				synchronized (regionFileLock) {
					records = readRegionFile(file);
				}
			}
			List<ZoneRecord> finalRecords = records;
			clientThread.invoke(() -> {
				if (finalRecords != null)
					for (ZoneRecord r : finalRecords)
						finishLoad(r, false);
				onComplete.run();
			});
		}).queue(false);
	}

	private List<ZoneRecord> readRegionFile(ResourcePath file) {
		try (InputStream is = file.toInputStream()) {
			DataInputStream in = new DataInputStream(new GZIPInputStream(new BufferedInputStream(is)));
			int magic = in.readInt();
			int version = in.readInt();
			if (magic != FORMAT_MAGIC || version != FORMAT_VERSION)
				return null;

			in.readInt(); // regionX, unused - each zone carries its own absolute coordinates
			in.readInt(); // regionZ
			int count = in.readInt();
			List<ZoneRecord> records = new ArrayList<>(count);
			for (int i = 0; i < count; i++)
				records.add(readZoneRecord(in));
			return records;
		} catch (Exception ex) {
			log.debug("Failed to read backdrop region file {}:", file, ex);
			return null;
		}
	}

	private void finishLoad(ZoneRecord r, boolean requireCachingEnabled) {
		long key = key(r.chunkX, r.chunkZ);
		if (r.vertexBytes.length == 0 || r.faceBytes.length == 0)
			return;

		// This chunk already has a file on disk from a previous session (or an earlier write this session) -
		// don't bother reading it back and rewriting it just because it's now stale; outdated is fine.
		cachedChunksThisSession.add(key);

		if ((requireCachingEnabled && !plugin.configBackdropCaching) || memCache.containsKey(key))
			return;

		Zone zone = injector.getInstance(Zone.class);

		// LWJGL's native glBufferSubData binding needs a direct buffer to get a real pointer from - a heap buffer
		// from ByteBuffer.wrap() isn't backed by native memory, and passing one through crashes the native driver
		// call instead of failing cleanly, so the bytes must be copied into a BufferUtils-allocated direct buffer.
		GLBuffer o = new GLBuffer("BackdropZone::VBO", GL_ARRAY_BUFFER, GL_STATIC_DRAW);
		o.initialize(r.vertexBytes.length);
		ByteBuffer vertexBuf = BufferUtils.createByteBuffer(r.vertexBytes.length);
		vertexBuf.put(r.vertexBytes).flip();
		o.upload(vertexBuf);

		GLTextureBuffer f = new GLTextureBuffer("BackdropZone::TBO", GL_STATIC_DRAW);
		f.initialize(r.faceBytes.length);
		ByteBuffer faceBuf = BufferUtils.createByteBuffer(r.faceBytes.length);
		faceBuf.put(r.faceBytes).flip();
		f.upload(faceBuf);

		zone.initialize(o, null, f);
		zone.sizeO = zone.bufLen = r.vertexBytes.length / Zone.VERT_SIZE;
		zone.sizeF = r.faceBytes.length / Zone.TEXTURE_SIZE;
		zone.levelOffsets = r.levelOffsets;
		zone.rids = r.rids;
		zone.roofStart = r.roofStart;
		zone.roofEnd = r.roofEnd;
		zone.hasWater = r.hasWater;
		zone.onlyWater = r.onlyWater;
		zone.lights = r.lightData;
		zone.isBackdrop = true;
		zone.initialized = true;

		memCache.put(key, zone);
		log.debug("Loaded backdrop zone for chunk ({}, {}) from disk", r.chunkX, r.chunkZ);
	}

	public void clear() {
		for (Zone zone : memCache.values())
			DestructibleHandler.queueDestruction(zone);
		memCache.clear();
		pendingWrites.clear();
		cachedChunksThisSession.clear();
		loadedRegions.clear();
		cacheQueue.clear();
		knownDiskChunksByRegion.clear();
	}

	private static byte[] readBufferBytes(int bufferId, int numBytes) {
		if (numBytes <= 0)
			return new byte[0];
		if (numBytes > MAX_READBACK_BYTES) {
			log.warn("readBufferBytes: refusing to allocate {} bytes, capping at {}", numBytes, MAX_READBACK_BYTES);
			numBytes = MAX_READBACK_BYTES;
		}
		ByteBuffer buf = BufferUtils.createByteBuffer(numBytes);
		glBindBuffer(GL_COPY_READ_BUFFER, bufferId);
		glGetBufferSubData(GL_COPY_READ_BUFFER, 0, buf);
		glBindBuffer(GL_COPY_READ_BUFFER, 0);
		byte[] bytes = new byte[numBytes];
		buf.get(bytes);
		return bytes;
	}

	private static int[][] copy2D(int[][] src) {
		if (src == null)
			return null;
		int[][] dst = new int[src.length][];
		for (int i = 0; i < src.length; i++)
			dst[i] = src[i] == null ? null : src[i].clone();
		return dst;
	}

	private void pruneDiskCacheIfNeeded() {
		File root = CACHE_DIR.toFile();
		File[] files = root.listFiles((d, name) -> name.startsWith("r_") && name.endsWith(".bin"));
		if (files == null)
			return;

		long total = 0;
		for (File f : files)
			total += f.length();
		if (total <= MAX_DISK_BYTES)
			return;

		List<File> sorted = new ArrayList<>(List.of(files));
		sorted.sort(Comparator.comparingLong(File::lastModified));
		long target = (long) (MAX_DISK_BYTES * 0.9);
		for (File f : sorted) {
			if (total <= target)
				break;
			long len = f.length();
			if (f.delete())
				total -= len;
		}
	}

	private static ResourcePath regionFile(int regionX, int regionZ) {
		return CACHE_DIR.resolve("r_" + regionX + "_" + regionZ).setExtension("bin");
	}

	private static void writeZoneRecord(DataOutputStream out, ZoneRecord r) throws IOException {
		out.writeInt(r.chunkX);
		out.writeInt(r.chunkZ);
		out.writeInt(r.hasWater ? 1 : 0);
		out.writeInt(r.onlyWater ? 1 : 0);
		writeIntArray(out, r.levelOffsets);
		for (int level = 0; level < 4; level++) {
			writeIntArray(out, r.rids == null ? null : r.rids[level]);
			writeIntArray(out, r.roofStart == null ? null : r.roofStart[level]);
			writeIntArray(out, r.roofEnd == null ? null : r.roofEnd[level]);
		}
		out.writeInt(r.vertexBytes.length);
		out.write(r.vertexBytes);
		out.writeInt(r.faceBytes.length);
		out.write(r.faceBytes);
		writeFloatArray(out, r.lightData);
	}

	// Sanity caps for anything read back from a region file. A single zone's geometry should never remotely
	// approach these - if a length field is bigger than this, the file is corrupt (e.g. from an older, buggier
	// version of this cache, or a crash mid-write before atomic rename was added), not a legitimate size, and
	// should be treated as unreadable rather than trusted enough to allocate.
	private static final int MAX_RECORD_BYTES = 64 * 1024 * 1024;
	private static final int MAX_RECORD_INTS = 4 * 1024 * 1024;

	private static ZoneRecord readZoneRecord(DataInputStream in) throws IOException {
		ZoneRecord r = new ZoneRecord();
		r.chunkX = in.readInt();
		r.chunkZ = in.readInt();
		r.hasWater = in.readInt() != 0;
		r.onlyWater = in.readInt() != 0;
		r.levelOffsets = readIntArray(in);
		r.rids = new int[4][];
		r.roofStart = new int[4][];
		r.roofEnd = new int[4][];
		for (int level = 0; level < 4; level++) {
			r.rids[level] = readIntArray(in);
			r.roofStart[level] = readIntArray(in);
			r.roofEnd[level] = readIntArray(in);
		}
		int vertLen = readSaneLength(in.readInt(), MAX_RECORD_BYTES);
		r.vertexBytes = new byte[vertLen];
		in.readFully(r.vertexBytes);
		int faceLen = readSaneLength(in.readInt(), MAX_RECORD_BYTES);
		r.faceBytes = new byte[faceLen];
		in.readFully(r.faceBytes);
		r.lightData = readFloatArray(in);
		return r;
	}

	private static int readSaneLength(int len, int max) throws IOException {
		if (len < 0 || len > max)
			throw new IOException("Backdrop region file field length out of range: " + len);
		return len;
	}

	private static void writeIntArray(DataOutputStream out, int[] arr) throws IOException {
		if (arr == null) {
			out.writeInt(0);
			return;
		}
		out.writeInt(arr.length);
		for (int v : arr)
			out.writeInt(v);
	}

	private static int[] readIntArray(DataInputStream in) throws IOException {
		int len = readSaneLength(in.readInt(), MAX_RECORD_INTS);
		int[] arr = new int[len];
		for (int i = 0; i < len; i++)
			arr[i] = in.readInt();
		return arr;
	}

	private static void writeFloatArray(DataOutputStream out, float[] arr) throws IOException {
		out.writeInt(arr.length);
		for (float v : arr)
			out.writeFloat(v);
	}

	private static float[] readFloatArray(DataInputStream in) throws IOException {
		int len = readSaneLength(in.readInt(), MAX_RECORD_INTS);
		float[] arr = new float[len];
		for (int i = 0; i < len; i++)
			arr[i] = in.readFloat();
		return arr;
	}

	private static final class ZoneRecord {
		int chunkX, chunkZ;
		byte[] vertexBytes;
		byte[] faceBytes;
		int[] levelOffsets;
		int[][] rids;
		int[][] roofStart;
		int[][] roofEnd;
		boolean hasWater;
		boolean onlyWater;
		float[] lightData = EMPTY_FLOAT_ARRAY;
	}

	private static final class PendingCache {
		final int chunkX, chunkZ;
		final Zone zone;
		final float[] lightData;

		PendingCache(int chunkX, int chunkZ, Zone zone, float[] lightData) {
			this.chunkX = chunkX;
			this.chunkZ = chunkZ;
			this.zone = zone;
			this.lightData = lightData;
		}
	}
}
