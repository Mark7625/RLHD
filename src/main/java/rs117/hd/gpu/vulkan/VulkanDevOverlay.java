package rs117.hd.gpu.vulkan;

import java.nio.ByteBuffer;
import rs117.hd.gpu.DevOverlayRenderer;

/**
 * Wires {@link VulkanRenderDevice} + {@link VulkanTriangleRenderer} up as a {@link DevOverlayRenderer},
 * so {@code HdPlugin} can show the Vulkan stub's output composited into the live game window
 * without ever referencing Vulkan types directly (see {@code HdPlugin}'s use of this through the
 * neutral interface, guarded by a try-catch so a real install without these classes on its
 * classpath degrades gracefully instead of crashing).
 */
public class VulkanDevOverlay implements DevOverlayRenderer {
	private final VulkanRenderDevice device;
	private final VulkanTriangleRenderer renderer;
	private final int width, height;

	public VulkanDevOverlay(int width, int height) {
		this.width = width;
		this.height = height;
		device = new VulkanRenderDevice();
		try {
			renderer = new VulkanTriangleRenderer(device, width, height);
		} catch (RuntimeException ex) {
			device.destroy();
			throw ex;
		}
	}

	@Override
	public int width() {
		return width;
	}

	@Override
	public int height() {
		return height;
	}

	@Override
	public ByteBuffer renderFrame() {
		return renderer.renderFrame();
	}

	@Override
	public void destroy() {
		renderer.destroy();
		device.destroy();
	}
}
