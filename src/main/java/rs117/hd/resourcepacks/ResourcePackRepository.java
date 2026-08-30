package rs117.hd.resourcepacks;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Properties;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import rs117.hd.resourcepacks.impl.FileResourcePack;
import rs117.hd.resourcepacks.impl.ZipResourcePack;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

@Singleton
@Slf4j
final class ResourcePackRepository {
	private static final ResourcePath PACK_DIRECTORY = Props.getFolder("117hd-resource-packs", () -> ResourcePath.path(new File(RuneLite.RUNELITE_DIR, "117hd-resource-packs").getPath()));
	private static final ResourcePath PROPERTIES_FILE = Props.getFile("117hd-resource-pack-commits", () -> ResourcePath.path(new File(RuneLite.RUNELITE_DIR, "117hd-resource-packs").getPath(), "commits.properties"));
	private static final FileFilter PACK_FILTER = file -> file.isFile() || file.isDirectory() && new File(file, "pack.properties").isFile();

	List<AbstractResourcePack> loadInstalledPacks() {
		Properties properties = loadProperties();
		LinkedHashMap<String, AbstractResourcePack> packsByName = new LinkedHashMap<>();
		File[] files = PACK_DIRECTORY.exists() ? PACK_DIRECTORY.toFile().listFiles(PACK_FILTER) : null;
		if (files != null) {
			Arrays.sort(files, (left, right) -> left.getName().compareToIgnoreCase(right.getName()));
			for (File file : files) {
				try {
					AbstractResourcePack pack = createPack(file);
					if (!pack.isValid()) {
						log.warn("Ignoring resource pack with invalid metadata: {}", file);
						close(pack);
						continue;
					}
					close(packsByName.put(pack.getManifest().getInternalName(), pack));
				} catch (RuntimeException ex) {
					log.warn("Ignoring unreadable resource pack: {}", file, ex);
				}
			}
		}

		List<AbstractResourcePack> orderedPacks = new ArrayList<>();
		String packOrder = properties.getProperty("packOrder");
		if (packOrder != null && !packOrder.isEmpty()) {
			for (String internalName : packOrder.split(",")) {
				AbstractResourcePack pack = packsByName.remove(internalName.trim());
				if (pack != null)
					orderedPacks.add(pack);
			}
		}
		orderedPacks.addAll(packsByName.values());
		return orderedPacks;
	}

	boolean ensurePackDirectory() {
		return PACK_DIRECTORY.toFile().mkdirs() || PACK_DIRECTORY.exists();
	}

	File archiveFile(String internalName) {
		return PACK_DIRECTORY.resolve(internalName + ".zip").toFile();
	}

	AbstractResourcePack createPack(File file) {
		AbstractResourcePack pack = file.getName().toLowerCase().endsWith(".zip") ? new ZipResourcePack(file) : new FileResourcePack(file);
		pack.setDevelopmentPack(!(pack instanceof ZipResourcePack));
		return pack;
	}

	void close(AbstractResourcePack pack) {
		if (pack instanceof ZipResourcePack)
			((ZipResourcePack) pack).close();
	}

	Properties loadProperties() {
		Properties properties = new Properties();
		File file = PROPERTIES_FILE.toFile();
		if (!file.isFile())
			return properties;
		try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
			properties.load(reader);
		} catch (IOException ex) {
			log.warn("Failed to load resource pack properties from {}", file, ex);
		}
		return properties;
	}

	void saveProperties(Properties properties) {
		File destination = PROPERTIES_FILE.toFile();
		File temporaryFile = new File(destination.getPath() + ".tmp");
		try {
			File parent = destination.getParentFile();
			if (parent != null && !parent.mkdirs() && !parent.isDirectory())
				throw new IOException("Unable to create properties directory: " + parent);
			try (FileOutputStream output = new FileOutputStream(temporaryFile)) {
				writeLine(output, "# Resource pack commit hashes and order - packOrder:comma-separated-list, internalName:commitHash");
				String order = properties.getProperty("packOrder");
				if (order != null)
					writeLine(output, "packOrder=" + order);
				List<String> keys = new ArrayList<>(properties.stringPropertyNames());
				keys.remove("packOrder");
				keys.sort(String::compareTo);
				if (!keys.isEmpty()) {
					writeLine(output, "");
					writeLine(output, "# Commit hashes");
					for (String key : keys)
						writeLine(output, key + "=" + properties.getProperty(key));
				}
				output.getFD().sync();
			}
			moveReplacing(temporaryFile, destination);
		} catch (IOException ex) {
			log.warn("Failed to save resource pack properties", ex);
			if (temporaryFile.exists() && !temporaryFile.delete())
				log.warn("Failed to delete temporary properties file: {}", temporaryFile);
		}
	}

	private static void writeLine(FileOutputStream output, String line) throws IOException {
		output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
	}

	private static void moveReplacing(File source, File destination) throws IOException {
		try {
			Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException ex) {
			Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
