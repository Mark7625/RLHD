package rs117.hd.resourcepacks;

import com.google.common.base.Charsets;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Properties;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.resourcepacks.data.Manifest;
import rs117.hd.utils.ResourcePath;

@Slf4j
public abstract class AbstractResourcePack {
	private Manifest manifest;
	public final ResourcePath path;

	@Getter
	@Setter
	private boolean developmentPack = false;

	public AbstractResourcePack(ResourcePath resourcePackFileIn) {
		this.path = resourcePackFileIn;
	}

	public InputStream getInputStream(String... parts) throws IOException {
		return getResource(parts).toInputStream();
	}

	public ResourcePath getResource(String... parts) {
		return path.resolve(parts);
	}

	/**
	 * Check if a resource exists in this pack.
	 * @param parts The path parts to the resource
	 * @return true if the resource exists, false otherwise
	 */
	public boolean hasResource(String... parts) {
		return getResource(parts).exists();
	}

	/**
	 * Lists all JSON files in the specified directory.
	 * @param directory The directory path (e.g., "environments")
	 * @return List of ResourcePath objects pointing to JSON files
	 */
	public abstract List<ResourcePath> listJsonFiles(String directory);

	public Manifest getManifest() {
		try {
			if (manifest == null) {
				manifest = readMetadata(getInputStream("pack.properties"));
			}
			return manifest;
		} catch (IOException e) {
			log.warn("Failed to load pack.properties for resource pack {}: {}", this, e.getMessage());
			return null;
		}
	}

	private static Manifest readMetadata(InputStream inputStream) {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, Charsets.UTF_8))) {
			Properties props = new Properties();
			props.load(reader);
			String displayName = props.getProperty("displayName");
			if (displayName == null || displayName.trim().isEmpty()) {
				log.warn("Resource pack metadata is missing displayName");
				return null;
			}
			return new Manifest(displayName, props.getProperty("description"), props.getProperty("author"));
		} catch (IOException ex) {
			log.warn("Failed to read resource pack metadata", ex);
			return null;
		}
	}

	public String getPackName() {
		return getManifest().getInternalName();
	}

	public boolean isValid() {
		return getManifest() != null;
	}

	public BufferedImage getPackImage() {
		try {
			return getResource("icon.png").loadImage();
		} catch (IOException ex) {
			log.warn("Pack: {} has no defined icon", getPackName());
			return null;
		}
	}

	public boolean hasPackImage() {
		return hasResource("icon.png");
	}

	public BufferedImage getPackImage(boolean compactView) {
		if (compactView && hasResource("compact-icon.png")) {
			try {
				return getResource("compact-icon.png").loadImage();
			} catch (IOException e) {
				log.warn("Pack: {} has compact-icon.png but failed to load, falling back to icon.png", getPackName());
			}
		}
		return getPackImage();
	}

	public boolean hasPackImage(boolean compactView) {
		if (compactView) {
			return hasResource("compact-icon.png") || hasPackImage();
		}
		return hasPackImage();
	}
}
