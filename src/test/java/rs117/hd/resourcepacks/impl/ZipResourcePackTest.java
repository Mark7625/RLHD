package rs117.hd.resourcepacks.impl;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Assert;
import org.junit.Test;

public class ZipResourcePackTest {
	@Test
	public void findsResourcesBelowArchiveRoot() throws IOException {
		File archive = File.createTempFile("resource-pack", ".zip");
		try {
			try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(archive))) {
				write(output, "example-pack/pack.properties", "displayName=Example Pack\n");
				write(output, "example-pack/environments/example.json", "{}");
			}

			ZipResourcePack pack = new ZipResourcePack(archive);
			try {
				Assert.assertTrue(pack.isValid());
				Assert.assertTrue(pack.hasResource("environments", "example.json"));
				Assert.assertEquals(1, pack.listJsonFiles("environments").size());
				Assert.assertTrue(pack.getResource("environments", "example.json").toPosixPath().endsWith(".zip!/environments/example.json"));
			} finally {
				pack.close();
			}
		} finally {
			Assert.assertTrue(archive.delete());
		}
	}

	private static void write(ZipOutputStream output, String name, String content) throws IOException {
		output.putNextEntry(new ZipEntry(name));
		output.write(content.getBytes(StandardCharsets.UTF_8));
		output.closeEntry();
	}
}
