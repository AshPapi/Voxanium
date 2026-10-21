package me.cortex.voxy.client.core.rendering.section.backend.mdic;

import me.cortex.voxy.client.RenderStatistics;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.backend.vk.*;
import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.model.ModelStore;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.client.core.rendering.util.LightMapHelper;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

public class VkMDICSectionRenderer extends AbstractSectionRenderer<VkMDICViewport, BasicSectionGeometryData> {
    public static final Factory<VkMDICViewport, BasicSectionGeometryData> FACTORY = AbstractSectionRenderer.Factory.create(VkMDICSectionRenderer.class);

    public static final int OPAQUE_DRAW_COUNT = MDICSectionRenderer.OPAQUE_DRAW_COUNT;
    public static final int TRANSLUCENT_DRAW_COUNT = MDICSectionRenderer.TRANSLUCENT_DRAW_COUNT;
    public static final int TEMPORAL_DRAW_COUNT = MDICSectionRenderer.TEMPORAL_DRAW_COUNT;
    public static final int TRANSLUCENT_OFFSET = OPAQUE_DRAW_COUNT;
    public static final int TEMPORAL_OFFSET = TRANSLUCENT_OFFSET + TRANSLUCENT_DRAW_COUNT;
    private static final int STATISTICS_BUFFER_BINDING = 8;
    private static final int TRANSLUCENT_SORT_SCRATCH_BASE = 1024 + TRANSLUCENT_DRAW_COUNT;

    private final VkVoxyDevice device;
    private final AbstractRenderPipeline pipeline;

    private final VkVoxyPipeline prepPipeline;
    private final VkVoxyPipeline cullPipeline;
    private final VkVoxyPipeline commandGenPipeline;
    private final VkVoxyPipeline prefixSumPipeline;
    private final VkVoxyPipeline translucentGenPipeline;
    private final VkVoxyPipeline translucentSortPipeline;
    private final VkVoxyPipeline terrainPipeline;
    private final VkVoxyPipeline translucentTerrainPipeline;
    private final VkVoxyPipeline translucentSkirtPipeline;
    private final VkVoxyPipeline translucentDepthPipeline;
    private final VkVoxyPipeline translucentSkirtDepthPipeline;
    private final VkVoxyTexture dummyDepthTex;
    private final Map<Long, VkVoxyPipeline> terrainPipelineCache = new HashMap<>();

    private final VkVoxyBuffer uniform;
    private final VkVoxyBuffer distanceCountBuffer;
    private final VkVoxyBuffer statisticsBuffer;

    private final long nearestSampler;
    private final long linearSampler;
    private final long blockSampler;

    private VkCommandBuffer activeCmd;

