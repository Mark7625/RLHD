package rs117.hd.resourcepacks.impl;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.resourcepacks.AbstractResourcePack;
import rs117.hd.utils.ResourcePath;

@Slf4j
public class DefaultResourcePack extends AbstractResourcePack {
    public DefaultResourcePack(ResourcePath resourcePackFileIn) {
        super(resourcePackFileIn);
    }

    @Override
    protected InputStream getInputStreamByName(String name) throws IOException {
		return this.path.resolve(name).toInputStream();
    }

    @Override
    public InputStream getInputStream(String... parts) throws IOException {
		return this.path.resolve(parts).toInputStream();
    }

	@Override
	public ResourcePath getResource(String... parts) {
		return this.path.resolve(parts);
	}

    @Override
    public BufferedImage getPackImage() {
		ResourcePath path = this.path.resolve("icon.png");
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
