package rs117.hd.gpu.vulkan;

import java.nio.IntBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import rs117.hd.gpu.GpuFramebuffer;
import rs117.hd.gpu.GpuTexture;
import rs117.hd.gpu.RenderDevice;
import rs117.hd.gpu.TextureDesc;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Experimental Vulkan-backed {@link RenderDevice}, written purely to find out empirically what
 * this interface actually needs before designing more of it speculatively. It is NOT wired into
 * the live plugin anywhere - {@link rs117.hd.gpu.gl.GLRenderDevice} remains the only backend
 * HdPlugin ever instantiates. Validated by {@code VulkanRenderDeviceTest}, which skips itself on
 * machines without a usable Vulkan driver.
 * <p>
 * What this already exposed as real interface gaps (see each method's Javadoc for details):
 * <ul>
 *     <li>{@link #createFramebuffer()} can't be implemented at all - Vulkan needs every
 *     attachment's format known upfront via a VkRenderPass, whereas {@code GpuFramebuffer} is
 *     shaped around GL's create-empty-then-attach-later model.</li>
 *     <li>{@code TextureDesc} conflates image storage (format/size/mips) with sampler state
 *     (filter/wrap/compare/border) because GL bundles both into one texture object; Vulkan keeps
 *     them as two independently created/shared resources (VkImage vs. VkSampler). This stub
 *     ignores the sampler fields entirely.</li>
 *     <li>{@code GpuTexture.id()} returning {@code int} is a GL-specific escape hatch - Vulkan
 *     handles are 64-bit, so {@link VulkanTexture#id()} throws rather than silently truncating.</li>
 *     <li>{@code GpuTexture.bind()} is a no-op under Vulkan: there's no global per-texture-unit
 *     binding state: sampling is wired per-draw through descriptor sets, which nothing in
 *     {@code rs117.hd.gpu} models yet.</li>
 * </ul>
 */
public class VulkanRenderDevice implements RenderDevice {
	private final VkInstance instance;
	private final VkPhysicalDevice physicalDevice;
	private final VkDevice device;
	private final int graphicsQueueFamily;
	private final VkQueue graphicsQueue;

	public VulkanRenderDevice() {
		instance = createInstance();
		try {
			physicalDevice = pickPhysicalDevice(instance);
			graphicsQueueFamily = findGraphicsQueueFamily(physicalDevice);
			device = createLogicalDevice(physicalDevice, graphicsQueueFamily);

			try (MemoryStack stack = stackPush()) {
				PointerBuffer pQueue = stack.mallocPointer(1);
				vkGetDeviceQueue(device, graphicsQueueFamily, 0, pQueue);
				graphicsQueue = new VkQueue(pQueue.get(0), device);
			}
		} catch (RuntimeException ex) {
			vkDestroyInstance(instance, null);
			throw ex;
		}
	}

	VkDevice device() {
		return device;
	}

	VkPhysicalDevice physicalDevice() {
		return physicalDevice;
	}

	int graphicsQueueFamily() {
		return graphicsQueueFamily;
	}

	VkQueue graphicsQueue() {
		return graphicsQueue;
	}

	private static VkInstance createInstance() {
		try (MemoryStack stack = stackPush()) {
			VkApplicationInfo appInfo = VkApplicationInfo
				.calloc(stack)
				.sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
				.pApplicationName(stack.UTF8("117 HD Vulkan stub"))
				.applicationVersion(VK_MAKE_VERSION(1, 0, 0))
				.pEngineName(stack.UTF8("117 HD"))
				.engineVersion(VK_MAKE_VERSION(1, 0, 0))
				.apiVersion(VK_API_VERSION_1_0);

			VkInstanceCreateInfo createInfo = VkInstanceCreateInfo
				.calloc(stack)
				.sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
				.pApplicationInfo(appInfo);

			PointerBuffer pInstance = stack.mallocPointer(1);
			checkResult(vkCreateInstance(createInfo, null, pInstance), "create VkInstance");
			return new VkInstance(pInstance.get(0), createInfo);
		}
	}

	private static VkPhysicalDevice pickPhysicalDevice(VkInstance instance) {
		try (MemoryStack stack = stackPush()) {
			IntBuffer count = stack.ints(0);
			vkEnumeratePhysicalDevices(instance, count, null);
			int deviceCount = count.get(0);
			if (deviceCount == 0)
				throw new RuntimeException("No Vulkan-capable physical devices found");

			PointerBuffer devices = stack.mallocPointer(deviceCount);
			vkEnumeratePhysicalDevices(instance, count, devices);

			VkPhysicalDevice fallback = null;
			for (int i = 0; i < deviceCount; i++) {
				VkPhysicalDevice candidate = new VkPhysicalDevice(devices.get(i), instance);
				if (fallback == null)
					fallback = candidate;

				VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
				vkGetPhysicalDeviceProperties(candidate, props);
				if (props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU)
					return candidate;
			}
			return fallback;
		}
	}

	private static int findGraphicsQueueFamily(VkPhysicalDevice physicalDevice) {
		try (MemoryStack stack = stackPush()) {
			IntBuffer count = stack.ints(0);
			vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, null);
			int familyCount = count.get(0);

			VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.calloc(familyCount, stack);
			vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, families);

			for (int i = 0; i < familyCount; i++)
				if ((families.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0)
					return i;

			throw new RuntimeException("No graphics-capable Vulkan queue family found");
		}
	}

	private static VkDevice createLogicalDevice(VkPhysicalDevice physicalDevice, int queueFamilyIndex) {
		try (MemoryStack stack = stackPush()) {
			VkDeviceQueueCreateInfo.Buffer queueCreateInfos = VkDeviceQueueCreateInfo
				.calloc(1, stack)
				.sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
				.queueFamilyIndex(queueFamilyIndex)
				.pQueuePriorities(stack.floats(1.0f));

			VkDeviceCreateInfo deviceCreateInfo = VkDeviceCreateInfo
				.calloc(stack)
				.sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
				.pQueueCreateInfos(queueCreateInfos);

			PointerBuffer pDevice = stack.mallocPointer(1);
			checkResult(vkCreateDevice(physicalDevice, deviceCreateInfo, null, pDevice), "create VkDevice");
			return new VkDevice(pDevice.get(0), physicalDevice, deviceCreateInfo);
		}
	}

	static void checkResult(int result, String operation) {
		if (result != VK_SUCCESS)
			throw new RuntimeException("Vulkan error " + result + " while trying to " + operation);
	}

	@Override
	public GpuTexture createTexture2D(int textureUnit, TextureDesc desc) {
		return VulkanTexture.create(this, VK_IMAGE_TYPE_2D, VK_IMAGE_VIEW_TYPE_2D, 1, 0, desc);
	}

	@Override
	public GpuTexture createTexture2DArray(int textureUnit, TextureDesc desc) {
		return VulkanTexture.create(this, VK_IMAGE_TYPE_2D, VK_IMAGE_VIEW_TYPE_2D_ARRAY, Math.max(1, desc.depth), 0, desc);
	}

	@Override
	public GpuTexture createTextureCubeMap(int textureUnit, TextureDesc desc) {
		return VulkanTexture.create(this, VK_IMAGE_TYPE_2D, VK_IMAGE_VIEW_TYPE_CUBE, 6, VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT, desc);
	}

	@Override
	public GpuTexture createTexture3D(int textureUnit, TextureDesc desc) {
		return VulkanTexture.create(this, VK_IMAGE_TYPE_3D, VK_IMAGE_VIEW_TYPE_3D, 1, 0, desc);
	}

	/**
	 * Can't be implemented: {@code vkCreateFramebuffer} requires every attachment's VkImageView,
	 * plus a compatible VkRenderPass describing their formats/load-store ops, all up front in one
	 * call. {@code GpuFramebuffer}'s create-empty-then-{@code attachX(...)}-later shape (modeled on
	 * GL's {@code glFramebufferTexture*}) has no way to express that. A real Vulkan backend needs
	 * the interface reshaped around an attachment list (and a render-pass concept) passed to a
	 * single creation call, which isn't worth designing speculatively until it's actually needed.
	 */
	@Override
	public GpuFramebuffer createFramebuffer() {
		throw new UnsupportedOperationException(
			"GpuFramebuffer's create-then-attach shape doesn't map to Vulkan; see this method's Javadoc");
	}

	@Override
	public void destroy() {
		vkDestroyDevice(device, null);
		vkDestroyInstance(instance, null);
	}
}
