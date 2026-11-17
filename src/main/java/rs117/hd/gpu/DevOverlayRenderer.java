package rs117.hd.gpu;

import java.nio.ByteBuffer;
import rs117.hd.utils.Destructible;

/**
 * A small, self-contained renderer whose output gets copied into a GL texture for on-screen
 * compositing, used to validate an experimental backend (e.g. {@code rs117.hd.gpu.vulkan}'s
 * triangle stub) by actually showing its output in the running game rather than only in a test.
 * Deliberately not part of {@link RenderDevice}: it's a one-off validation hook, not a resource
 * type any real renderer would use.
 */
public interface DevOverlayRenderer extends Destructible {
	int width();

	int height();

	/** Renders one frame and returns tightly-packed RGBA8 pixels, row-major, top row first. */
	ByteBuffer renderFrame();
}
