package rs117.hd.resourcepacks.impl;

import lombok.extern.slf4j.Slf4j;
import rs117.hd.resourcepacks.AbstractResourcePack;
import rs117.hd.utils.ResourcePath;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

@Slf4j
public class FileResourcePack extends AbstractResourcePack {

    public FileResourcePack(File resourcePackFileIn) {
        super(ResourcePath.path(resourcePackFileIn.getPath()));
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