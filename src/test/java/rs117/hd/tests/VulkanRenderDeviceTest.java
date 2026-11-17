package rs117.hd.tests;

import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import rs117.hd.gpu.FilterMode;
import rs117.hd.gpu.GpuTexture;
import rs117.hd.gpu.PixelFormat;
import rs117.hd.gpu.TextureDesc;
import rs117.hd.gpu.WrapMode;
import rs117.hd.gpu.vulkan.VulkanRenderDevice;

/**
 * Validates the RenderDevice interface against a real Vulkan implementation. Not every machine
 * running this test suite has a Vulkan-capable driver (CI runners, VMs, older GPUs), so every
 * test skips itself via {@link Assume} rather than failing when device creation isn't possible.
 */
public class VulkanRenderDeviceTest {
	private VulkanRenderDevice device;

	@Before
	public void setUp() {
		try {
			device = new VulkanRenderDevice();
		} catch (Throwable t) {
			Assume.assumeNoException("No usable Vulkan driver on this machine", t);
		}
	}

	@After
	public void tearDown() {
		if (device != null)
			device.destroy();
	}

	@Test
	public void testCreate2DTexture() {
		GpuTexture texture = device.createTexture2D(0, new TextureDesc()
			.size(64, 64)
			.format(PixelFormat.RGBA8)
			.filter(FilterMode.LINEAR, FilterMode.LINEAR)
			.wrap(WrapMode.CLAMP_TO_EDGE, WrapMode.CLAMP_TO_EDGE));
		texture.destroy();
	}

	@Test
	public void testCreate2DArrayTexture() {
		GpuTexture texture = device.createTexture2DArray(0, new TextureDesc()
			.size(32, 32, 4)
			.format(PixelFormat.SRGB8_ALPHA8)
			.mipmapped(6));
		texture.destroy();
	}

	@Test
	public void testCreateCubeMapTexture() {
		GpuTexture texture = device.createTextureCubeMap(0, new TextureDesc()
			.size(128, 128)
			.format(PixelFormat.RGBA16F));
		texture.destroy();
	}

	@Test
	public void testCreate3DTexture() {
		GpuTexture texture = device.createTexture3D(0, new TextureDesc()
			.size(16, 16, 16)
			.format(PixelFormat.DEPTH16));
		texture.destroy();
	}

	@Test
	public void testResize() {
		GpuTexture texture = device.createTexture2D(0, new TextureDesc()
			.size(16, 16)
			.format(PixelFormat.RGBA8));
		texture.resize(32, 32);
		texture.destroy();
	}

	@Test
	public void testIdThrowsRatherThanTruncating() {
		GpuTexture texture = device.createTexture2D(0, new TextureDesc().size(4, 4).format(PixelFormat.RGBA8));
		try {
			Assert.assertThrows(UnsupportedOperationException.class, texture::id);
		} finally {
			texture.destroy();
		}
	}

	@Test
	public void testCreateFramebufferIsUnsupported() {
		Assert.assertThrows(UnsupportedOperationException.class, device::createFramebuffer);
	}
}
