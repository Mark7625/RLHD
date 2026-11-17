package rs117.hd.gpu.vulkan;

import java.nio.ByteBuffer;

import static org.lwjgl.util.shaderc.Shaderc.*;

/** Compiles GLSL source to SPIR-V bytecode at runtime via shaderc, for the Vulkan validation stub. */
final class VulkanShaderCompiler {
	private VulkanShaderCompiler() {}

	static ByteBuffer compileGlslToSpirv(String source, int shaderKind, String fileName) {
		long compiler = shaderc_compiler_initialize();
		if (compiler == 0)
			throw new RuntimeException("Failed to initialize shaderc compiler");

		try {
			long result = shaderc_compile_into_spv(compiler, source, shaderKind, fileName, "main", 0);
			try {
				if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
					String error = shaderc_result_get_error_message(result);
					throw new RuntimeException("Failed to compile " + fileName + " to SPIR-V: " + error);
				}

				ByteBuffer spirv = shaderc_result_get_bytes(result);
				ByteBuffer copy = ByteBuffer.allocateDirect(spirv.remaining());
				copy.put(spirv).flip();
				return copy;
			} finally {
				shaderc_result_release(result);
			}
		} finally {
			shaderc_compiler_release(compiler);
		}
	}
}
