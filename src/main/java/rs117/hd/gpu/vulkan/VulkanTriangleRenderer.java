package rs117.hd.gpu.vulkan;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.VkAttachmentDescription;
import org.lwjgl.vulkan.VkAttachmentReference;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkExtent3D;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageSubresourceLayers;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkOffset3D;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;
import org.lwjgl.vulkan.VkRenderPassCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkSubpassDescription;
import org.lwjgl.vulkan.VkViewport;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static rs117.hd.gpu.vulkan.VulkanRenderDevice.checkResult;

/**
 * Renders a single hardcoded RGB triangle through a real Vulkan render pass + graphics pipeline
 * (SPIR-V shaders compiled at runtime via shaderc), then reads the result back to the CPU so it
 * can be composited into the GL scene by {@link VulkanDevOverlay}. This is a validation
 * harness, not a reusable renderer: everything here is a standalone, self-contained "hello
 * triangle" rather than anything built on {@link rs117.hd.gpu.RenderDevice}, since the point was
 * to find out what a full render actually requires before trying to generalize it.
 */
class VulkanTriangleRenderer {
	private static final String VERTEX_SHADER =
		"#version 450\n" +
		"layout(location = 0) out vec3 fragColor;\n" +
		"vec2 positions[3] = vec2[](vec2(0.0, -0.75), vec2(0.75, 0.75), vec2(-0.75, 0.75));\n" +
		"vec3 colors[3] = vec3[](vec3(1.0, 0.0, 0.0), vec3(0.0, 1.0, 0.0), vec3(0.0, 0.3, 1.0));\n" +
		"void main() {\n" +
		"    gl_Position = vec4(positions[gl_VertexIndex], 0.0, 1.0);\n" +
		"    fragColor = colors[gl_VertexIndex];\n" +
		"}\n";

	private static final String FRAGMENT_SHADER =
		"#version 450\n" +
		"layout(location = 0) in vec3 fragColor;\n" +
		"layout(location = 0) out vec4 outColor;\n" +
		"void main() {\n" +
		"    outColor = vec4(fragColor, 1.0);\n" +
		"}\n";

	private static final int COLOR_FORMAT = VK_FORMAT_R8G8B8A8_UNORM;

	private final VulkanRenderDevice owner;
	private final int width, height;

	private long colorImage, colorImageMemory, colorImageView;
	private long renderPass, framebuffer;
	private long pipelineLayout, pipeline;
	private long readbackBuffer, readbackBufferMemory;
	private long commandPool;
	private VkCommandBuffer commandBuffer;
	private long fence;

	VulkanTriangleRenderer(VulkanRenderDevice owner, int width, int height) {
		this.owner = owner;
		this.width = width;
		this.height = height;

		createColorImage();
		createRenderPass();
		createFramebuffer();
		createReadbackBuffer();
		createPipeline();
		createCommandBuffer();

		try (MemoryStack stack = stackPush()) {
			VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
			LongBuffer pFence = stack.mallocLong(1);
			checkResult(vkCreateFence(owner.device(), fenceInfo, null, pFence), "create fence");
			fence = pFence.get(0);
		}
	}

