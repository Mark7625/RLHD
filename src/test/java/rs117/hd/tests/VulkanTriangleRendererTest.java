package rs117.hd.tests;

import java.nio.ByteBuffer;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import rs117.hd.gpu.DevOverlayRenderer;
import rs117.hd.gpu.vulkan.VulkanDevOverlay;

/**
 * Renders the Vulkan stub's hardcoded triangle offscreen and checks real pixels came back, before
 * ever trying to composite it into the live game. Skips itself on machines without a usable
 * Vulkan driver, same as VulkanRenderDeviceTest.
 */
public class VulkanTriangleRendererTest {
	private static final int SIZE = 64;

	private DevOverlayRenderer overlay;

	@Before
	public void setUp() {
		try {
			overlay = new VulkanDevOverlay(SIZE, SIZE);
		} catch (Throwable t) {
			Assume.assumeNoException("No usable Vulkan driver on this machine", t);
		}
	}

	@After
	public void tearDown() {
		if (overlay != null)
			overlay.destroy();
	}

	@Test
	public void testTriangleIsVisible() {
		ByteBuffer pixels = overlay.renderFrame();
		Assert.assertEquals(SIZE * SIZE * 4, pixels.remaining());

		// The clear color is dark blue-grey; the triangle covers roughly the center-bottom half of
		// the frame with saturated red/green/blue corners. Sample the exact center, which the
		// triangle (apex at y=-0.75, base corners at y=0.75) should always cover regardless of
		// exact rasterization rules, and confirm it isn't just the background clear color.
		int centerPixel = pixelAt(pixels, SIZE / 2, SIZE / 2);
		int clearColor = packRgba(13, 13, 20, 255); // 0.05, 0.05, 0.08 clear color as sRGB bytes

		Assert.assertNotEquals(
			"Expected the triangle to cover the center pixel, but found the clear color",
			clearColor,
			centerPixel
		);
	}

	private static int pixelAt(ByteBuffer pixels, int x, int y) {
		int offset = (y * SIZE + x) * 4;
		int r = pixels.get(offset) & 0xFF;
		int g = pixels.get(offset + 1) & 0xFF;
		int b = pixels.get(offset + 2) & 0xFF;
		int a = pixels.get(offset + 3) & 0xFF;
		return packRgba(r, g, b, a);
	}

	private static int packRgba(int r, int g, int b, int a) {
		return (r << 24) | (g << 16) | (b << 8) | a;
	}
}
