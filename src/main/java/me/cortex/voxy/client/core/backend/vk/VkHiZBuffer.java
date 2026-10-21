package me.cortex.voxy.client.core.backend.vk;

import me.cortex.voxy.client.core.RenderProperties;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

public class VkHiZBuffer {
    private final VkVoxyDevice device;
    private final RenderProperties properties;
    private final long hizSampler;
    private final long gatherSampler;

    private VkVoxyPipeline downsamplePipeline;
    private VkVoxyTexture texture;
    private int levels;
    private int width;
    private int height;

    private static final String DOWNSAMPLE_SHADER = """
            #version 460
            layout(local_size_x = 8, local_size_y = 8) in;

            layout(binding = 0) uniform sampler2D inDepthTex;
            layout(binding = 1, r32f) uniform writeonly image2D outDepthImage;

            layout(push_constant) uniform PushConstants {
                ivec2 outSize;
                vec2 invOutSize;
            } pc;

            void main() {
                ivec2 pos = ivec2(gl_GlobalInvocationID.xy);
                if (pos.x >= pc.outSize.x || pos.y >= pc.outSize.y) {
                    return;
                }
                vec2 uv = (vec2(pos) + vec2(0.5)) * pc.invOutSize;
                vec4 depths = textureGather(inDepthTex, uv, 0);

                #ifdef USE_REVERSE_Z
                float res = min(min(depths.x, depths.y), min(depths.z, depths.w));
                #else
                float res = max(max(depths.x, depths.y), max(depths.z, depths.w));
                #endif

                imageStore(outDepthImage, pos, vec4(res));
            }
            """;

