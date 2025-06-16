package rs117.hd.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.scene.GamevalManager;

public class ReplaceIds {

	public GamevalManager gameValRepository;

	public ReplaceIds(GamevalManager gameValRepository) {
		this.gameValRepository = gameValRepository;
	}

	public static String fromKey(String key) {
		switch (key) {
			case "npcIds":
				return "npcs";
			case "graphicsObjectIds":
			case "projectileIds":
				return "spotanims";
			case "animationIds":
				return "anims";
			case "objectIds":
				return "objects";
			default:
				throw new IllegalArgumentException("Unknown key: " + key);
		}
	}

	public void replaceIdsWithNames() throws IOException {
		String[] files = {
			"C:\\Users\\Home\\Desktop\\New folder\\lights.json",
			"C:\\Users\\Home\\Desktop\\New folder\\model_overrides.json"
		};

		// Keys to look for
		String[] keys = {"npcIds", "graphicsObjectIds", "objectIds", "animationIds", "projectileIds"};
		String keyPattern = String.join("|", keys);
		Pattern keyValuePattern = Pattern.compile("\"(" + keyPattern + ")\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL);
		Pattern numberPattern = Pattern.compile("\\b(\\d+)\\b");

		for (String filePath : files) {
			Path path = Paths.get(filePath);
			String content = Files.readString(path);
			Matcher matcher = keyValuePattern.matcher(content);

			StringBuffer sb = new StringBuffer();
			while (matcher.find()) {
				String key = matcher.group(1);
				String arrayContent = matcher.group(2); // Inside [ ... ]
				String type = fromKey(key);

				Matcher numMatcher = numberPattern.matcher(arrayContent);
				StringBuffer replacedArray = new StringBuffer();

				while (numMatcher.find()) {
					int id = Integer.parseInt(numMatcher.group(1));
					String name = gameValRepository.getName(type, id);
					String replacement = name != null ? "\"" + name + "\"" : numMatcher.group(1);
					numMatcher.appendReplacement(replacedArray, Matcher.quoteReplacement(replacement));
				}
				numMatcher.appendTail(replacedArray);

				String fullArray = matcher.group(0);
				String replacedFullArray = fullArray.replace(matcher.group(2), replacedArray.toString());
				matcher.appendReplacement(sb, Matcher.quoteReplacement(replacedFullArray));
			}
			matcher.appendTail(sb);

			Files.writeString(path, sb.toString().replace("-\"SWARM_ATTACK\"","-1"));
		}
	}
}
