package me.cortex.voxy.client.core.backend.vk;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.bounding.StreamedBoundStore;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.*;

import java.util.HashMap;
import java.util.Map;

public class VkBoundRenderer {
    private static final int UBO_SIZE = 96;

    private final VkVoxyDevice device;
    private final RenderProperties properties;
    private final int depthFormat;
    private final boolean hasStencil;
    private final VkVoxyPipeline pipeline;
    private final VkVoxyBuffer uniformBuffer;
    private VkVoxyBuffer chunkPosBuffer;
    private VkVoxyTexture depthTex;

    public VkBoundRenderer(VkVoxyDevice device, RenderProperties properties, int depthFormat, boolean hasStencil) {
        this.device = device;
        this.properties = properties;
        this.depthFormat = depthFormat;
        this.hasStencil = hasStencil;
        this.uniformBuffer = VkVoxyBuffer.createHostMapped(device, UBO_SIZE, VK12.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
        this.chunkPosBuffer = VkVoxyBuffer.createHostMapped(device, (1<<12)*8L, VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);

        Map<String, String> defines = new HashMap<>();
        if (properties.isReverseZ()) defines.put("USE_REVERSE_Z", "1");
        if (properties.isZero2One()) defines.put("USE_ZERO_ONE_DEPTH", "1");
        long vertModule = device.compiler().compileShaderModule(device.vkDevice(), Shaderc.shaderc_vertex_shader,
                "outline.vsh", ShaderLoader.parse("voxy:chunkoutline/outline.vsh"), defines);
        long fragModule = device.compiler().compileShaderModule(device.vkDevice(), Shaderc.shaderc_fragment_shader,
                "outline.fsh", ShaderLoader.parse("voxy:chunkoutline/outline.fsh"), defines);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
            bindings.get(0).binding(0).descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_VERTEX_BIT);
            bindings.get(1).binding(1).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_VERTEX_BIT);