    public VkHiZBuffer(VkVoxyDevice device, RenderProperties properties) {
        this.device = device;
        this.properties = properties;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack).sType$Default();
            samplerInfo.magFilter(VK12.VK_FILTER_NEAREST);
            samplerInfo.minFilter(VK12.VK_FILTER_NEAREST);
            samplerInfo.mipmapMode(VK12.VK_SAMPLER_MIPMAP_MODE_NEAREST);
            samplerInfo.addressModeU(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            samplerInfo.addressModeV(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            samplerInfo.addressModeW(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            samplerInfo.minLod(0);
            samplerInfo.maxLod(VK12.VK_LOD_CLAMP_NONE);

            LongBuffer pSampler = stack.callocLong(1);
            int res = VK12.vkCreateSampler(device.vkDevice(), samplerInfo, null, pSampler);
            if (res != VK12.VK_SUCCESS) {
                throw new RuntimeException("vkCreateSampler failed: " + res);
            }
            this.hizSampler = pSampler.get(0);

            samplerInfo.magFilter(VK12.VK_FILTER_NEAREST);
            samplerInfo.minFilter(VK12.VK_FILTER_NEAREST);
            res = VK12.vkCreateSampler(device.vkDevice(), samplerInfo, null, pSampler);
            if (res != VK12.VK_SUCCESS) {
                throw new RuntimeException("vkCreateSampler for gather failed: " + res);
            }
            this.gatherSampler = pSampler.get(0);
        }

        this.initPipeline();
    }

    private void initPipeline() {
        Map<String, String> defines = new HashMap<>();
        if (this.properties.isReverseZ()) {
            defines.put("USE_REVERSE_Z", "1");
        }
        if (this.properties.isZero2One()) {
            defines.put("USE_ZERO_ONE_DEPTH", "1");
        }

        long module = this.device.compiler().compileShaderModule(
                this.device.vkDevice(),
                org.lwjgl.util.shaderc.Shaderc.shaderc_compute_shader,
                "hiz_downsample",
                DOWNSAMPLE_SHADER,
                defines);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
            bindings.get(0).binding(0)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT);

            bindings.get(1).binding(1)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1)
                    .stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT);

            this.downsamplePipeline = VkVoxyPipeline.createCompute(this.device, module, bindings, 16);
        } finally {
            VK12.vkDestroyShaderModule(this.device.vkDevice(), module, null);
        }
    }

    private void alloc(int width, int height) {
        this.width = width;
        this.height = height;
        this.levels = (int) Math.ceil(Math.log(Math.max(width, height)) / Math.log(2));

        this.texture = VkVoxyTexture.createSampledMipmap(this.device, width, height, VK12.VK_FORMAT_R32_SFLOAT, this.levels);
    }

    public void buildMipChain(VkCommandBuffer cmd, long srcDepthImageView, int srcWidth, int srcHeight) {
        int targetWidth = Integer.highestOneBit(srcWidth);
        int targetHeight = Integer.highestOneBit(srcHeight);

        if (this.texture == null || this.width != targetWidth || this.height != targetHeight) {
            if (this.texture != null) {
                this.texture.free();
                this.texture = null;
            }
            this.alloc(targetWidth, targetHeight);
        }

        this.downsamplePipeline.bind(cmd);

        int cw = this.width;
        int ch = this.height;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer pushConstants = stack.calloc(16);

            this.texture.transitionMipLayout(cmd, 0, VK12.VK_IMAGE_LAYOUT_UNDEFINED, VK12.VK_IMAGE_LAYOUT_GENERAL, VK12.VK_IMAGE_ASPECT_COLOR_BIT);

            this.downsamplePipeline.pushImage(cmd, 0, srcDepthImageView, this.gatherSampler, VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            this.downsamplePipeline.pushStorageImage(cmd, 1, this.texture.getMipView(0), VK12.VK_IMAGE_LAYOUT_GENERAL);

            pushConstants.clear();
            pushConstants.putInt(cw).putInt(ch);
            pushConstants.putFloat(1.0f / cw).putFloat(1.0f / ch);
            pushConstants.flip();
            this.downsamplePipeline.pushConstants(cmd, VK12.VK_SHADER_STAGE_COMPUTE_BIT, 0, pushConstants);

            this.downsamplePipeline.dispatch(cmd, (cw + 7) / 8, (ch + 7) / 8, 1);

            this.texture.transitionMipLayout(cmd, 0, VK12.VK_IMAGE_LAYOUT_GENERAL, VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK12.VK_IMAGE_ASPECT_COLOR_BIT);

            for (int i = 1; i < this.levels; i++) {
                int nextCw = Math.max(cw / 2, 1);
                int nextCh = Math.max(ch / 2, 1);

                this.texture.transitionMipLayout(cmd, i, VK12.VK_IMAGE_LAYOUT_UNDEFINED, VK12.VK_IMAGE_LAYOUT_GENERAL, VK12.VK_IMAGE_ASPECT_COLOR_BIT);

                this.downsamplePipeline.pushImage(cmd, 0, this.texture.getMipView(i - 1), this.gatherSampler, VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                this.downsamplePipeline.pushStorageImage(cmd, 1, this.texture.getMipView(i), VK12.VK_IMAGE_LAYOUT_GENERAL);

                pushConstants.clear();
                pushConstants.putInt(nextCw).putInt(nextCh);
                pushConstants.putFloat(1.0f / nextCw).putFloat(1.0f / nextCh);
                pushConstants.flip();
                this.downsamplePipeline.pushConstants(cmd, VK12.VK_SHADER_STAGE_COMPUTE_BIT, 0, pushConstants);

                this.downsamplePipeline.dispatch(cmd, (nextCw + 7) / 8, (nextCh + 7) / 8, 1);

                this.texture.transitionMipLayout(cmd, i, VK12.VK_IMAGE_LAYOUT_GENERAL, VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK12.VK_IMAGE_ASPECT_COLOR_BIT);

                cw = nextCw;
                ch = nextCh;
            }
        }
    }

    public VkVoxyTexture getTexture() {
        return this.texture;
    }

    public long getSampler() {
        return this.hizSampler;
    }

    public int getPackedLevels() {
        return (this.width << 16) | this.height;
    }

    public void free() {
        if (this.texture != null) {
            this.texture.free();
            this.texture = null;
        }
        if (this.downsamplePipeline != null) {
            this.downsamplePipeline.free();
            this.downsamplePipeline = null;
        }
        VK12.vkDestroySampler(this.device.vkDevice(), this.hizSampler, null);
        VK12.vkDestroySampler(this.device.vkDevice(), this.gatherSampler, null);
    }
}
