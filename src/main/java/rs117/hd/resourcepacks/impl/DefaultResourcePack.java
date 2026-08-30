package rs117.hd.resourcepacks.impl;

import java.util.ArrayList;
import java.util.List;
import rs117.hd.resourcepacks.AbstractResourcePack;
import rs117.hd.utils.ResourcePath;

public class DefaultResourcePack extends AbstractResourcePack {
	public DefaultResourcePack(ResourcePath resourcePackFileIn) {
		super(resourcePackFileIn);
	}

	@Override
	public List<ResourcePath> listJsonFiles(String directory) {
		// DefaultResourcePack uses ClassResourcePath which doesn't support listing files
		// Return empty list as default pack environments are loaded from the main environments.json
		return new ArrayList<>();
	}
}
