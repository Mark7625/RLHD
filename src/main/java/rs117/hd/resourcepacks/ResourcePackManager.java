package rs117.hd.resourcepacks;

import com.google.gson.Gson;
import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
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
import rs117.hd.HdPluginConfig;
import rs117.hd.resourcepacks.data.Manifest;
import rs117.hd.resourcepacks.impl.DefaultResourcePack;
import rs117.hd.utils.PopupUtils;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.HdPluginConfig.*;

@Singleton
@Slf4j
public final class ResourcePackManager {

	private static final HttpUrl RESOURCE_PACKS_MANIFEST_URL = HttpUrl.get("https://raw.githubusercontent.com/117HD/resource-pack-hub/manifest/manifest.json");

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

	private final Map<String, Manifest> downloadablePacks = new LinkedHashMap<>();

	@Getter
	private ResourcePackStatus status;

	private long lastCheckForUpdates;

	public void startUp() {
		clearInstalledPacks();

		if (!config.enableResourcePacks()) {
			installedPacks.add(new DefaultResourcePack(ResourcePath.path(ResourcePackManager.class.getClassLoader(), "rs117/hd/resource-pack")));
			return;
		}
		installedPacks.addAll(repository.loadInstalledPacks());

		installedPacks.add(new DefaultResourcePack(ResourcePath.path(ResourcePackManager.class.getClassLoader(), "rs117/hd/resource-pack")));

		savePackOrder();

		checkForUpdates();
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
				.url(RESOURCE_PACKS_MANIFEST_URL)
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

		// Store file path before removing from list
		File fileToDelete = null;
		if (packToRemove.path.isFileSystemResource()) {
			fileToDelete = packToRemove.path.toFile();
		}

		// Close zip files before deletion so Windows releases the file handle.
		repository.close(packToRemove);

		if (fileToDelete != null && fileToDelete.exists() && fileToDelete.isFile()) {
			if (!fileToDelete.delete()) {
				log.warn("Unable to delete resource pack file for '{}': {}", internalName, fileToDelete);
				int packIndex = installedPacks.indexOf(packToRemove);
				if (packIndex >= 0)
					installedPacks.set(packIndex, repository.createPack(fileToDelete));
				return;
			}
		}

		installedPacks.remove(packToRemove);

		removeCommitHash(internalName);

		savePackOrder();

		eventBus.post(new ResourcePackUpdate(PackEventType.REMOVED, packToRemove));
	}

	private void downloadResourcePack(Manifest manifest, boolean updating) {
		downloadResourcePack(manifest, null, null, null, updating);
	}

