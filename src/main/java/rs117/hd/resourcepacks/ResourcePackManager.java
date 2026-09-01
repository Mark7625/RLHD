package rs117.hd.resourcepacks;

import com.google.gson.Gson;
import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.util.ColorUtil;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.resourcepacks.data.Manifest;
import rs117.hd.resourcepacks.impl.DefaultResourcePack;
import rs117.hd.utils.PopupUtils;
import rs117.hd.utils.FileWatcher;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.HdPluginConfig.*;

@Singleton
@Slf4j
public final class ResourcePackManager {
	private static final int MAX_UPDATE_CHECK_INTERVAL = 600000; // 10 minutes

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private Gson gson;

	@Inject
	private EventBus eventBus;

	@Inject
	private HdPluginConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private Client client;

	@Inject ClientThread clientThread;

	@Inject
	private ResourcePackRepository repository;

	private final List<AbstractResourcePack> installedPacks = new CopyOnWriteArrayList<>();
	private final AtomicBoolean reloadQueued = new AtomicBoolean();
	private FileWatcher.UnregisterCallback packDirectoryWatcher = () -> {};
	private volatile boolean watchingPackDirectory;

	private final Map<String, Manifest> downloadablePacks = new LinkedHashMap<>();
	private ResourcePackState packState = new ResourcePackState();

	@Getter
	private ResourcePackStatus status;

	private long lastCheckForUpdates;

	public void startUp() {
		watchingPackDirectory = false;
		packDirectoryWatcher.unregister();
		clearInstalledPacks();

		if (!config.enableResourcePacks()) {
			installedPacks.add(new DefaultResourcePack(ResourcePath.path(ResourcePackManager.class.getClassLoader(), "rs117/hd/resource-pack")));
			return;
		}
		loadPackState();
		recoverInterruptedReplacements();
		loadInstalledPacks();
		watchPackDirectory();

		checkForUpdates();
	}

	private void loadInstalledPacks() {
		installedPacks.addAll(repository.loadInstalledPacks());
		installedPacks.add(new DefaultResourcePack(ResourcePath.path(ResourcePackManager.class.getClassLoader(), "rs117/hd/resource-pack")));
		verifyInstalledPacks();
		restorePackOrder();
		savePackOrder();
	}

	/** Restores a verified previous archive left behind if the client stopped during replacement. */
	private void recoverInterruptedReplacements() {
		for (Map.Entry<String, String> entry : packState.sha256ByPack.entrySet()) {
			String internalName = entry.getKey();
			String expectedSha256 = entry.getValue();
			if (!isSafeInternalName(internalName) || expectedSha256 == null || !expectedSha256.matches("[0-9a-fA-F]{64}"))
				continue;

			File archive = repository.archiveFile(internalName);
			File backup = new File(archive.getPath() + ".previous");
			if (archive.exists() || !backup.isFile())
				continue;

			try {
				if (!expectedSha256.equalsIgnoreCase(PackHashes.sha256(backup))) {
					log.warn("Not recovering previous archive for '{}': SHA-256 does not match the installed pack state", internalName);
					continue;
				}
				Files.move(backup.toPath(), archive.toPath());
				log.info("Recovered resource pack '{}' from an interrupted replacement", internalName);
			} catch (IOException ex) {
				log.warn("Unable to recover previous archive for resource pack '{}'", internalName, ex);
			}
		}
	}

	private void watchPackDirectory() {
		watchingPackDirectory = true;
		packDirectoryWatcher = ResourcePath.path(getPackDirectory()).watch((path, first) -> {
			if (!first && repository.isRelevantPackChange(path)) {
				queueInstalledPackReload();
			}
		});
	}

	private void queueInstalledPackReload() {
		if (!watchingPackDirectory || !reloadQueued.compareAndSet(false, true)) {
			return;
		}

		SwingUtilities.invokeLater(() -> {
			reloadQueued.set(false);
			if (!watchingPackDirectory) {
				return;
			}

			clearInstalledPacks();
			loadInstalledPacks();
			eventBus.post(new ResourcePackUpdate(PackEventType.REFRESHED));
		});
	}

