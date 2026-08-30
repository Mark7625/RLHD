package rs117.hd.resourcepacks.impl;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import rs117.hd.resourcepacks.AbstractResourcePack;
import rs117.hd.utils.ResourcePath;

public class FileResourcePack extends AbstractResourcePack {
	public FileResourcePack(File resourcePackFileIn) {
		super(ResourcePath.path(resourcePackFileIn.getPath()));
	}

	@Override
	public List<ResourcePath> listJsonFiles(String directory) {
		List<ResourcePath> jsonFiles = new ArrayList<>();
		ResourcePath dirPath = getResource(directory);
		if (!dirPath.exists()) {
			return jsonFiles;
		}

		File dir = dirPath.toFile();
		if (!dir.isDirectory()) {
			return jsonFiles;
		}

		File[] files = dir.listFiles((file, name) -> name.toLowerCase().endsWith(".json"));
		if (files != null) {
			for (File file : files) {
				if (file.isFile()) {
					jsonFiles.add(getResource(directory, file.getName()));
				}
			}
		}

		return jsonFiles;
	}
}