            int furtherCompare = properties.isReverseZ() ? VK12.VK_COMPARE_OP_LESS : VK12.VK_COMPARE_OP_GREATER;
            this.pipeline = VkVoxyPipeline.createGraphics(device, vertModule, fragModule, bindings,
                    0, depthFormat,
                    false, true, true, furtherCompare, false, VK12.VK_CULL_MODE_NONE);
        } finally {
            VK12.vkDestroyShaderModule(device.vkDevice(), vertModule, null);
            VK12.vkDestroyShaderModule(device.vkDevice(), fragModule, null);
        }
    }

    public void resizeIfNeeded(int width, int height) {
        if (this.depthTex == null || this.depthTex.width() != width || this.depthTex.height() != height) {
            if (this.depthTex != null) {
                this.depthTex.free();
            }
            this.depthTex = VkVoxyTexture.createDepthAttachment(this.device, width, height, this.depthFormat);
        }
    }

    public void render(VkCommandBuffer cmd, Viewport<?> viewport, @Nullable StreamedBoundStore store) {
        this.resizeIfNeeded(viewport.width, viewport.height);
        int count = store == null ? 0 : store.getCount();
        if (count != 0) {
            this.uploadUniforms(viewport);
            if (this.chunkPosBuffer.size() < count*8L) {
                this.chunkPosBuffer.free();
                this.chunkPosBuffer = VkVoxyBuffer.createHostMapped(this.device, (long) Math.ceil(count*1.25)*8L, VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            }
            store.copyTo(this.chunkPosBuffer.mappedAddress());
            this.chunkPosBuffer.flush(0, count*8L);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            int layout = VK12.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
            this.depthTex.transitionLayout(cmd, layout);

            VkRenderingAttachmentInfo depthAtt = VkRenderingAttachmentInfo.calloc(stack).sType$Default();
            depthAtt.imageView(this.depthTex.vkImageView());
            depthAtt.imageLayout(layout);
            depthAtt.loadOp(VK12.VK_ATTACHMENT_LOAD_OP_CLEAR);
            depthAtt.storeOp(VK12.VK_ATTACHMENT_STORE_OP_STORE);
            depthAtt.clearValue().depthStencil().depth(this.properties.inverseClearDepth()).stencil(0);

            VkRenderingInfo renderingInfo = VkRenderingInfo.calloc(stack).sType$Default();
            renderingInfo.renderArea().extent().set(viewport.width, viewport.height);
            renderingInfo.layerCount(1);
            renderingInfo.pDepthAttachment(depthAtt);
            if (this.hasStencil) {
                renderingInfo.pStencilAttachment(depthAtt);
            }
            VkVoxyDevice.cmdBeginRendering(cmd, renderingInfo);

            if (count != 0) {
                this.pipeline.bind(cmd);
                VkViewport.Buffer vp = VkViewport.calloc(1, stack);
                vp.x(0).y(0).width(viewport.width).height(viewport.height).minDepth(0.0f).maxDepth(1.0f);
                VK12.vkCmdSetViewport(cmd, 0, vp);
                VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
                scissor.extent().set(viewport.width, viewport.height);
                VK12.vkCmdSetScissor(cmd, 0, scissor);
                VK12.vkCmdSetStencilReference(cmd, VK12.VK_STENCIL_FACE_FRONT_AND_BACK, 0);

                this.pipeline.pushUniformBuffer(cmd, 0, this.uniformBuffer.vkBuffer(), 0, UBO_SIZE);
                this.pipeline.pushStorageBuffer(cmd, 1, this.chunkPosBuffer.vkBuffer(), 0, count*8L);
                VK12.vkCmdBindIndexBuffer(cmd, SharedIndexBuffer.INSTANCE_BB_BYTE.vkBuffer().vkBuffer(), 0, EXTIndexTypeUint8.VK_INDEX_TYPE_UINT8_EXT);

                if (count >= 32) {
                    VK12.vkCmdDrawIndexed(cmd, 6*2*3*32, count/32, 0, 0, 0);
                }
                if (count%32 != 0) {
                    VK12.vkCmdDrawIndexed(cmd, 6*2*3*(count%32), 1, 0, 0, count/32);
                }
            }

            VkVoxyDevice.cmdEndRendering(cmd);
        }
        this.depthTex.transitionLayout(cmd, VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }

    private void uploadUniforms(Viewport<?> viewport) {
        long ptr = this.uniformBuffer.mappedAddress();
        int bx = (int) Math.floor(viewport.cameraX);
        int by = (int) Math.floor(viewport.cameraY);
        int bz = (int) Math.floor(viewport.cameraZ);
        float ix = (float) (viewport.cameraX - bx);
        float iy = (float) (viewport.cameraY - by);
        float iz = (float) (viewport.cameraZ - bz);
        new Matrix4f(viewport.MVP).translate(-ix, -iy, -iz).getToAddress(ptr);
        MemoryUtil.memPutInt(ptr + 64, bx);
        MemoryUtil.memPutInt(ptr + 68, by);
        MemoryUtil.memPutInt(ptr + 72, bz);
        MemoryUtil.memPutInt(ptr + 76, 0);
        MemoryUtil.memPutFloat(ptr + 80, ix);
        MemoryUtil.memPutFloat(ptr + 84, iy);
        MemoryUtil.memPutFloat(ptr + 88, iz);
        MemoryUtil.memPutFloat(ptr + 92, Minecraft.getInstance().options.getEffectiveRenderDistance()*16);
        this.uniformBuffer.flush(0, UBO_SIZE);
    }

    public long depthView() {
        return this.depthTex == null ? 0 : this.depthTex.getMipView(0);
    }

    public void free() {
        if (this.depthTex != null) {
            this.depthTex.free();
            this.depthTex = null;
        }
        this.pipeline.free();
        this.uniformBuffer.free();
        this.chunkPosBuffer.free();
    }
}