	private void migrateLegacyConfigsInternal() {
		if (config.legacyTobEnvironment()) {
			downloadLegacyPackIfNeeded("legacy_theatre_of_blood", "Theatre of Blood",KEY_LEGACY_TOB_ENVIRONMENT);
		}
	}

	private void downloadLegacyPackIfNeeded(String internalName, String displayName, String keyName) {
		Manifest manifest = downloadablePacks.get(internalName);
		if (manifest != null) {
			if (getInstalledPack(internalName) == null) {
				log.info("Auto-downloading legacy pack '{}' due to legacy config being enabled", internalName);
				downloadResourcePack(manifest,false);
				configManager.setConfiguration("hd",keyName,false);
			}
		} else {
			log.warn("Could not find legacy pack '{}' ({}) in downloadable packs", internalName, displayName);
		}
	}

	public void shutDown() {
		watchingPackDirectory = false;
		packDirectoryWatcher.unregister();
		clearInstalledPacks();
	}

	private void clearInstalledPacks() {
		for (AbstractResourcePack pack : installedPacks)
			repository.close(pack);
		installedPacks.clear();
	}

	public void checkForUpdates() {
		if (System.currentTimeMillis() - lastCheckForUpdates < MAX_UPDATE_CHECK_INTERVAL)
			return;
		lastCheckForUpdates = System.currentTimeMillis();

		setStatus("Loading...", "Fetching list of resource packs...");

		okHttpClient
			.newCall(new Request.Builder()
				.url(HdPlugin.RESOURCE_PACKS_MANIFEST_URL)
				.build())
			.enqueue(new Callback() {
				@Override
				public void onFailure(Call call, IOException ex) {
					log.error("Unable to download manifest:", ex);
					// Allow retrying without delay
					lastCheckForUpdates = 0;

					setStatus(
						"Network Error",
						"Check your network connection.<br>"
						+ "Join our Discord server if the issue persists."
					);
				}

			@Override
			public void onResponse(Call call, Response res) {
				Manifest[] manifests;
				try (Response ignored = res) {
					if (!res.isSuccessful())
						throw new IOException("Unexpected response code " + res.code());
					if (res.body() == null)
						throw new IllegalStateException("Manifest is null");

					manifests = gson.fromJson(res.body().string(), Manifest[].class);
					if (manifests == null)
						throw new IllegalStateException("Manifest is empty");
				} catch (Exception ex) {
					log.error("Error while reading downloaded manifest:", ex);
					setStatus(
						"Malformed Manifest",
						"Something went wrong with our system...<br>"
						+ "Join our Discord server for further information."
					);
					return;
				}

				if (manifests.length == 0) {
					setStatus(
						"No packs available",
						"There are currently no packs available for download."
					);
					return;
				}

					SwingUtilities.invokeLater(() -> {
						downloadablePacks.clear();

						Arrays.sort(manifests, (left, right) -> left.getDisplayName().compareToIgnoreCase(right.getDisplayName()));
						for (var manifest : manifests) {
							downloadablePacks.put(manifest.getInternalName(), manifest);
						}

						checkAndUpdateOutdatedPacks();

						setStatus(null, null);

						migrateLegacyConfigsInternal();
					});
				}
			});
	}

	private void setStatus(String title, String description) {
		SwingUtilities.invokeLater(() -> {
			if (title == null) {
				status = null;
			} else {
				status = new ResourcePackStatus(title, description);
			}
			eventBus.post(new ResourcePackUpdate(PackEventType.REFRESHED));
		});
	}