	private void createColorImage() {
		VkDevice device = owner.device();
		try (MemoryStack stack = stackPush()) {
			VkImageCreateInfo imageInfo = VkImageCreateInfo
				.calloc(stack)
				.sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
				.imageType(VK_IMAGE_TYPE_2D)
				.format(COLOR_FORMAT)
				.extent(VkExtent3D.calloc(stack).set(width, height, 1))
				.mipLevels(1)
				.arrayLayers(1)
				.samples(VK_SAMPLE_COUNT_1_BIT)
				.tiling(VK_IMAGE_TILING_OPTIMAL)
				.usage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
				.sharingMode(VK_SHARING_MODE_EXCLUSIVE)
				.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);

			LongBuffer pImage = stack.mallocLong(1);
			checkResult(vkCreateImage(device, imageInfo, null, pImage), "create color image");
			colorImage = pImage.get(0);

			VkMemoryRequirements memRequirements = VkMemoryRequirements.malloc(stack);
			vkGetImageMemoryRequirements(device, colorImage, memRequirements);

			VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo
				.calloc(stack)
				.sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
				.allocationSize(memRequirements.size())
				.memoryTypeIndex(findMemoryType(memRequirements.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));

			LongBuffer pMemory = stack.mallocLong(1);
			checkResult(vkAllocateMemory(device, allocInfo, null, pMemory), "allocate color image memory");
			colorImageMemory = pMemory.get(0);
			checkResult(vkBindImageMemory(device, colorImage, colorImageMemory, 0), "bind color image memory");

			VkImageSubresourceRange range = VkImageSubresourceRange
				.calloc(stack)
				.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
				.baseMipLevel(0)
				.levelCount(1)
				.baseArrayLayer(0)
				.layerCount(1);

			VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo
				.calloc(stack)
				.sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
				.image(colorImage)
				.viewType(VK_IMAGE_VIEW_TYPE_2D)
				.format(COLOR_FORMAT)
				.subresourceRange(range);

			LongBuffer pView = stack.mallocLong(1);
			checkResult(vkCreateImageView(device, viewInfo, null, pView), "create color image view");
			colorImageView = pView.get(0);
		}
	}

	private void createRenderPass() {
		try (MemoryStack stack = stackPush()) {
			VkAttachmentDescription.Buffer attachments = VkAttachmentDescription
				.calloc(1, stack)
				.format(COLOR_FORMAT)
				.samples(VK_SAMPLE_COUNT_1_BIT)
				.loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
				.storeOp(VK_ATTACHMENT_STORE_OP_STORE)
				.stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
				.stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
				.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
				// Automatically transitioned here at the end of the render pass, so the subsequent
				// vkCmdCopyImageToBuffer needs no manual barrier.
				.finalLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);

			VkAttachmentReference.Buffer colorAttachmentRef = VkAttachmentReference
				.calloc(1, stack)
				.attachment(0)
				.layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

			VkSubpassDescription.Buffer subpass = VkSubpassDescription
				.calloc(1, stack)
				.pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
				.colorAttachmentCount(1)
				.pColorAttachments(colorAttachmentRef);

			VkRenderPassCreateInfo renderPassInfo = VkRenderPassCreateInfo
				.calloc(stack)
				.sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
				.pAttachments(attachments)
				.pSubpasses(subpass);

			LongBuffer pRenderPass = stack.mallocLong(1);
			checkResult(vkCreateRenderPass(owner.device(), renderPassInfo, null, pRenderPass), "create render pass");
			renderPass = pRenderPass.get(0);
		}
	}

	private void createFramebuffer() {
		try (MemoryStack stack = stackPush()) {
			VkFramebufferCreateInfo framebufferInfo = VkFramebufferCreateInfo
				.calloc(stack)
				.sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
				.renderPass(renderPass)
				.pAttachments(stack.longs(colorImageView))
				.width(width)
				.height(height)
				.layers(1);

			LongBuffer pFramebuffer = stack.mallocLong(1);
			checkResult(vkCreateFramebuffer(owner.device(), framebufferInfo, null, pFramebuffer), "create framebuffer");
			framebuffer = pFramebuffer.get(0);
		}
	}

	private void createReadbackBuffer() {
		VkDevice device = owner.device();
		long sizeBytes = (long) width * height * 4;
		try (MemoryStack stack = stackPush()) {
			VkBufferCreateInfo bufferInfo = VkBufferCreateInfo
				.calloc(stack)
				.sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
				.size(sizeBytes)
				.usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT)
				.sharingMode(VK_SHARING_MODE_EXCLUSIVE);

			LongBuffer pBuffer = stack.mallocLong(1);
			checkResult(vkCreateBuffer(device, bufferInfo, null, pBuffer), "create readback buffer");
			readbackBuffer = pBuffer.get(0);

			VkMemoryRequirements memRequirements = VkMemoryRequirements.malloc(stack);
			vkGetBufferMemoryRequirements(device, readbackBuffer, memRequirements);

			int memoryType = findMemoryType(
				memRequirements.memoryTypeBits(),
				VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
			);

			VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo
				.calloc(stack)
				.sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
				.allocationSize(memRequirements.size())
				.memoryTypeIndex(memoryType);

			LongBuffer pMemory = stack.mallocLong(1);
			checkResult(vkAllocateMemory(device, allocInfo, null, pMemory), "allocate readback buffer memory");
			readbackBufferMemory = pMemory.get(0);
			checkResult(vkBindBufferMemory(device, readbackBuffer, readbackBufferMemory, 0), "bind readback buffer memory");
		}
	}

	private void createPipeline() {
		VkDevice device = owner.device();
		ByteBuffer vertSpirv = VulkanShaderCompiler.compileGlslToSpirv(VERTEX_SHADER, Shaderc.shaderc_glsl_vertex_shader, "triangle.vert");
		ByteBuffer fragSpirv = VulkanShaderCompiler.compileGlslToSpirv(FRAGMENT_SHADER, Shaderc.shaderc_glsl_fragment_shader, "triangle.frag");

		try (MemoryStack stack = stackPush()) {
			long vertModule = createShaderModule(device, stack, vertSpirv);
			long fragModule = createShaderModule(device, stack, fragSpirv);
			try {
				VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo
					.calloc(2, stack);
				stages
					.get(0)
					.sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
					.stage(VK_SHADER_STAGE_VERTEX_BIT)
					.module(vertModule)
					.pName(stack.UTF8("main"));
				stages
					.get(1)
					.sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
					.stage(VK_SHADER_STAGE_FRAGMENT_BIT)
					.module(fragModule)
					.pName(stack.UTF8("main"));

				VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo
					.calloc(stack)
					.sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO);

				VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo
					.calloc(stack)
					.sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
					.topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);

				VkViewport.Buffer viewport = VkViewport
					.calloc(1, stack)
					.x(0)
					.y(0)
					.width(width)
					.height(height)
					.minDepth(0)
					.maxDepth(1);

				VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
				scissor.get(0).offset().set(0, 0);
				scissor.get(0).extent().set(width, height);

				VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo
					.calloc(stack)
					.sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
					.pViewports(viewport)
					.pScissors(scissor);

				VkPipelineRasterizationStateCreateInfo rasterizer = VkPipelineRasterizationStateCreateInfo
					.calloc(stack)
					.sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
					.polygonMode(VK_POLYGON_MODE_FILL)
					.lineWidth(1.0f)
					.cullMode(VK_CULL_MODE_NONE)
					.frontFace(VK_FRONT_FACE_CLOCKWISE);

				VkPipelineMultisampleStateCreateInfo multisampling = VkPipelineMultisampleStateCreateInfo
					.calloc(stack)
					.sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
					.rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);

				VkPipelineColorBlendAttachmentState.Buffer blendAttachment = VkPipelineColorBlendAttachmentState
					.calloc(1, stack)
					.colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);

				VkPipelineColorBlendStateCreateInfo colorBlending = VkPipelineColorBlendStateCreateInfo
					.calloc(stack)
					.sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
					.pAttachments(blendAttachment);

				VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo
					.calloc(stack)
					.sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO);

				LongBuffer pLayout = stack.mallocLong(1);
				checkResult(vkCreatePipelineLayout(device, layoutInfo, null, pLayout), "create pipeline layout");
				pipelineLayout = pLayout.get(0);

				VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo
					.calloc(1, stack)
					.sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
					.pStages(stages)
					.pVertexInputState(vertexInput)
					.pInputAssemblyState(inputAssembly)
					.pViewportState(viewportState)
					.pRasterizationState(rasterizer)
					.pMultisampleState(multisampling)
					.pColorBlendState(colorBlending)
					.layout(pipelineLayout)
					.renderPass(renderPass)
					.subpass(0);

				LongBuffer pPipeline = stack.mallocLong(1);
				checkResult(
					vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, pipelineInfo, null, pPipeline),
					"create graphics pipeline"
				);
				pipeline = pPipeline.get(0);
			} finally {
				vkDestroyShaderModule(device, vertModule, null);
				vkDestroyShaderModule(device, fragModule, null);
			}
		}
	}

	private static long createShaderModule(VkDevice device, MemoryStack stack, ByteBuffer spirv) {
		VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo
			.calloc(stack)
			.sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
			.pCode(spirv);

		LongBuffer pModule = stack.mallocLong(1);
		checkResult(vkCreateShaderModule(device, createInfo, null, pModule), "create shader module");
		return pModule.get(0);
	}

	private void createCommandBuffer() {
		VkDevice device = owner.device();
		try (MemoryStack stack = stackPush()) {
			VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo
				.calloc(stack)
				.sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
				.flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
				.queueFamilyIndex(owner.graphicsQueueFamily());

			LongBuffer pPool = stack.mallocLong(1);
			checkResult(vkCreateCommandPool(device, poolInfo, null, pPool), "create command pool");
			commandPool = pPool.get(0);

			VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo
				.calloc(stack)
				.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
				.commandPool(commandPool)
				.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
				.commandBufferCount(1);

			PointerBuffer pCommandBuffer = stack.mallocPointer(1);
			checkResult(vkAllocateCommandBuffers(device, allocInfo, pCommandBuffer), "allocate command buffer");
			commandBuffer = new VkCommandBuffer(pCommandBuffer.get(0), device);
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

	/** Renders the triangle and returns the resulting RGBA8 pixels, row-major, top to bottom. */
	ByteBuffer renderFrame() {
		VkDevice device = owner.device();
		try (MemoryStack stack = stackPush()) {
			checkResult(vkResetCommandBuffer(commandBuffer, 0), "reset command buffer");

			VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo
				.calloc(stack)
				.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
				.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
			checkResult(vkBeginCommandBuffer(commandBuffer, beginInfo), "begin command buffer");

			VkClearValue.Buffer clearValues = VkClearValue.calloc(1, stack);
			clearValues.get(0).color().float32(0, 0.05f).float32(1, 0.05f).float32(2, 0.08f).float32(3, 1.0f);

			VkRect2D renderArea = VkRect2D.calloc(stack);
			renderArea.offset().set(0, 0);
			renderArea.extent().set(width, height);

			VkRenderPassBeginInfo renderPassBeginInfo = VkRenderPassBeginInfo
				.calloc(stack)
				.sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
				.renderPass(renderPass)
				.framebuffer(framebuffer)
				.renderArea(renderArea)
				.pClearValues(clearValues);

			vkCmdBeginRenderPass(commandBuffer, renderPassBeginInfo, VK_SUBPASS_CONTENTS_INLINE);
			vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
			vkCmdDraw(commandBuffer, 3, 1, 0, 0);
			vkCmdEndRenderPass(commandBuffer);

			VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
			region.get(0).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
			VkImageSubresourceLayers subresource = region.get(0).imageSubresource();
			subresource.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
			region.get(0).imageOffset(VkOffset3D.calloc(stack).set(0, 0, 0));
			region.get(0).imageExtent(VkExtent3D.calloc(stack).set(width, height, 1));

			vkCmdCopyImageToBuffer(commandBuffer, colorImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, readbackBuffer, region);

			checkResult(vkEndCommandBuffer(commandBuffer), "end command buffer");

			checkResult(vkResetFences(device, fence), "reset fence");

			VkSubmitInfo submitInfo = VkSubmitInfo
				.calloc(stack)
				.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
				.pCommandBuffers(stack.pointers(commandBuffer));
			checkResult(vkQueueSubmit(owner.graphicsQueue(), submitInfo, fence), "submit command buffer");

			checkResult(vkWaitForFences(device, fence, true, Long.MAX_VALUE), "wait for fence");

			long sizeBytes = (long) width * height * 4;
			PointerBuffer pData = stack.mallocPointer(1);
			checkResult(vkMapMemory(device, readbackBufferMemory, 0, sizeBytes, 0, pData), "map readback memory");
			try {
				ByteBuffer mapped = org.lwjgl.system.MemoryUtil.memByteBuffer(pData.get(0), (int) sizeBytes);
				ByteBuffer copy = ByteBuffer.allocateDirect((int) sizeBytes);
				copy.put(mapped).flip();
				return copy;
			} finally {
				vkUnmapMemory(device, readbackBufferMemory);
			}
		}
	}

	void destroy() {
		VkDevice device = owner.device();
		vkDestroyCommandPool(device, commandPool, null);
		vkDestroyFence(device, fence, null);
		vkDestroyPipeline(device, pipeline, null);
		vkDestroyPipelineLayout(device, pipelineLayout, null);
		vkDestroyFramebuffer(device, framebuffer, null);
		vkDestroyRenderPass(device, renderPass, null);
		vkDestroyBuffer(device, readbackBuffer, null);
		vkFreeMemory(device, readbackBufferMemory, null);
		vkDestroyImageView(device, colorImageView, null);
		vkDestroyImage(device, colorImage, null);
		vkFreeMemory(device, colorImageMemory, null);
	}
}
