package me.cortex.voxy.client.core.backend.vk;

import me.cortex.voxy.common.Logger;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

public class VkShaderCompiler {
    private static final Path CACHE_DIR = Path.of(".cache", "voxy", "spv");
    private final long compiler;

    public VkShaderCompiler() {
        this.compiler = Shaderc.shaderc_compiler_initialize();
        if (this.compiler == 0) {
            throw new RuntimeException("Failed to initialize Shaderc compiler");
        }
        try {
            Files.createDirectories(CACHE_DIR);
        } catch (IOException e) {
            Logger.warn("Failed to create SPV cache directory: " + e.getMessage());
        }
    }

    public long compileShaderModule(VkDevice device, int shadercStage, String shaderName, String source, Map<String, String> defines) {
        ByteBuffer spv = this.compileToSpv(shadercStage, shaderName, source, defines);
        try {
            return this.createShaderModule(device, spv, shaderName);
        } finally {
            MemoryUtil.memFree(spv);
        }
    }

    public long compileShaderModule(VkDevice device, int shadercStage, String shaderName, String source) {
        return this.compileShaderModule(device, shadercStage, shaderName, source, null);
    }

    public long createShaderModule(VkDevice device, ByteBuffer spv, String name) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack).sType$Default();
            createInfo.pCode(spv);
            LongBuffer pModule = stack.callocLong(1);
            int result = VK12.vkCreateShaderModule(device, createInfo, null, pModule);
            if (result != VK12.VK_SUCCESS) {
                throw new RuntimeException("vkCreateShaderModule failed for " + name + " with error code: " + result);
            }
            return pModule.get(0);
        }
    }

    public ByteBuffer compileToSpv(int shadercStage, String shaderName, String source, Map<String, String> defines) {
        String cacheKey = computeCacheKey(shaderName, source, defines);
        Path cachedFile = CACHE_DIR.resolve(cacheKey + ".spv");

        if (Files.exists(cachedFile)) {
            try {
                byte[] bytes = Files.readAllBytes(cachedFile);
                ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length);
                buffer.put(bytes).flip();
                return buffer;
            } catch (IOException ignored) {}
        }

        long options = Shaderc.shaderc_compile_options_initialize();
        Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
        Shaderc.shaderc_compile_options_set_target_spirv(options, Shaderc.shaderc_spirv_version_1_3);
        Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_performance);
        Shaderc.shaderc_compile_options_set_vulkan_rules_relaxed(options, true);

        if (defines != null) {
            for (var entry : defines.entrySet()) {
                if ("VULKAN".equals(entry.getKey())) {
                    continue;
                }
                Shaderc.shaderc_compile_options_add_macro_definition(options, entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
            }
        }

        ByteBuffer sourceBytes = MemoryUtil.memUTF8(source, false);
        ByteBuffer nameBytes = MemoryUtil.memUTF8(shaderName);
        ByteBuffer entryBytes = MemoryUtil.memUTF8("main");
        long result;
        try {
            result = Shaderc.shaderc_compile_into_spv(this.compiler, sourceBytes, shadercStage, nameBytes, entryBytes, options);
        } finally {
            MemoryUtil.memFree(sourceBytes);
            MemoryUtil.memFree(nameBytes);
            MemoryUtil.memFree(entryBytes);
        }
        Shaderc.shaderc_compile_options_release(options);

        if (result == 0 || Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
            String errorMsg = result != 0 ? Shaderc.shaderc_result_get_error_message(result) : "Unknown shaderc error";
            if (result != 0) {
                Shaderc.shaderc_result_release(result);
            }
            try {
                Files.writeString(Path.of("SHADER_VK_DUMP.txt"), source);
            } catch (IOException ignored) {}
            throw new RuntimeException("Failed to compile shader [" + shaderName + "] to SPIR-V:\n" + errorMsg);
        }

        ByteBuffer spvBytes = Shaderc.shaderc_result_get_bytes(result);
        ByteBuffer copy = MemoryUtil.memAlloc(spvBytes.remaining());
        copy.put(spvBytes).flip();
        Shaderc.shaderc_result_release(result);

        try {
            byte[] rawBytes = new byte[copy.remaining()];
            copy.get(0, rawBytes);
            Files.write(cachedFile, rawBytes);
        } catch (IOException ignored) {}

        return copy;
    }

    private static String computeCacheKey(String name, String src, Map<String, String> defines) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(name.getBytes());
            digest.update(src.getBytes());
            if (defines != null) {
                for (var e : defines.entrySet()) {
                    digest.update(e.getKey().getBytes());
                    digest.update(e.getValue().getBytes());
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString((name + src).hashCode());
        }
    }

    public void free() {
        if (this.compiler != 0) {
            Shaderc.shaderc_compiler_release(this.compiler);
        }
    }
}