	public void removeResourcePack(String internalName) {
		// Find the pack before removing it
		AbstractResourcePack packToRemove = null;
		for (var pack : installedPacks) {
			if (pack.getManifest().getInternalName().equals(internalName)) {
				packToRemove = pack;
				break;
			}
		}

		if (packToRemove == null) {
			log.warn("Attempted to remove pack '{}' but it was not found", internalName);
			return;
		}

		// Don't delete the default pack
		if (packToRemove instanceof DefaultResourcePack) {
			log.warn("Attempted to remove default pack, ignoring");
			return;
		}
		if (isPackEnabled(packToRemove))
			revertPackSettings(packToRemove);

		// Store file path before removing from list
		File fileToDelete = null;
		if (packToRemove.path.isFileSystemResource()) {
			fileToDelete = packToRemove.path.toFile();
		}

		// Close zip files before deletion so Windows releases the file handle.
		repository.close(packToRemove);

		if (fileToDelete != null && fileToDelete.exists()) {
			if (!deleteResourcePackFile(fileToDelete)) {
				log.warn("Unable to delete resource pack for '{}': {}", internalName, fileToDelete);
				int packIndex = installedPacks.indexOf(packToRemove);
				if (packIndex >= 0)
					installedPacks.set(packIndex, repository.createPack(fileToDelete));
				return;
			}
		}

		installedPacks.remove(packToRemove);
		packState.sha256ByPack.remove(internalName);
		packState.disabledPacks.remove(internalName);
		packState.settingsByPack.remove(internalName);

		savePackOrder();

		eventBus.post(new ResourcePackUpdate(PackEventType.REMOVED, packToRemove));
	}

	private void downloadResourcePack(Manifest manifest, boolean updating) {
		downloadResourcePack(manifest, null, null, null, updating);
	}

	public void downloadResourcePack(Manifest manifest, java.util.function.Consumer<Integer> onProgress, Runnable onSuccess, java.util.function.Consumer<String> onFailure, boolean updating) {
		boolean packExists = getInstalledPack(manifest.getInternalName()) != null;

		if (manifest.isHasSettings() && !updating && !packExists) {
			if (onProgress != null)
				onProgress.accept(-2);
			PopupUtils.displayPopupMessage(
				client,
				"Pack Settings Override",
				"This pack will override some of your settings.<br><br>" +
				"Do you want to continue with the installation?",
				new String[] { "Cancel", "Continue" },
				i -> {
					if (i == 1) {
						downloadResourcePackInternal(manifest, onProgress, onSuccess, onFailure, false);
						return true;
					}
					if (onFailure != null)
						onFailure.accept(null);
					return true;
				}
			);
			return;
		}

		downloadResourcePackInternal(manifest, onProgress, onSuccess, onFailure, updating);
	}

	private void downloadResourcePackInternal(Manifest manifest, java.util.function.Consumer<Integer> onProgress, Runnable onSuccess, java.util.function.Consumer<String> onFailure, boolean updating) {
		if (!isSafeInternalName(manifest.getInternalName())) {
			log.warn("Refusing to download resource pack with unsafe internal name: {}", manifest.getInternalName());
			if (onFailure != null)
				onFailure.accept("The resource pack has an invalid internal identifier.");
			return;
		}
		if (!repository.ensurePackDirectory()) {
			log.warn("Unable to create resource pack directory");
			if (onFailure != null)
				onFailure.accept("Unable to create the resource-pack folder. Check available disk space and permissions.");
			return;
		}

		HttpUrl repositoryUrl = githubRepositoryUrl(manifest.getLink());
		if (repositoryUrl == null || !isCommitHash(manifest.getCommit())) {
			log.warn("Invalid download metadata for resource pack {}", manifest.getInternalName());
			if (onFailure != null)
				onFailure.accept("The resource pack has invalid download metadata.");
			return;
		}
		URL url = repositoryUrl
			.newBuilder()
			.addPathSegment("archive")
			.addPathSegment(manifest.getCommit() + ".zip")
			.build()
			.url();

		// Use file size from manifest if available
		Long expectedFileSize = manifest.getFileSize();

		File zipFile = repository.archiveFile(manifest.getInternalName());
		File temporaryFile = new File(zipFile.getPath() + ".part");
		deleteFileQuietly(temporaryFile);

		ResourcePackDownloader downloader = new ResourcePackDownloader(okHttpClient);
		downloader.download(
			url.toString(),
			temporaryFile,
			expectedFileSize,
			new ResourcePackDownloader.Listener() {
				@Override
				public void onStarted() {
					log.info("Downloading resource pack '{}' from {}", manifest.getInternalName(), url);
				}

				@Override
				public void onFailure(Call call, IOException e) {
					deleteFileQuietly(temporaryFile);
					log.warn("Error while downloading resource pack '{}' from {}", manifest.getInternalName(), url, e);
					if (onFailure != null) {
						onFailure.accept(getDownloadFailureMessage(e));
					}
				}

				@Override
				public void onProgress(int progress) {
					if (onProgress != null) {
						onProgress.accept(progress);
					}
				}

				@Override
				public void onFinished(String sha256) {
					SwingUtilities.invokeLater(() -> {
						try {
							installDownloadedPack(manifest, temporaryFile, zipFile, sha256, updating);
							if (onSuccess != null)
								onSuccess.run();
						} catch (Exception ex) {
							deleteFileQuietly(temporaryFile);
							log.warn("Unable to install resource pack '{}'", manifest.getInternalName(), ex);
							if (onFailure != null)
								onFailure.accept(getDownloadFailureMessage(ex));
						}
					});
				}
			}
		);
	}

