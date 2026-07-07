package rs117.hd.particles;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import rs117.hd.utils.Props;

/**
 * Re-serialize emitters.json through the manifest serde. Run from a live dev
 * session (export bundle) so gamevals are loaded via {@link rs117.hd.scene.GamevalManager}.
 */
public class RegenEmitters
{
	public static void main(String... args) throws IOException
	{
		Props.set("rlhd.resource-path", "src/main/resources");
		Path root = Path.of("src/main/resources/rs117/hd/particles");
		Path emittersPath = root.resolve("emitters.json");
		Path foldersPath = root.resolve("folders.json");

		Gson gson = new Gson();
		String json = Files.readString(emittersPath, StandardCharsets.UTF_8);
		Map<String, ProfileFolder> folders = gson.fromJson(
			Files.readString(foldersPath, StandardCharsets.UTF_8),
			new TypeToken<Map<String, ProfileFolder>>() {}.getType());
		if (folders == null)
		{
			folders = Map.of();
		}

		Map<String, EmitterProfile> profiles = EmitterManifest.parse(json, folders);
		if (profiles == null || profiles.isEmpty())
		{
			profiles = EmitterManifest.parseLegacyMap(json, gson);
		}
		if (profiles == null || profiles.isEmpty())
		{
			throw new IllegalStateException("No emitters to convert in " + emittersPath);
		}

		String manifest = EmitterManifest.serialize(gson, profiles, folders);
		Files.writeString(emittersPath, manifest + System.lineSeparator(), StandardCharsets.UTF_8);
		System.out.println("Wrote " + profiles.size() + " manifest entries to " + emittersPath.toAbsolutePath());
	}
}