	public void downloadResourcePack(Manifest manifest, java.util.function.Consumer<Integer> onProgress, Runnable onSuccess, Runnable onFailure, boolean updating) {
		boolean packExists = getInstalledPack(manifest.getInternalName()) != null;

		if (manifest.isHasSettings() && !updating && !packExists) {
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
					return true;
				}
			);
			return;
		}

		downloadResourcePackInternal(manifest, onProgress, onSuccess, onFailure, updating);
	}

	private void downloadResourcePackInternal(Manifest manifest, java.util.function.Consumer<Integer> onProgress, Runnable onSuccess, Runnable onFailure, boolean updating) {
		if (!isSafeInternalName(manifest.getInternalName())) {
			log.warn("Refusing to download resource pack with unsafe internal name: {}", manifest.getInternalName());
			if (onFailure != null)
				onFailure.run();
			return;
		}
		if (!repository.ensurePackDirectory()) {
			log.warn("Unable to create resource pack directory");
			if (onFailure != null)
				onFailure.run();
			return;
		}

		HttpUrl repositoryUrl = HttpUrl.parse(manifest.getLink());
		if (repositoryUrl == null || manifest.getCommit().isEmpty()) {
			log.warn("Invalid download metadata for resource pack {}", manifest.getInternalName());
			if (onFailure != null)
				onFailure.run();
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
						onFailure.run();
					}
				}

				@Override
				public void onProgress(int progress) {
					if (onProgress != null) {
						onProgress.accept(progress);
					}
				}

				@Override
				public void onFinished() {
					SwingUtilities.invokeLater(() -> {
						try {
							installDownloadedPack(manifest, temporaryFile, zipFile, updating);
							if (onSuccess != null)
								onSuccess.run();
						} catch (Exception ex) {
							deleteFileQuietly(temporaryFile);
							log.warn("Unable to install resource pack '{}'", manifest.getInternalName(), ex);
							if (onFailure != null)
								onFailure.run();
						}
					});
				}
			}
		);
	}

	private void installDownloadedPack(Manifest manifest, File temporaryFile, File zipFile, boolean updating) throws IOException {
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
		saveCommitHash(internalName, manifest.getCommit());
		savePackOrder();

		eventBus.post(new ResourcePackUpdate(PackEventType.ADDED, pack, manifest));
		if (manifest.isHasSettings() && localPack == null && !updating)
			applyPackSettings(pack);
	}

	private static boolean isSafeInternalName(String internalName) {
		return internalName != null && internalName.matches("[A-Za-z0-9._-]+");
	}

	private static void deleteFileQuietly(File file) {
		if (file.exists() && !file.delete())
			log.warn("Unable to delete temporary resource pack file: {}", file);
	}

	public List<AbstractResourcePack> getInstalledPacks() {
		return List.copyOf(installedPacks);
	}

	public List<Manifest> getDownloadablePacks() {
		return List.copyOf(downloadablePacks.values());
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

	public ResourcePath locateFile(String... parts) {
		AbstractResourcePack pack = locatePack(parts);
		return pack != null ? pack.getResource(parts) : null;
	}

	private AbstractResourcePack locatePack(String... parts) {
		for (AbstractResourcePack pack : installedPacks) {
			if (pack.hasResource(parts)) {
				return pack;
			}
		}
		return null;
	}

	/**
	 * Moves a non-default pack and returns its resulting index, or -1 when the move is invalid.
	 */
	public int movePack(int fromIndex, int toIndex) {
		int lastIndex = installedPacks.size() - 1;
		if (fromIndex < 0 || fromIndex >= lastIndex || toIndex < 0)
			return -1;

		int targetIndex = Math.min(toIndex, lastIndex - 1);
		if (targetIndex == fromIndex)
			return -1;

		Collections.swap(installedPacks, fromIndex, targetIndex);
		return targetIndex;
	}

	/**
	 * Checks for outdated packs by comparing stored commit hashes with manifest,
	 * and automatically re-downloads any outdated packs.
	 */
	private void checkAndUpdateOutdatedPacks() {
		Properties commitHashes = loadCommitHashes();
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

			String storedCommit = commitHashes.getProperty(internalName);
			String manifestCommit = manifest.getCommit();

			if (storedCommit == null || !storedCommit.equals(manifestCommit)) {
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
	 * Loads commit hashes from the properties file.
	 * @return Properties object containing internalName -> commitHash mappings
	 */
	private Properties loadCommitHashes() {
		return repository.loadProperties();
	}

	/**
	 * Saves all properties to the file in the correct order:
	 * 1. packOrder
	 * 2. Commit hashes
	 */
	private void savePropertiesFile(Properties props) {
		repository.saveProperties(props);
	}

	/**
	 * Saves a commit hash for a pack to the properties file.
	 * @param internalName The internal name of the pack
	 * @param commitHash The commit hash to save
	 */
	private void saveCommitHash(String internalName, String commitHash) {
		Properties props = loadCommitHashes();
		props.setProperty(internalName, commitHash);
		savePropertiesFile(props);
	}

	/**
	 * Removes a commit hash from the properties file.
	 * @param internalName The internal name of the pack to remove
	 */
	private void removeCommitHash(String internalName) {
		Properties props = loadCommitHashes();
		if (props.remove(internalName) != null) {
			savePropertiesFile(props);
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
		Properties props = loadCommitHashes();
		List<String> packNames = new ArrayList<>();

		for (var pack : installedPacks) {
			if (pack instanceof DefaultResourcePack) {
				continue;
			}
			packNames.add(pack.getManifest().getInternalName());
		}

		if (!packNames.isEmpty()) {
			props.setProperty("packOrder", String.join(",", packNames));
		} else {
			props.remove("packOrder");
		}

		savePropertiesFile(props);
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

			// Map settings from properties file to config keys
			for (String key : settings.stringPropertyNames()) {
				String value = settings.getProperty(key).trim();
				configManager.setConfiguration(CONFIG_GROUP, key, value);
				log.info("Applied setting: {} = {}", key, value);
			}

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

}