	private static String getDownloadFailureMessage(Exception exception) {
		String message = exception.getMessage();
		String lowerCaseMessage = message == null ? "" : message.toLowerCase(Locale.ROOT);
		if (lowerCaseMessage.contains("no space left") || lowerCaseMessage.contains("disk full"))
			return "Not enough free disk space to install this resource pack.";
		if (exception instanceof java.io.InterruptedIOException || lowerCaseMessage.contains("timed out"))
			return "The download timed out. Check your network connection and try again.";
		if (message == null || message.isEmpty())
			return "Unable to download or install this resource pack.";
		return "Unable to download or install this resource pack: " + message;
	}

	private void installDownloadedPack(Manifest manifest, File temporaryFile, File zipFile, String sha256, boolean updating) throws IOException {
		AbstractResourcePack validatedPack = repository.createPack(temporaryFile);
		if (!validatedPack.isValid()) {
			repository.close(validatedPack);
			throw new IOException("Downloaded archive has invalid pack metadata");
		}
		String internalName = validatedPack.getManifest().getInternalName();
		if (!manifest.getInternalName().equals(internalName)) {
			repository.close(validatedPack);
			throw new IOException("Archive metadata does not match the requested pack");
		}
		String archiveCommit = validatedPack.getManifest().getCommit();
		if (!archiveCommit.isEmpty() && !manifest.getCommit().equals(archiveCommit))
			log.debug("Archive metadata for '{}' declares commit '{}'; using the pinned GitHub archive commit '{}' instead",
				internalName, archiveCommit, manifest.getCommit());
		repository.close(validatedPack);

		AbstractResourcePack localPack = getInstalledPack(internalName);
		File backupFile = new File(zipFile.getPath() + ".previous");
		deleteFileQuietly(backupFile);
		repository.close(localPack);

		try {
			if (zipFile.exists())
				Files.move(zipFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
			Files.move(temporaryFile.toPath(), zipFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException ex) {
			if (!zipFile.exists() && backupFile.exists())
				Files.move(backupFile.toPath(), zipFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
			if (localPack != null && zipFile.exists()) {
				int localIndex = installedPacks.indexOf(localPack);
				if (localIndex >= 0)
					installedPacks.set(localIndex, repository.createPack(zipFile));
			}
			throw ex;
		}

		AbstractResourcePack pack = repository.createPack(zipFile);
		if (localPack == null) {
			installedPacks.add(installedPacks.size() - 1, pack);
		} else {
			installedPacks.set(installedPacks.indexOf(localPack), pack);
		}
		deleteFileQuietly(backupFile);
		packState.sha256ByPack.put(internalName, sha256);
		pack.setModified(false);
		savePackOrder();

		eventBus.post(new ResourcePackUpdate(PackEventType.ADDED, pack, manifest));
		if (manifest.isHasSettings() && localPack == null && !updating)
			applyPackSettings(pack);
	}

	private static boolean isSafeInternalName(String internalName) {
		return internalName != null && internalName.matches("[a-z0-9_-]+");
	}

	private static boolean isCommitHash(String commit) {
		return commit != null && commit.matches("[0-9a-fA-F]{7,64}");
	}

	private static HttpUrl githubRepositoryUrl(String link) {
		if (link == null || !link.startsWith("https://github.com/"))
			return null;
		HttpUrl url = HttpUrl.parse(link);
		if (url == null || !url.isHttps() || !"github.com".equals(url.host()) || url.username().length() > 0 || url.password().length() > 0)
			return null;
		return url.pathSize() >= 2 ? url : null;
	}

	private static void deleteFileQuietly(File file) {
		if (file.exists() && !file.delete())
			log.warn("Unable to delete temporary resource pack file: {}", file);
	}

	private static boolean deleteResourcePackFile(File file) {
		try {
			Files.walkFileTree(file.toPath(), new SimpleFileVisitor<java.nio.file.Path>() {
				@Override
				public FileVisitResult visitFile(java.nio.file.Path path, BasicFileAttributes attributes) throws IOException {
					Files.delete(path);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(java.nio.file.Path directory, IOException exception) throws IOException {
					if (exception != null)
						throw exception;
					Files.delete(directory);
					return FileVisitResult.CONTINUE;
				}
			});
			return true;
		} catch (IOException ex) {
			log.warn("Unable to delete resource pack path: {}", file, ex);
			return false;
		}
	}

	public List<AbstractResourcePack> getInstalledPacks() {
		return List.copyOf(installedPacks);
	}

	public List<AbstractResourcePack> getEnabledPacks() {
		return installedPacks.stream().filter(this::isPackEnabled).collect(Collectors.toList());
	}

	public boolean isPackEnabled(AbstractResourcePack pack) {
		return pack instanceof DefaultResourcePack || !packState.disabledPacks.contains(pack.getManifest().getInternalName());
	}

	public boolean hasSettingsConflict(AbstractResourcePack pack) {
		Map<String, ResourcePackState.AppliedSetting> settings = packState.settingsByPack.get(pack.getManifest().getInternalName());
		if (settings == null)
			return false;
		for (Map.Entry<String, ResourcePackState.AppliedSetting> entry : settings.entrySet()) {
			if (!Objects.equals(configManager.getConfiguration(CONFIG_GROUP, entry.getKey()), entry.getValue().appliedValue))
				return true;
		}
		return false;
	}

	public void setPackEnabled(AbstractResourcePack pack, boolean enabled) {
		if (pack instanceof DefaultResourcePack)
			return;

		String internalName = pack.getManifest().getInternalName();
		if (enabled) {
			if (!packState.disabledPacks.remove(internalName))
				return;
			if (pack.hasResource("settings.properties"))
				applyPackSettings(pack);
		} else {
			if (!packState.disabledPacks.add(internalName))
				return;
			revertPackSettings(pack);
		}
		savePackOrder();
		eventBus.post(new ResourcePackUpdate(PackEventType.REFRESHED));
	}

	public List<Manifest> getDownloadablePacks() {
		return List.copyOf(downloadablePacks.values());
	}

	public File getPackDirectory() {
		if (!repository.ensurePackDirectory())
			log.warn("Unable to create resource pack directory");
		return repository.packDirectory();
	}

	public AbstractResourcePack getInstalledPack(String internalName) {
		for (var pack : installedPacks)
			if (pack.getManifest().getInternalName().equals(internalName))
				return pack;
		return null;
	}

	public boolean isEnabled(String internalName) {
		return getInstalledPack(internalName) != null;
	}

	public boolean isTrackedOfficialPack(AbstractResourcePack pack) {
		return packState.sha256ByPack.containsKey(pack.getManifest().getInternalName());
	}

	public void redownloadResourcePack(AbstractResourcePack pack) {
		Manifest manifest = downloadablePacks.get(pack.getManifest().getInternalName());
		if (manifest != null)
			downloadResourcePack(manifest, true);
	}

	public ResourcePath locateFile(String... parts) {
		AbstractResourcePack pack = locatePack(parts);
		return pack != null ? pack.getResource(parts) : null;
	}

	private AbstractResourcePack locatePack(String... parts) {
		for (AbstractResourcePack pack : installedPacks) {
			if (!isPackEnabled(pack))
				continue;
			if (pack.hasResource(parts)) {
				return pack;
			}
		}
		return null;
	}

	/** Moves a pack and returns its resulting index, or -1 when the move is invalid. */
	public int movePack(int fromIndex, int toIndex) {
		int lastIndex = installedPacks.size() - 1;
		if (fromIndex < 0 || fromIndex > lastIndex || toIndex < 0)
			return -1;

		int targetIndex = Math.min(toIndex, lastIndex);
		if (targetIndex == fromIndex)
			return -1;

		installedPacks.add(targetIndex, installedPacks.remove(fromIndex));
		return targetIndex;
	}

	/**
	 * Checks for outdated packs by comparing archive metadata with the manifest,
	 * and automatically re-downloads any outdated packs.
	 */
	private void checkAndUpdateOutdatedPacks() {
		ArrayList<Manifest> packsToUpdate = new ArrayList<>();

		for (var pack : installedPacks) {
			if (pack instanceof DefaultResourcePack) {
				continue;
			}

			String internalName = pack.getManifest().getInternalName();
			Manifest manifest = downloadablePacks.get(internalName);

			if (manifest == null) {
				continue;
			}

			String installedCommit = pack.getManifest().getCommit();
			String availableCommit = manifest.getCommit();
			if (!installedCommit.isEmpty() && !availableCommit.isEmpty() && !installedCommit.equals(availableCommit)) {
				packsToUpdate.add(manifest);
			}
		}

		if (!packsToUpdate.isEmpty()) {
			List<String> namesList = packsToUpdate.stream()
				.map(Manifest::getInternalName)
				.collect(Collectors.toList());

			String names = String.join(", ", namesList);
			log.info("{} | Packs outdated: {}", namesList.size(), names);
		}

		for (var manifest : packsToUpdate) {
			downloadResourcePack(manifest,true);
		}
	}
	/**
	 * Persists an already-applied move and publishes its final state.
	 */
	public void commitPackMove(AbstractResourcePack pack, int fromIndex, int toIndex) {
		savePackOrder();
		eventBus.post(new ResourcePackUpdate(PackEventType.MOVED, pack, fromIndex, toIndex));
	}

	private void savePackOrder() {
		packState.packOrder.clear();
		for (var pack : installedPacks) {
			packState.packOrder.add(pack.getManifest().getInternalName());
		}
		configManager.setConfiguration(CONFIG_GROUP, KEY_RESOURCE_PACK_STATE, gson.toJson(packState));
	}

	private void restorePackOrder() {
		Map<String, AbstractResourcePack> packsByName = new LinkedHashMap<>();
		for (AbstractResourcePack pack : installedPacks)
			packsByName.put(pack.getManifest().getInternalName(), pack);

		installedPacks.clear();
		for (String internalName : packState.packOrder) {
			AbstractResourcePack pack = packsByName.remove(internalName.trim());
			if (pack != null)
				installedPacks.add(pack);
		}
		installedPacks.addAll(packsByName.values());
	}

	private void loadPackState() {
		try {
			String serialized = config.resourcePackState();
			ResourcePackState loaded = serialized == null || serialized.isEmpty() ? null : gson.fromJson(serialized, ResourcePackState.class);
			if (loaded != null) {
				if (loaded.packOrder == null)
					loaded.packOrder = new ArrayList<>();
				if (loaded.sha256ByPack == null)
					loaded.sha256ByPack = new LinkedHashMap<>();
				if (loaded.disabledPacks == null)
					loaded.disabledPacks = new LinkedHashSet<>();
				if (loaded.settingsByPack == null)
					loaded.settingsByPack = new LinkedHashMap<>();
				packState = loaded;
			}
		} catch (RuntimeException ex) {
			log.warn("Ignoring invalid resource pack state", ex);
			packState = new ResourcePackState();
		}
	}

	private void verifyInstalledPacks() {
		for (AbstractResourcePack pack : installedPacks) {
			String expected = packState.sha256ByPack.get(pack.getManifest().getInternalName());
			if (expected == null)
				continue;
			File file = pack.path.isFileSystemResource() ? pack.path.toFile() : null;
			try {
				pack.setModified(file == null || !file.isFile() || !expected.equals(PackHashes.sha256(file)));
			} catch (IOException ex) {
				log.warn("Unable to verify resource pack {}", pack.getPackName(), ex);
				pack.setModified(true);
			}
		}
	}

	private void applyPackSettings(AbstractResourcePack pack) {
		try {
			if (!pack.hasResource("settings.properties")) {
				log.warn("Pack {} has hasSettings=true but no settings.properties file", pack.getPackName());
				return;
			}

			var settingsPath = pack.getResource("settings.properties");
			if (settingsPath == null || !settingsPath.exists()) {
				log.warn("Pack {} settings.properties file not found", pack.getPackName());
				return;
			}

			Properties settings = new Properties();
			try (var inputStream = settingsPath.toInputStream()) {
				settings.load(inputStream);
			}

			Map<String, ResourcePackState.AppliedSetting> appliedSettings = packState.settingsByPack
				.computeIfAbsent(pack.getManifest().getInternalName(), ignored -> new LinkedHashMap<>());
			for (String key : settings.stringPropertyNames()) {
				String value = settings.getProperty(key).trim();
				ResourcePackState.AppliedSetting applied = appliedSettings.get(key);
				if (applied != null && !Objects.equals(configManager.getConfiguration(CONFIG_GROUP, key), applied.appliedValue))
					continue;
				if (applied == null) {
					applied = new ResourcePackState.AppliedSetting();
					applied.previousValue = configManager.getConfiguration(CONFIG_GROUP, key);
					appliedSettings.put(key, applied);
				}
				applied.appliedValue = value;
				configManager.setConfiguration(CONFIG_GROUP, key, value);
				log.info("Applied setting: {} = {}", key, value);
			}
			savePackOrder();

			boolean loggedIn = client.getGameState() == GameState.LOGGED_IN;
			boolean interacting = loggedIn && client.getLocalPlayer() != null && client.getLocalPlayer().isInteracting();

			if (!loggedIn || !interacting) {
				PopupUtils.displayPopupMessage(
					client,
					"Settings Applied",
					"The pack settings have been applied.<br><br>" +
					"This requires a restart to take effect.",
					new String[] { "Cancel", "Restart" },
					i -> {
						if (i == 1) {
							System.exit(0);
						}
						return true;
					}
				);
			} else {
				clientThread.invoke(() -> client.addChatMessage(
					ChatMessageType.GAMEMESSAGE,
					"117 HD",
					ColorUtil.wrapWithColorTag(
						"[117 HD] We could not apply the settings visually because you were in combat. Please restart the client to see the changes.",
						Color.GREEN
					),
					"117 HD"
				));
			}
		} catch (Exception ex) {
			log.error("Error applying pack settings:", ex);
		}
	}

	private void revertPackSettings(AbstractResourcePack pack) {
		Map<String, ResourcePackState.AppliedSetting> settings = packState.settingsByPack.get(pack.getManifest().getInternalName());
		if (settings == null)
			return;

		for (Map.Entry<String, ResourcePackState.AppliedSetting> entry : settings.entrySet()) {
			ResourcePackState.AppliedSetting applied = entry.getValue();
			if (!Objects.equals(configManager.getConfiguration(CONFIG_GROUP, entry.getKey()), applied.appliedValue))
				continue;
			if (applied.previousValue == null)
				configManager.unsetConfiguration(CONFIG_GROUP, entry.getKey());
			else
				configManager.setConfiguration(CONFIG_GROUP, entry.getKey(), applied.previousValue);
		}
	}

}
