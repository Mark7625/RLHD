package rs117.hd.particles;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class EmitterManifestSerdeTest
{
	@Test
	public void bundledManifestRoundTrips() throws IOException
	{
		String json = Files.readString(
			Path.of("src/main/resources/rs117/hd/particles/emitters.json"),
			StandardCharsets.UTF_8);
		Assert.assertTrue(EmitterManifest.isManifestJson(json));

		Map<String, ProfileFolder> folders = new Gson().fromJson(
			Files.readString(
				Path.of("src/main/resources/rs117/hd/particles/folders.json"),
				StandardCharsets.UTF_8),
			new com.google.gson.reflect.TypeToken<Map<String, ProfileFolder>>() {}.getType());

		Map<String, EmitterProfile> parsed = EmitterManifest.parse(json, folders);
		Assert.assertNotNull(parsed);
		Assert.assertEquals(107, parsed.size());
	}

	@Test
	public void weatherPlacementRoundTrips()
	{
		Gson gson = new Gson();
		String json = "[{\"description\":\"Wintertodt snow\",\"particleId\":\"snow\","
			+ "\"weatherEmitters\":[{\"weatherAreas\":[\"WINTERTODT_ARENA\"],\"weatherParticlesPerTile\":10}]}]";
		Map<String, EmitterProfile> parsed = EmitterManifest.parse(json, Map.of());
		Assert.assertNotNull(parsed);
		Assert.assertEquals(1, parsed.size());
		EmitterProfile profile = parsed.values().iterator().next();
		Assert.assertTrue(profile.isWeatherTarget());
		Assert.assertEquals(List.of("WINTERTODT_ARENA"), profile.getWeatherAreas());
		Assert.assertEquals(10f, profile.getWeatherParticlesPerTile(), 0.001f);

		String out = EmitterManifest.serialize(gson, parsed, Map.of());
		Map<String, EmitterProfile> again = EmitterManifest.parse(out, Map.of());
		Assert.assertNotNull(again);
		Assert.assertEquals(1, again.size());
		EmitterProfile round = again.values().iterator().next();
		Assert.assertEquals(profile.getWeatherAreas(), round.getWeatherAreas());
		Assert.assertEquals(profile.getWeatherParticlesPerTile(), round.getWeatherParticlesPerTile(), 0.001f);
	}
}
