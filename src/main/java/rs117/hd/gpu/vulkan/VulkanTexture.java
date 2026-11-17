package rs117.hd.gpu.vulkan;

import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkExtent3D;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import rs117.hd.gpu.GpuTexture;
import rs117.hd.gpu.TextureDesc;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static rs117.hd.gpu.vulkan.VulkanRenderDevice.checkResult;

/**
 * See {@link VulkanRenderDevice}'s class Javadoc for the interface gaps this implementation
 * exposed. In particular: {@link #id()} throws (VkImage handles don't fit in {@code int}),
 * {@link #bind()} is a no-op (Vulkan has no per-unit binding state), and {@code desc}'s
 * filter/wrap/compare/border fields are ignored entirely (those are VkSampler properties in
 * Vulkan, a separate resource this stub doesn't create).
 */
class VulkanTexture implements GpuTexture {
	private final VulkanRenderDevice owner;
	private final TextureDesc desc;
	private final int imageType;
	private final int viewType;
	private final int arrayLayers;
	private final int createFlags;

	private int width, height, depth;
	private long image = VK_NULL_HANDLE;
	private long imageView = VK_NULL_HANDLE;
	private long memory = VK_NULL_HANDLE;

	private VulkanTexture(VulkanRenderDevice owner, int imageType, int viewType, int arrayLayers, int createFlags, TextureDesc desc) {
		this.owner = owner;
		this.imageType = imageType;
		this.viewType = viewType;
		this.arrayLayers = arrayLayers;
		this.createFlags = createFlags;
		this.desc = desc;
	}

	static VulkanTexture create(
		VulkanRenderDevice owner,
		int imageType,
		int viewType,
		int arrayLayers,
		int createFlags,
		TextureDesc desc
	) {
		VulkanTexture texture = new VulkanTexture(owner, imageType, viewType, arrayLayers, createFlags, desc);
		if (desc.width > 0 && desc.height > 0)
			texture.allocate(desc.width, desc.height, desc.depth);
		return texture;
	}

	@Override
	public int id() {
		throw new UnsupportedOperationException(
			"VkImage/VkImageView handles are 64-bit; GpuTexture.id() returning int is a GL-specific " +
			"escape hatch used only by raw GL/RenderState call sites, which never run under this backend");
	}

	@Override
	public void bind() {
		// Intentional no-op: Vulkan has no global per-texture-unit binding state to mutate here.
		// Sampling would be wired per-draw through a descriptor set, which GpuTexture doesn't model.
	}

	@Override
	public void resize(int width, int height) {
		if (this.width == width && this.height == height)
			return;
		destroy();
		allocate(width, height, desc.depth);
	}

	private void allocate(int width, int height, int depth) {
		this.width = width;
		this.height = height;
		this.depth = depth;

		VkDevice device = owner.device();
		int format = VulkanFormats.format(desc.format);
		boolean isDepth = VulkanFormats.isDepthFormat(desc.format);
		int usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT |
			(isDepth ? VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT : VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT);
		int mipLevels = Math.max(1, desc.mipLevels);

		try (MemoryStack stack = stackPush()) {
			VkExtent3D extent = VkExtent3D
				.calloc(stack)
				.set(width, height, imageType == VK_IMAGE_TYPE_3D ? Math.max(1, depth) : 1);

			VkImageCreateInfo imageInfo = VkImageCreateInfo
				.calloc(stack)
				.sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
				.flags(createFlags)
				.imageType(imageType)
				.format(format)
				.extent(extent)
				.mipLevels(mipLevels)
				.arrayLayers(arrayLayers)
				.samples(VK_SAMPLE_COUNT_1_BIT)
				.tiling(VK_IMAGE_TILING_OPTIMAL)
				.usage(usage)
				.sharingMode(VK_SHARING_MODE_EXCLUSIVE)
				.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);

			LongBuffer pImage = stack.mallocLong(1);
			checkResult(vkCreateImage(device, imageInfo, null, pImage), "create VkImage");
			image = pImage.get(0);

			VkMemoryRequirements memRequirements = VkMemoryRequirements.malloc(stack);
			vkGetImageMemoryRequirements(device, image, memRequirements);

			VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo
				.calloc(stack)
				.sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
				.allocationSize(memRequirements.size())
				.memoryTypeIndex(findMemoryType(memRequirements.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));

			LongBuffer pMemory = stack.mallocLong(1);
			checkResult(vkAllocateMemory(device, allocInfo, null, pMemory), "allocate VkImage memory");
			memory = pMemory.get(0);

			checkResult(vkBindImageMemory(device, image, memory, 0), "bind VkImage memory");

			VkImageSubresourceRange range = VkImageSubresourceRange
				.calloc(stack)
				.aspectMask(isDepth ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT)
				.baseMipLevel(0)
				.levelCount(mipLevels)
				.baseArrayLayer(0)
				.layerCount(arrayLayers);

			VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo
				.calloc(stack)
				.sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
				.image(image)
				.viewType(viewType)
				.format(format)
				.subresourceRange(range);

			LongBuffer pView = stack.mallocLong(1);
			checkResult(vkCreateImageView(device, viewInfo, null, pView), "create VkImageView");
			imageView = pView.get(0);
		}
	}

	private int findMemoryType(int typeFilter, int properties) {
		try (MemoryStack stack = stackPush()) {
			VkPhysicalDeviceMemoryProperties memProperties = VkPhysicalDeviceMemoryProperties.malloc(stack);
			vkGetPhysicalDeviceMemoryProperties(owner.physicalDevice(), memProperties);

			for (int i = 0; i < memProperties.memoryTypeCount(); i++) {
				boolean typeMatches = (typeFilter & (1 << i)) != 0;
				boolean propertiesMatch = (memProperties.memoryTypes(i).propertyFlags() & properties) == properties;
				if (typeMatches && propertiesMatch)
					return i;
			}
		}
		throw new RuntimeException("Failed to find a Vulkan memory type matching filter " + typeFilter);
	}

	@Override
	public void destroy() {
		VkDevice device = owner.device();
		if (imageView != VK_NULL_HANDLE)
			vkDestroyImageView(device, imageView, null);
		if (image != VK_NULL_HANDLE)
			vkDestroyImage(device, image, null);
		if (memory != VK_NULL_HANDLE)
			vkFreeMemory(device, memory, null);
		imageView = image = memory = VK_NULL_HANDLE;
	}
}
