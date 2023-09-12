package rs117.hd.resourcepacks.impl;

import lombok.extern.slf4j.Slf4j;
import rs117.hd.resourcepacks.AbstractResourcePack;
import rs117.hd.utils.ResourcePath;

import java.awt.image.BufferedImage;
import java.io.*;

@Slf4j
public class DefaultResourcePack extends AbstractResourcePack {

    public DefaultResourcePack(ResourcePath resourcePackFileIn) {
        super(resourcePackFileIn);
    }

    @Override
    protected boolean hasResourceName(String name) {
        return (new File(this.resourcePackFile.toFile(), name)).isFile();
    }

    @Override
    protected InputStream getInputStreamByName(String name) throws IOException {
        return this.resourcePackFile.resolve(name).toInputStream();
    }

    @Override
    public InputStream getInputStream(String... parts) throws IOException {
        return this.resourcePackFile.resolve(parts).toInputStream();
    }

	@Override
	public ResourcePath getResource(String... parts) {
		return this.resourcePackFile.resolve(parts);
	}

    @Override
    public BufferedImage getPackImage() {
        ResourcePath path = resourcePackFile.resolve("icon.png");
        try {
            return path.loadImage();
        } catch (IOException e) {
            log.warn("Pack: {} has no defined icon in {}", getPackName(),path);
            return null;
        }
    }

    @Override
    public boolean hasPackImage() {
        return getPackImage() != null;
    }

}