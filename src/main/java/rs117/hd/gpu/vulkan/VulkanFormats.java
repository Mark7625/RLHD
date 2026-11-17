package rs117.hd.gpu.vulkan;

import rs117.hd.gpu.PixelFormat;

import static org.lwjgl.vulkan.VK10.*;

/** Translates the backend-agnostic enums in {@code rs117.hd.gpu} to their Vulkan 1.0 equivalents. */
final class VulkanFormats {
	private VulkanFormats() {}

	static int format(PixelFormat format) {
		switch (format) {
			case RGBA8:
				return VK_FORMAT_R8G8B8A8_UNORM;
			case SRGB8_ALPHA8:
				return VK_FORMAT_R8G8B8A8_SRGB;
			case RGBA16F:
				return VK_FORMAT_R16G16B16A16_SFLOAT;
			case RGBA16UI:
				return VK_FORMAT_R16G16B16A16_UINT;
			case DEPTH16:
				return VK_FORMAT_D16_UNORM;
			case DEPTH24:
				// Vulkan has no bare D24 format; this packed 32-bit format (24 depth bits + 8 unused)
				// is the conventional substitute, though support for it isn't guaranteed everywhere
				// and would need an explicit vkGetPhysicalDeviceFormatProperties check in real use.
				return VK_FORMAT_X8_D24_UNORM_PACK32;
			default:
				throw new IllegalArgumentException("Unsupported format: " + format);
		}
	}

	static boolean isDepthFormat(PixelFormat format) {
		return format == PixelFormat.DEPTH16 || format == PixelFormat.DEPTH24;
	}
}