    public VkMDICSectionRenderer(AbstractRenderPipeline pipeline, ModelStore modelStore, BasicSectionGeometryData geometryData) {
        super(pipeline.properties, modelStore, geometryData);
        this.pipeline = pipeline;
        this.device = VkVoxyDevice.getInstance();

        this.uniform = VkVoxyBuffer.createHostMapped(this.device, 1024, VK12.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        this.distanceCountBuffer = VkVoxyBuffer.createDeviceLocal(this.device, (TRANSLUCENT_SORT_SCRATCH_BASE + TRANSLUCENT_DRAW_COUNT) * 4L,
                VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        this.statisticsBuffer = VkVoxyBuffer.createDeviceLocal(this.device, 1024,
                VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT);

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
            int res = VK12.vkCreateSampler(this.device.vkDevice(), samplerInfo, null, pSampler);
            if (res != VK12.VK_SUCCESS) {
                throw new RuntimeException("vkCreateSampler nearest failed: " + res);
            }
            this.nearestSampler = pSampler.get(0);

            samplerInfo.magFilter(VK12.VK_FILTER_LINEAR);
            samplerInfo.minFilter(VK12.VK_FILTER_LINEAR);
            res = VK12.vkCreateSampler(this.device.vkDevice(), samplerInfo, null, pSampler);
            if (res != VK12.VK_SUCCESS) {
                throw new RuntimeException("vkCreateSampler linear failed: " + res);
            }
            this.linearSampler = pSampler.get(0);

            samplerInfo.magFilter(VK12.VK_FILTER_NEAREST);
            samplerInfo.minFilter(VK12.VK_FILTER_NEAREST);
            samplerInfo.mipmapMode(VK12.VK_SAMPLER_MIPMAP_MODE_LINEAR);
            samplerInfo.addressModeU(VK12.VK_SAMPLER_ADDRESS_MODE_REPEAT);
            samplerInfo.addressModeV(VK12.VK_SAMPLER_ADDRESS_MODE_REPEAT);
            samplerInfo.addressModeW(VK12.VK_SAMPLER_ADDRESS_MODE_REPEAT);
            res = VK12.vkCreateSampler(this.device.vkDevice(), samplerInfo, null, pSampler);
            if (res != VK12.VK_SUCCESS) {
                throw new RuntimeException("vkCreateSampler blockSampler failed: " + res);
            }
            this.blockSampler = pSampler.get(0);
        }

        this.dummyDepthTex = VkVoxyTexture.createColorAttachment(this.device, 1, 1, VK12.VK_FORMAT_R32_SFLOAT);
        VkCommandBuffer initCmd = this.device.allocateCommandBuffer();
        this.device.beginSingleTimeCommands(initCmd);
        this.dummyDepthTex.transitionLayout(initCmd, VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkClearColorValue clear = VkClearColorValue.calloc(stack);
            clear.float32(0, this.properties.inverseClearDepth());
            VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
            range.aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VK12.vkCmdClearColorImage(initCmd, this.dummyDepthTex.vkImage(), VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, clear, range);
        }
        this.dummyDepthTex.transitionLayout(initCmd, VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        this.device.endAndSubmitSingleTimeCommands(initCmd, this.device.graphicsQueue());
        this.device.freeCommandBuffer(initCmd);

        {
            Map<String, String> defines = new HashMap<>();
            long module = this.device.compiler().compileShaderModule(this.device.vkDevice(), Shaderc.shaderc_compute_shader, "prep.comp",
                    ShaderLoader.parse("voxy:lod/gl46/prep.comp"), defines);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(3, stack);
                bindings.get(0).binding(0).descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT);
                bindings.get(1).binding(1).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT);
                bindings.get(2).binding(2).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT);
                this.prepPipeline = VkVoxyPipeline.createCompute(this.device, module, bindings);
            } finally {
                VK12.vkDestroyShaderModule(this.device.vkDevice(), module, null);
            }
        }

        {
            Map<String, String> defines = new HashMap<>();
            defines.put("TRANSLUCENT_WRITE_BASE", "1024");
            defines.put("TEMPORAL_OFFSET", String.valueOf(TEMPORAL_OFFSET));
            defines.put("TRANSLUCENT_DISTANCE_BUFFER_BINDING", "7");
            if (RenderStatistics.enabled) {
                defines.put("HAS_STATISTICS", "1");
                defines.put("STATISTICS_BUFFER_BINDING", "8");
            }
            long module = this.device.compiler().compileShaderModule(this.device.vkDevice(), Shaderc.shaderc_compute_shader, "cmdgen.comp",
                    ShaderLoader.parse("voxy:lod/gl46/cmdgen.comp"), defines);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                int count = RenderStatistics.enabled ? 9 : 8;
                VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(count, stack);
                bindings.get(0).binding(0).descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT);
                for (int i = 1; i < count; i++) {
                    bindings.get(i).binding(i).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT);
                }
                this.commandGenPipeline = VkVoxyPipeline.createCompute(this.device, module, bindings);
            } finally {
                VK12.vkDestroyShaderModule(this.device.vkDevice(), module, null);
            }
        }

        {
            Map<String, String> defines = new HashMap<>();
            defines.put("IO_BUFFER", "0");
            String path = "voxy:util/prefixsum/simple.comp";
            long module = this.device.compiler().compileShaderModule(this.device.vkDevice(), Shaderc.shaderc_compute_shader, "prefixsum.comp",
                    ShaderLoader.parse(path), defines);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(1, stack);
                bindings.get(0).binding(0).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT);
                this.prefixSumPipeline = VkVoxyPipeline.createCompute(this.device, module, bindings);
            } finally {
                VK12.vkDestroyShaderModule(this.device.vkDevice(), module, null);
            }
        }

        {
            Map<String, String> defines = new HashMap<>();
            defines.put("TRANSLUCENT_WRITE_BASE", "1024");
            defines.put("TRANSLUCENT_DISTANCE_BUFFER_BINDING", "5");
            defines.put("TRANSLUCENT_OFFSET", String.valueOf(TRANSLUCENT_OFFSET));
            defines.put("SORT_SCRATCH_BASE", String.valueOf(TRANSLUCENT_SORT_SCRATCH_BASE));
            this.translucentGenPipeline = this.createTranslucentPassPipeline("voxy:lod/gl46/buildtranslucents.comp", defines);
            this.translucentSortPipeline = this.createTranslucentPassPipeline("voxy:lod/gl46/sorttranslucents.comp", defines);
        }

        {
            Map<String, String> defines = new HashMap<>();
            if (this.properties.isReverseZ()) defines.put("USE_REVERSE_Z", "1");
            if (this.properties.isZero2One()) defines.put("USE_ZERO_ONE_DEPTH", "1");
            if (this.pipeline.hasTAA()) defines.put("TAA", "1");

            String vertSrc = ShaderLoader.parse("voxy:lod/gl46/cull/raster.vert");
            String fragSrc = ShaderLoader.parse("voxy:lod/gl46/cull/raster.frag");
            long vertMod = this.device.compiler().compileShaderModule(this.device.vkDevice(), Shaderc.shaderc_vertex_shader, "raster.vert", vertSrc, defines);
            long fragMod = this.device.compiler().compileShaderModule(this.device.vkDevice(), Shaderc.shaderc_fragment_shader, "raster.frag", fragSrc, defines);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(4, stack);
                bindings.get(0).binding(0).descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_VERTEX_BIT);
                bindings.get(1).binding(1).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_VERTEX_BIT);
                bindings.get(2).binding(2).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_VERTEX_BIT | VK12.VK_SHADER_STAGE_FRAGMENT_BIT);
                bindings.get(3).binding(3).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_VERTEX_BIT);

                int depthCompare = this.properties.isReverseZ() ? VK12.VK_COMPARE_OP_GREATER_OR_EQUAL : VK12.VK_COMPARE_OP_LESS_OR_EQUAL;
                int depthFormat = this.pipeline instanceof VulkanRenderPipeline vkp ? vkp.getDepthFormat() : VK12.VK_FORMAT_D24_UNORM_S8_UINT;
                this.cullPipeline = VkVoxyPipeline.createGraphics(this.device, vertMod, fragMod, bindings,
                        0, depthFormat,
                        false, true, false, depthCompare, false, VK12.VK_CULL_MODE_NONE);
            } finally {
                VK12.vkDestroyShaderModule(this.device.vkDevice(), vertMod, null);
                VK12.vkDestroyShaderModule(this.device.vkDevice(), fragMod, null);
            }
        }

        {
            int depthFormat = this.pipeline instanceof VulkanRenderPipeline vkp ? vkp.getDepthFormat() : VK12.VK_FORMAT_D24_UNORM_S8_UINT;
            this.terrainPipeline = this.getOrCreateTerrainPipeline(VK12.VK_FORMAT_R8G8B8A8_UNORM, depthFormat, false);
            this.translucentTerrainPipeline = this.getOrCreateTerrainPipeline(VK12.VK_FORMAT_R8G8B8A8_UNORM, depthFormat, true);
            this.translucentSkirtPipeline = this.getOrCreateTerrainPipeline(VK12.VK_FORMAT_R8G8B8A8_UNORM, depthFormat, true, true);
            this.translucentDepthPipeline = this.getOrCreateTerrainPipeline(VK12.VK_FORMAT_R8G8B8A8_UNORM, depthFormat, true, false, true);
            this.translucentSkirtDepthPipeline = this.getOrCreateTerrainPipeline(VK12.VK_FORMAT_R8G8B8A8_UNORM, depthFormat, true, true, true);
        }
    }

    private VkVoxyPipeline createTranslucentPassPipeline(String path, Map<String, String> defines) {
        long module = this.device.compiler().compileShaderModule(this.device.vkDevice(), Shaderc.shaderc_compute_shader,
                path.substring(path.lastIndexOf('/') + 1), ShaderLoader.parse(path), defines);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(6, stack);
            bindings.get(0).binding(0).descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT);
            for (int i = 1; i < 6; i++) {
                bindings.get(i).binding(i).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            return VkVoxyPipeline.createCompute(this.device, module, bindings);
        } finally {
            VK12.vkDestroyShaderModule(this.device.vkDevice(), module, null);
        }
    }

    public void setActiveCommandBuffer(VkCommandBuffer cmd) {
        this.activeCmd = cmd;
    }

    public void setCommandBuffer(VkCommandBuffer cmd) {
        this.activeCmd = cmd;
    }

    public VkCommandBuffer getCommandBuffer() {
        return this.activeCmd;
    }

    public VkVoxyPipeline getPrepPipeline() {
        return this.prepPipeline;
    }

    public VkVoxyPipeline getCullPipeline() {
        return this.cullPipeline;
    }

    public VkVoxyPipeline getCommandGenPipeline() {
        return this.commandGenPipeline;
    }

    public VkVoxyPipeline getPrefixSumPipeline() {
        return this.prefixSumPipeline;
    }

    public VkVoxyPipeline getTranslucentGenPipeline() {
        return this.translucentGenPipeline;
    }

    public VkVoxyPipeline getTerrainPipeline() {
        return this.terrainPipeline;
    }

    public VkVoxyPipeline getTranslucentTerrainPipeline() {
        return this.translucentTerrainPipeline;
    }

    private void uploadUniformBuffer(VkMDICViewport viewport) {
        long ptr = this.uniform.mappedAddress();
        var mat = new Matrix4f(viewport.MVP);
        mat.translate(-viewport.innerTranslation.x, -viewport.innerTranslation.y, -viewport.innerTranslation.z);
        mat.getToAddress(ptr); ptr += 4 * 4 * 4;

        viewport.section.getToAddress(ptr); ptr += 4 * 3;

        int frameId = viewport.frameId < 0 ? (viewport.frameId & 0x7fffffff) : viewport.frameId;
        MemoryUtil.memPutInt(ptr, frameId); ptr += 4;
        viewport.innerTranslation.getToAddress(ptr);
        this.uniform.flush(0, 1024);
    }

    private void bindRenderingBuffers(VkMDICViewport viewport, VkCommandBuffer cmd, VkVoxyPipeline p) {
        this.bindRenderingBuffers(viewport, cmd, p, this.uniform.vkBuffer(), 0, 1024);
    }

    private void bindRenderingBuffers(VkMDICViewport viewport, VkCommandBuffer cmd, VkVoxyPipeline p, long sceneBuffer, long sceneOffset, long sceneRange) {
        p.pushBuffer(cmd, 0, sceneBuffer, sceneOffset, sceneRange);
        p.pushBuffer(cmd, 1, this.geometryManager.getGeometryBuffer().rawHandle(), 0, this.geometryManager.getGeometryBuffer().size());
        p.pushBuffer(cmd, 2, this.geometryManager.getMetadataBuffer().rawHandle(), 0, this.geometryManager.getMetadataBuffer().size());
        p.pushBuffer(cmd, 3, this.modelStore.modelBuffer != null ? this.modelStore.modelBuffer.rawHandle() : 0, 0, (long) ModelStore.MODEL_SIZE * (1<<16));
        p.pushBuffer(cmd, 4, this.modelStore.modelColourBuffer != null ? this.modelStore.modelColourBuffer.rawHandle() : 0, 0, 4L * (1<<16));
        p.pushBuffer(cmd, 5, viewport.positionScratchBuffer.vkBuffer(), 0, viewport.positionScratchBuffer.size());

        if (this.modelStore.vkTextures != null) {
            p.pushImage(cmd, 6, this.modelStore.vkTextures.vkImageView(), this.blockSampler, VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }

        var lightmapView = LightMapHelper.getLightmapTextureViewVk();
        if (lightmapView != null) {
            p.pushImage(cmd, 7, lightmapView.vkImageView(), this.linearSampler, VK12.VK_IMAGE_LAYOUT_GENERAL);
        }

        long boundView = this.pipeline instanceof VulkanRenderPipeline vkp ? vkp.getBoundDepthView() : 0;
        p.pushImage(cmd, 8, boundView != 0 ? boundView : this.dummyDepthTex.vkImageView(), this.nearestSampler, VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }

    public boolean hasGeometry() {
        return this.geometryManager.getSectionCount() != 0;
    }

    public void writeSceneUniform(VkMDICViewport viewport, long ptr) {
        var mat = new Matrix4f(viewport.MVP);
        mat.translate(-viewport.innerTranslation.x, -viewport.innerTranslation.y, -viewport.innerTranslation.z);
        mat.getToAddress(ptr); ptr += 4 * 4 * 4;
        viewport.section.getToAddress(ptr); ptr += 4 * 3;
        int frameId = viewport.frameId < 0 ? (viewport.frameId & 0x7fffffff) : viewport.frameId;
        MemoryUtil.memPutInt(ptr, frameId); ptr += 4;
        viewport.innerTranslation.getToAddress(ptr);
    }

    public String patchedVertexSource() {
        return ShaderLoader.parse("voxy:lod/gl46/quads3.vert")
                .replace("#define LIGHTING_SAMPLER_BINDING 1", "#define LIGHTING_SAMPLER_BINDING 7");
    }

    public String patchedFragmentSource() {
        return ShaderLoader.parse("voxy:lod/gl46/quads.frag")
                .replace("layout(binding = 0) uniform sampler2D blockModelAtlas;", "layout(binding = 6) uniform sampler2D blockModelAtlas;")
                .replace("layout(binding = 2) uniform sampler2D depthTex;", "layout(binding = 8) uniform sampler2D depthTex;");
    }

    public Map<String, String> patchedDefines(boolean translucent, boolean skirtPass) {
        Map<String, String> defines = new HashMap<>();
        if (this.properties.isReverseZ()) defines.put("USE_REVERSE_Z", "1");
        if (this.properties.isZero2One()) defines.put("USE_ZERO_ONE_DEPTH", "1");
        net.minecraft.client.multiplayer.ClientLevel cl = net.minecraft.client.Minecraft.getInstance().level;
        if (cl != null) {
            var cardinalLight = cl.cardinalLighting();
            defines.put("NO_SHADE_FACE_TINT", String.valueOf(cardinalLight.up()));
            defines.put("UP_FACE_TINT", String.valueOf(cardinalLight.up()));
            defines.put("DOWN_FACE_TINT", String.valueOf(cardinalLight.down()));
            defines.put("Z_AXIS_FACE_TINT", String.valueOf(cardinalLight.north()));
            defines.put("X_AXIS_FACE_TINT", String.valueOf(cardinalLight.east()));
        } else {
            defines.put("NO_SHADE_FACE_TINT", "1.0f");
            defines.put("UP_FACE_TINT", "1.0f");
            defines.put("DOWN_FACE_TINT", "0.5f");
            defines.put("Z_AXIS_FACE_TINT", "0.8f");
            defines.put("X_AXIS_FACE_TINT", "0.6f");
        }
        defines.put("PATCHED_SHADER", "1");
        defines.put("PATCHED_GEOMETRIC_FACE", "1");
        if (translucent) {
            defines.put("TRANSLUCENT", "1");
            if (skirtPass) {
                defines.put("TRANSLUCENT_SKIRT_PASS", "1");
            }
        }
        return defines;
    }

    public VkVoxyPipeline createPatchedPipeline(String name, String vertSrc, String fragSrc, Map<String, String> defines,
                                                int[] colorFormats, VkVoxyPipeline.AttachmentBlend[] blends, int depthFormat,
                                                boolean skirtPass, int uniformBinding, int firstSamplerBinding, int samplerCount) {
        long vertMod = this.device.compiler().compileShaderModule(this.device.vkDevice(), Shaderc.shaderc_vertex_shader, name + ".vert", vertSrc, defines);
        long fragMod;
        try {
            fragMod = this.device.compiler().compileShaderModule(this.device.vkDevice(), Shaderc.shaderc_fragment_shader, name + ".frag", fragSrc, defines);
        } catch (RuntimeException e) {
            VK12.vkDestroyShaderModule(this.device.vkDevice(), vertMod, null);
            throw e;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            int count = firstSamplerBinding + samplerCount;
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(count, stack);
            bindings.get(0).binding(0).descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_VERTEX_BIT | VK12.VK_SHADER_STAGE_FRAGMENT_BIT);
            bindings.get(1).binding(1).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_VERTEX_BIT);
            bindings.get(2).binding(2).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_VERTEX_BIT);
            bindings.get(3).binding(3).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_VERTEX_BIT | VK12.VK_SHADER_STAGE_FRAGMENT_BIT);
            bindings.get(4).binding(4).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_VERTEX_BIT);
            bindings.get(5).binding(5).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_VERTEX_BIT);
            bindings.get(6).binding(6).descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_FRAGMENT_BIT);
            bindings.get(7).binding(7).descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_VERTEX_BIT);
            bindings.get(8).binding(8).descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_FRAGMENT_BIT);
            for (int i = 9; i < count; i++) {
                if (i == uniformBinding) {
                    bindings.get(i).binding(i).descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_VERTEX_BIT | VK12.VK_SHADER_STAGE_FRAGMENT_BIT);
                } else {
                    bindings.get(i).binding(i).descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_FRAGMENT_BIT);
                }
            }

            int depthCompare = this.properties.isReverseZ() ? VK12.VK_COMPARE_OP_GREATER_OR_EQUAL : VK12.VK_COMPARE_OP_LESS_OR_EQUAL;
            float biasSign = this.properties.isReverseZ() ? -1.0f : 1.0f;
            float biasConstant = skirtPass ? 4.0f * biasSign : 0.0f;
            float biasSlope = skirtPass ? 4.0f * biasSign : 0.0f;

            return VkVoxyPipeline.createGraphics(this.device, vertMod, fragMod, bindings,
                    colorFormats, blends, depthFormat,
                    true, true, depthCompare, false, VK12.VK_CULL_MODE_NONE,
                    0, 0, VK12.VK_COMPARE_OP_ALWAYS, VK12.VK_STENCIL_OP_REPLACE,
                    false, 0.0f, 1.0f, biasConstant, biasSlope);
        } finally {
            VK12.vkDestroyShaderModule(this.device.vkDevice(), vertMod, null);
            VK12.vkDestroyShaderModule(this.device.vkDevice(), fragMod, null);
        }
    }

    public void bindPatched(VkMDICViewport viewport, VkCommandBuffer cmd, VkVoxyPipeline pipe, long sceneBuffer, long sceneOffset, long sceneRange) {
        pipe.bind(cmd);
        this.bindRenderingBuffers(viewport, cmd, pipe, sceneBuffer, sceneOffset, sceneRange);
        var sharedIdx = SharedIndexBuffer.INSTANCE.vkBuffer();
        if (sharedIdx != null) {
            VK12.vkCmdBindIndexBuffer(cmd, sharedIdx.vkBuffer(), 0, VK12.VK_INDEX_TYPE_UINT16);
        }
    }

    public void drawPatchedOpaque(VkMDICViewport viewport, VkCommandBuffer cmd, VkVoxyPipeline pipe) {
        if (this.geometryManager.getSectionCount() == 0) return;
        pipe.drawIndexedIndirectCount(cmd, viewport.drawCallBuffer.vkBuffer(), 0,
                viewport.drawCountCallBuffer.vkBuffer(), 4 * 3, Math.min((int)(this.geometryManager.getSectionCount() * 4.4 + 128), OPAQUE_DRAW_COUNT), 20);
    }

    public void drawPatchedTranslucent(VkMDICViewport viewport, VkCommandBuffer cmd, VkVoxyPipeline pipe) {
        if (this.geometryManager.getSectionCount() == 0) return;
        pipe.drawIndexedIndirectCount(cmd, viewport.drawCallBuffer.vkBuffer(), TRANSLUCENT_OFFSET * 5L * 4L,
                viewport.drawCountCallBuffer.vkBuffer(), 4 * 4, TRANSLUCENT_DRAW_COUNT, 20);
    }

    public VkVoxyPipeline getOrCreateTerrainPipeline(int colorFormat, int depthFormat, boolean translucent) {
        return this.getOrCreateTerrainPipeline(colorFormat, depthFormat, translucent, false);
    }

    public VkVoxyPipeline getOrCreateTerrainPipeline(int colorFormat, int depthFormat, boolean translucent, boolean skirtPass) {
        return this.getOrCreateTerrainPipeline(colorFormat, depthFormat, translucent, skirtPass, false);
    }

    public VkVoxyPipeline getOrCreateTerrainPipeline(int colorFormat, int depthFormat, boolean translucent, boolean skirtPass, boolean depthOnly) {
        long key = (((long) colorFormat) << 33) | (depthOnly ? 1L << 32 : 0L) | (((long) depthFormat) << 1) | (translucent ? 1L : 0L) | (skirtPass ? Long.MIN_VALUE : 0L);
        synchronized (this.terrainPipelineCache) {
            return this.terrainPipelineCache.computeIfAbsent(key, k -> createTerrainPipelineForFormats(colorFormat, depthFormat, translucent, skirtPass, depthOnly));
        }
    }

    private VkVoxyPipeline createTerrainPipelineForFormats(int colorFormat, int depthFormat, boolean translucent, boolean skirtPass, boolean depthOnly) {
        Map<String, String> defines = new HashMap<>();
        if (this.properties.isReverseZ()) defines.put("USE_REVERSE_Z", "1");
        if (this.properties.isZero2One()) defines.put("USE_ZERO_ONE_DEPTH", "1");

        net.minecraft.client.multiplayer.ClientLevel cl = net.minecraft.client.Minecraft.getInstance().level;
        if (cl != null) {
            var cardinalLight = cl.cardinalLighting();
            defines.put("NO_SHADE_FACE_TINT", String.valueOf(cardinalLight.up()));
            defines.put("UP_FACE_TINT", String.valueOf(cardinalLight.up()));
            defines.put("DOWN_FACE_TINT", String.valueOf(cardinalLight.down()));
            defines.put("Z_AXIS_FACE_TINT", String.valueOf(cardinalLight.north()));
            defines.put("X_AXIS_FACE_TINT", String.valueOf(cardinalLight.east()));
        } else {
            defines.put("NO_SHADE_FACE_TINT", "1.0f");
            defines.put("UP_FACE_TINT", "1.0f");
            defines.put("DOWN_FACE_TINT", "0.5f");
            defines.put("Z_AXIS_FACE_TINT", "0.8f");
            defines.put("X_AXIS_FACE_TINT", "0.6f");
        }

        String vertSrc = ShaderLoader.parse("voxy:lod/gl46/quads3.vert");
        String taa = this.pipeline.taaFunction("taaShift");
        if (taa != null) {
            vertSrc += "\n" + taa;
            defines.put("TAA_PATCH", "1");
        }
        String fragSrc = ShaderLoader.parse("voxy:lod/gl46/quads.frag");

        fragSrc = fragSrc.replace("layout(binding = 0) uniform sampler2D blockModelAtlas;", "layout(binding = 6) uniform sampler2D blockModelAtlas;")
                         .replace("layout(binding = 2) uniform sampler2D depthTex;", "layout(binding = 8) uniform sampler2D depthTex;");
        vertSrc = vertSrc.replace("#define LIGHTING_SAMPLER_BINDING 1", "#define LIGHTING_SAMPLER_BINDING 7");

        if (translucent) {
            defines.put("TRANSLUCENT", "1");
            if (skirtPass) {
                defines.put("TRANSLUCENT_SKIRT_PASS", "1");
            }
        }

        long vertMod = this.device.compiler().compileShaderModule(this.device.vkDevice(), Shaderc.shaderc_vertex_shader, "quads3.vert", vertSrc, defines);
        long fragMod = this.device.compiler().compileShaderModule(this.device.vkDevice(), Shaderc.shaderc_fragment_shader, translucent ? "quads_trans.frag" : "quads.frag", fragSrc, defines);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(9, stack);
            bindings.get(0).binding(0).descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_VERTEX_BIT | VK12.VK_SHADER_STAGE_FRAGMENT_BIT);
            bindings.get(1).binding(1).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_VERTEX_BIT);
            bindings.get(2).binding(2).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_VERTEX_BIT);
            bindings.get(3).binding(3).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_VERTEX_BIT | VK12.VK_SHADER_STAGE_FRAGMENT_BIT);
            bindings.get(4).binding(4).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_VERTEX_BIT);
            bindings.get(5).binding(5).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_VERTEX_BIT);
            bindings.get(6).binding(6).descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_FRAGMENT_BIT);
            bindings.get(7).binding(7).descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_VERTEX_BIT);
            bindings.get(8).binding(8).descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_FRAGMENT_BIT);

            int depthCompare = this.properties.isReverseZ() ? VK12.VK_COMPARE_OP_GREATER_OR_EQUAL : VK12.VK_COMPARE_OP_LESS_OR_EQUAL;
            float biasSign = this.properties.isReverseZ() ? -1.0f : 1.0f;
            float biasConstant = skirtPass ? 4.0f * biasSign : 0.0f;
            float biasSlope = skirtPass ? 4.0f * biasSign : 0.0f;

            if (translucent) {
                return VkVoxyPipeline.createGraphics(this.device, vertMod, fragMod, bindings,
                        new int[]{colorFormat}, new VkVoxyPipeline.AttachmentBlend[]{depthOnly ? VkVoxyPipeline.AttachmentBlend.NO_COLOUR : VkVoxyPipeline.AttachmentBlend.ALPHA}, depthFormat,
                        true, depthOnly, depthCompare, false, VK12.VK_CULL_MODE_NONE,
                        0, 0, VK12.VK_COMPARE_OP_ALWAYS, VK12.VK_STENCIL_OP_REPLACE,
                        false, 0.0f, 1.0f, biasConstant, biasSlope);
            }
            return VkVoxyPipeline.createGraphics(this.device, vertMod, fragMod, bindings,
                    colorFormat, depthFormat,
                    false, true, true, depthCompare, false, VK12.VK_CULL_MODE_NONE,
                    0, 0, VK12.VK_COMPARE_OP_ALWAYS, VK12.VK_STENCIL_OP_REPLACE,
                    false, 0.0f, 1.0f, biasConstant, biasSlope);
        } finally {
            VK12.vkDestroyShaderModule(this.device.vkDevice(), vertMod, null);
            VK12.vkDestroyShaderModule(this.device.vkDevice(), fragMod, null);
        }
    }

    private void renderTerrain(VkMDICViewport viewport, VkCommandBuffer cmd, long indirectOffset, long drawCountOffset, int maxDrawCount, boolean translucent) {
        this.renderTerrain(viewport, cmd, indirectOffset, drawCountOffset, maxDrawCount, translucent ? this.translucentTerrainPipeline : this.terrainPipeline);
    }

    private void renderTerrain(VkMDICViewport viewport, VkCommandBuffer cmd, long indirectOffset, long drawCountOffset, int maxDrawCount, VkVoxyPipeline pipe) {
        if (this.geometryManager.getSectionCount() == 0) return;

        pipe.bind(cmd);
        this.bindRenderingBuffers(viewport, cmd, pipe);

        var sharedIdx = SharedIndexBuffer.INSTANCE.vkBuffer();
        if (sharedIdx != null) {
            VK12.vkCmdBindIndexBuffer(cmd, sharedIdx.vkBuffer(), 0, VK12.VK_INDEX_TYPE_UINT16);
        }

        pipe.drawIndexedIndirectCount(cmd, viewport.drawCallBuffer.vkBuffer(), indirectOffset,
                viewport.drawCountCallBuffer.vkBuffer(), drawCountOffset, maxDrawCount, 20);
    }

    public void renderOpaque(VkMDICViewport viewport, VkCommandBuffer cmd) {
        if (this.geometryManager.getSectionCount() == 0) return;
        this.uploadUniformBuffer(viewport);
        this.renderTerrain(viewport, cmd, 0, 4 * 3, Math.min((int)(this.geometryManager.getSectionCount() * 4.4 + 128), OPAQUE_DRAW_COUNT), false);
    }

    @Override
    public void renderOpaque(VkMDICViewport viewport) {
        if (this.activeCmd != null) {
            this.renderOpaque(viewport, this.activeCmd);
        }
    }

    public void renderTranslucent(VkMDICViewport viewport, VkCommandBuffer cmd) {
        if (this.geometryManager.getSectionCount() == 0) return;
        this.renderTerrain(viewport, cmd, TRANSLUCENT_OFFSET * 5L * 4L, 4 * 4, TRANSLUCENT_DRAW_COUNT, this.translucentDepthPipeline);
        this.renderTerrain(viewport, cmd, TRANSLUCENT_OFFSET * 5L * 4L, 4 * 4, TRANSLUCENT_DRAW_COUNT, this.translucentSkirtDepthPipeline);
        this.renderTerrain(viewport, cmd, TRANSLUCENT_OFFSET * 5L * 4L, 4 * 4, TRANSLUCENT_DRAW_COUNT, true);
        this.renderTerrain(viewport, cmd, TRANSLUCENT_OFFSET * 5L * 4L, 4 * 4, TRANSLUCENT_DRAW_COUNT, this.translucentSkirtPipeline);
    }

    @Override
    public void renderTranslucent(VkMDICViewport viewport) {
        if (this.activeCmd != null) {
            this.renderTranslucent(viewport, this.activeCmd);
        }
    }

    public void renderTemporal(VkMDICViewport viewport, VkCommandBuffer cmd) {
        if (this.geometryManager.getSectionCount() == 0) return;
        this.renderTerrain(viewport, cmd, TEMPORAL_OFFSET * 5L * 4L, 4 * 5, Math.min(this.geometryManager.getSectionCount(), TEMPORAL_DRAW_COUNT), false);
    }

    @Override
    public void renderTemporal(VkMDICViewport viewport) {
        if (this.activeCmd != null) {
            this.renderTemporal(viewport, this.activeCmd);
        }
    }

    public void buildDrawCalls(VkMDICViewport viewport, VkCommandBuffer cmd) {
        if (this.geometryManager.getSectionCount() == 0) return;
        this.uploadUniformBuffer(viewport);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack).sType$Default();
            barrier.srcAccessMask(VK12.VK_ACCESS_MEMORY_WRITE_BIT | VK12.VK_ACCESS_MEMORY_READ_BIT);
            barrier.dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT | VK12.VK_ACCESS_INDIRECT_COMMAND_READ_BIT);
            VK12.vkCmdPipelineBarrier(cmd, VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, barrier, null, null);
        }

        this.prepPipeline.bind(cmd);
        this.prepPipeline.pushBuffer(cmd, 0, this.uniform.vkBuffer(), 0, 1024);
        this.prepPipeline.pushBuffer(cmd, 1, viewport.drawCountCallBuffer.vkBuffer(), 0, 1024);
        this.prepPipeline.pushBuffer(cmd, 2, viewport.getRenderList().rawHandle(), 0, viewport.getRenderList().size());
        this.prepPipeline.dispatch(cmd, 1, 1, 1);

        this.insertComputeStorageBarrier(cmd);

        if (this.pipeline instanceof VulkanRenderPipeline vkPipe && vkPipe.getDepthTex() != null) {
            VkVoxyTexture depthTex = vkPipe.getDepthTex();
            depthTex.transitionLayout(cmd, VK12.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkRenderingAttachmentInfo.Buffer depthAttachment = VkRenderingAttachmentInfo.calloc(1, stack).sType$Default();
                depthAttachment.imageView(depthTex.vkImageView());
                depthAttachment.imageLayout(VK12.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
                depthAttachment.loadOp(VK12.VK_ATTACHMENT_LOAD_OP_LOAD);
                depthAttachment.storeOp(VK12.VK_ATTACHMENT_STORE_OP_STORE);

                VkRenderingInfo renderingInfo = VkRenderingInfo.calloc(stack).sType$Default();
                renderingInfo.renderArea().extent().set(viewport.width, viewport.height);
                renderingInfo.layerCount(1);
                renderingInfo.pDepthAttachment(depthAttachment.get(0));
                if (vkPipe.hasStencil()) {
                    renderingInfo.pStencilAttachment(depthAttachment.get(0));
                }

                VkVoxyDevice.cmdBeginRendering(cmd, renderingInfo);

                this.cullPipeline.bind(cmd);

                VkViewport.Buffer vp = VkViewport.calloc(1, stack);
                vp.x(0).y(0).width(viewport.width).height(viewport.height).minDepth(0.0f).maxDepth(1.0f);
                VK12.vkCmdSetViewport(cmd, 0, vp);

                VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
                scissor.offset().set(0, 0);
                scissor.extent().set(viewport.width, viewport.height);
                VK12.vkCmdSetScissor(cmd, 0, scissor);

                this.cullPipeline.pushBuffer(cmd, 0, this.uniform.vkBuffer(), 0, 1024);
                this.cullPipeline.pushBuffer(cmd, 1, this.geometryManager.getMetadataBuffer().rawHandle(), 0, this.geometryManager.getMetadataBuffer().size());
                this.cullPipeline.pushBuffer(cmd, 2, viewport.visibilityBuffer.vkBuffer(), 0, viewport.visibilityBuffer.size());
                this.cullPipeline.pushBuffer(cmd, 3, viewport.indirectLookupBuffer.vkBuffer(), 0, viewport.indirectLookupBuffer.size());

                var sharedIdx = SharedIndexBuffer.INSTANCE.vkBuffer();
                if (sharedIdx != null) {
                    VK12.vkCmdBindIndexBuffer(cmd, sharedIdx.vkBuffer(), 0, org.lwjgl.vulkan.EXTIndexTypeUint8.VK_INDEX_TYPE_UINT8_EXT);
                }

                VK12.vkCmdDrawIndexedIndirect(cmd, viewport.drawCountCallBuffer.vkBuffer(), 6 * 4, 1, 20);

                VkVoxyDevice.cmdEndRendering(cmd);
            }
            this.insertComputeStorageBarrier(cmd);
        }

        this.distanceCountBuffer.zero(cmd);
        viewport.drawCallBuffer.zeroRange(cmd, TRANSLUCENT_OFFSET * 5L * 4L, TRANSLUCENT_DRAW_COUNT * 5L * 4L);
        this.insertComputeStorageBarrier(cmd);
        this.commandGenPipeline.bind(cmd);
        this.commandGenPipeline.pushBuffer(cmd, 0, this.uniform.vkBuffer(), 0, 1024);
        this.commandGenPipeline.pushBuffer(cmd, 1, viewport.drawCallBuffer.vkBuffer(), 0, viewport.drawCallBuffer.size());
        this.commandGenPipeline.pushBuffer(cmd, 2, viewport.drawCountCallBuffer.vkBuffer(), 0, 1024);
        this.commandGenPipeline.pushBuffer(cmd, 3, this.geometryManager.getMetadataBuffer().rawHandle(), 0, this.geometryManager.getMetadataBuffer().size());
        this.commandGenPipeline.pushBuffer(cmd, 4, viewport.visibilityBuffer.vkBuffer(), 0, viewport.visibilityBuffer.size());
        this.commandGenPipeline.pushBuffer(cmd, 5, viewport.indirectLookupBuffer.vkBuffer(), 0, viewport.indirectLookupBuffer.size());
        this.commandGenPipeline.pushBuffer(cmd, 6, viewport.positionScratchBuffer.vkBuffer(), 0, viewport.positionScratchBuffer.size());
        this.commandGenPipeline.pushBuffer(cmd, 7, this.distanceCountBuffer.vkBuffer(), 0, this.distanceCountBuffer.size());
        if (RenderStatistics.enabled) {
            this.commandGenPipeline.pushBuffer(cmd, 8, this.statisticsBuffer.vkBuffer(), 0, 1024);
        }

        this.commandGenPipeline.dispatchIndirect(cmd, viewport.drawCountCallBuffer.vkBuffer(), 0);

        this.insertComputeStorageBarrier(cmd);

        this.prefixSumPipeline.bind(cmd);
        this.prefixSumPipeline.pushStorageBuffer(cmd, 0, this.distanceCountBuffer.vkBuffer(), 0, this.distanceCountBuffer.size());
        this.prefixSumPipeline.dispatch(cmd, 1, 1, 1);

        this.insertComputeStorageBarrier(cmd);

        this.translucentGenPipeline.bind(cmd);
        this.translucentGenPipeline.pushBuffer(cmd, 0, this.uniform.vkBuffer(), 0, 1024);
        this.translucentGenPipeline.pushBuffer(cmd, 1, viewport.drawCallBuffer.vkBuffer(), 0, viewport.drawCallBuffer.size());
        this.translucentGenPipeline.pushBuffer(cmd, 2, viewport.drawCountCallBuffer.vkBuffer(), 0, 1024);
        this.translucentGenPipeline.pushBuffer(cmd, 3, this.geometryManager.getMetadataBuffer().rawHandle(), 0, this.geometryManager.getMetadataBuffer().size());
        this.translucentGenPipeline.pushBuffer(cmd, 4, viewport.indirectLookupBuffer.vkBuffer(), 0, viewport.indirectLookupBuffer.size());
        this.translucentGenPipeline.pushBuffer(cmd, 5, this.distanceCountBuffer.vkBuffer(), 0, this.distanceCountBuffer.size());

        this.translucentGenPipeline.dispatchIndirect(cmd, viewport.drawCountCallBuffer.vkBuffer(), 0);

        this.insertComputeStorageBarrier(cmd);

        this.translucentSortPipeline.bind(cmd);
        this.translucentSortPipeline.pushBuffer(cmd, 0, this.uniform.vkBuffer(), 0, 1024);
        this.translucentSortPipeline.pushBuffer(cmd, 1, viewport.drawCallBuffer.vkBuffer(), 0, viewport.drawCallBuffer.size());
        this.translucentSortPipeline.pushBuffer(cmd, 2, viewport.drawCountCallBuffer.vkBuffer(), 0, 1024);
        this.translucentSortPipeline.pushBuffer(cmd, 3, this.geometryManager.getMetadataBuffer().rawHandle(), 0, this.geometryManager.getMetadataBuffer().size());
        this.translucentSortPipeline.pushBuffer(cmd, 4, viewport.indirectLookupBuffer.vkBuffer(), 0, viewport.indirectLookupBuffer.size());
        this.translucentSortPipeline.pushBuffer(cmd, 5, this.distanceCountBuffer.vkBuffer(), 0, this.distanceCountBuffer.size());

        this.translucentSortPipeline.dispatchIndirect(cmd, viewport.drawCountCallBuffer.vkBuffer(), 0);

        this.insertComputeStorageBarrier(cmd);
    }

    @Override
    public void buildDrawCalls(VkMDICViewport viewport) {
        if (this.activeCmd != null) {
            this.buildDrawCalls(viewport, this.activeCmd);
        }
    }

    private void insertComputeStorageBarrier(VkCommandBuffer cmd) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack).sType$Default();
            barrier.srcAccessMask(VK12.VK_ACCESS_SHADER_WRITE_BIT | VK12.VK_ACCESS_TRANSFER_WRITE_BIT);
            barrier.dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT | VK12.VK_ACCESS_INDIRECT_COMMAND_READ_BIT);
            VK12.vkCmdPipelineBarrier(cmd,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK12.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK12.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT
                            | VK12.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK12.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    0, barrier, null, null);
        }
    }

    @Override
    public VkMDICViewport createViewport() {
        return new VkMDICViewport(this.properties, this.geometryManager.getMaxSectionCount(), this.device);
    }

    @Override
    public void free() {
        this.uniform.free();
        this.distanceCountBuffer.free();
        this.statisticsBuffer.free();

        VK12.vkDestroySampler(this.device.vkDevice(), this.nearestSampler, null);
        VK12.vkDestroySampler(this.device.vkDevice(), this.linearSampler, null);
        VK12.vkDestroySampler(this.device.vkDevice(), this.blockSampler, null);

        this.prepPipeline.free();
        this.cullPipeline.free();
        this.commandGenPipeline.free();
        this.prefixSumPipeline.free();
        this.translucentGenPipeline.free();
        this.translucentSortPipeline.free();
        synchronized (this.terrainPipelineCache) {
            for (VkVoxyPipeline p : this.terrainPipelineCache.values()) {
                p.free();
            }
            this.terrainPipelineCache.clear();
        }
        if (this.dummyDepthTex != null) {
            this.dummyDepthTex.free();
        }
    }
}
