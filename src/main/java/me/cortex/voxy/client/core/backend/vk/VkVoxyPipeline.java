package me.cortex.voxy.client.core.backend.vk;

import me.cortex.voxy.common.util.TrackedObject;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

public class VkVoxyPipeline extends TrackedObject {
    private final VkVoxyDevice device;
    private final long pipeline;
    private final long pipelineLayout;
    private final long descriptorSetLayout;
    private final int bindPoint;
    private boolean closed = false;

    public record AttachmentBlend(boolean enabled, int srcColour, int dstColour, int srcAlpha, int dstAlpha) {
        public static final AttachmentBlend OFF = new AttachmentBlend(false, VK12.VK_BLEND_FACTOR_ONE, VK12.VK_BLEND_FACTOR_ZERO, VK12.VK_BLEND_FACTOR_ONE, VK12.VK_BLEND_FACTOR_ZERO);
        public static final AttachmentBlend ALPHA = new AttachmentBlend(true, VK12.VK_BLEND_FACTOR_SRC_ALPHA, VK12.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK12.VK_BLEND_FACTOR_ONE, VK12.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA);
        public static final AttachmentBlend NO_COLOUR = new AttachmentBlend(false, VK12.VK_BLEND_FACTOR_ZERO, VK12.VK_BLEND_FACTOR_ONE, VK12.VK_BLEND_FACTOR_ZERO, VK12.VK_BLEND_FACTOR_ONE);
    }

    private VkVoxyPipeline(VkVoxyDevice device, long pipeline, long pipelineLayout, long descriptorSetLayout, int bindPoint) {
        this.device = device;
        this.pipeline = pipeline;
        this.pipelineLayout = pipelineLayout;
        this.descriptorSetLayout = descriptorSetLayout;
        this.bindPoint = bindPoint;
    }

    public static VkVoxyPipeline createCompute(VkVoxyDevice device, long shaderModule, VkDescriptorSetLayoutBinding.Buffer bindings) {
        return createCompute(device, shaderModule, bindings, 0);
    }

    public static VkVoxyPipeline createCompute(VkVoxyDevice device, long shaderModule, VkDescriptorSetLayoutBinding.Buffer bindings, int pushConstantSize) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutCreateInfo descLayoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default();
            descLayoutInfo.flags(KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR);
            descLayoutInfo.pBindings(bindings);

            LongBuffer pDescLayout = stack.callocLong(1);
            int res = VK12.vkCreateDescriptorSetLayout(device.vkDevice(), descLayoutInfo, null, pDescLayout);
            if (res != VK12.VK_SUCCESS) {
                throw new RuntimeException("vkCreateDescriptorSetLayout failed: " + res);
            }
            long descriptorSetLayout = pDescLayout.get(0);

            VkPipelineLayoutCreateInfo pipeLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default();
            pipeLayoutInfo.pSetLayouts(stack.longs(descriptorSetLayout));
            if (pushConstantSize > 0) {
                VkPushConstantRange.Buffer pcRange = VkPushConstantRange.calloc(1, stack);
                pcRange.stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT);
                pcRange.offset(0);
                pcRange.size(pushConstantSize);
                pipeLayoutInfo.pPushConstantRanges(pcRange);
            }

            LongBuffer pPipeLayout = stack.callocLong(1);
            res = VK12.vkCreatePipelineLayout(device.vkDevice(), pipeLayoutInfo, null, pPipeLayout);
            if (res != VK12.VK_SUCCESS) {
                throw new RuntimeException("vkCreatePipelineLayout failed: " + res);
            }
            long pipelineLayout = pPipeLayout.get(0);

            VkComputePipelineCreateInfo.Buffer compInfo = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default();
            compInfo.layout(pipelineLayout);
            compInfo.stage().sType$Default();
            compInfo.stage().stage(VK12.VK_SHADER_STAGE_COMPUTE_BIT);
            compInfo.stage().module(shaderModule);
            compInfo.stage().pName(stack.UTF8("main"));

            LongBuffer pPipeline = stack.callocLong(1);
            res = VK12.vkCreateComputePipelines(device.vkDevice(), 0, compInfo, null, pPipeline);
            if (res != VK12.VK_SUCCESS) {
                throw new RuntimeException("vkCreateComputePipelines failed: " + res);
            }

            return new VkVoxyPipeline(device, pPipeline.get(0), pipelineLayout, descriptorSetLayout, VK12.VK_PIPELINE_BIND_POINT_COMPUTE);
        }
    }

    public static VkVoxyPipeline createGraphics(VkVoxyDevice device, long vertModule, long fragModule,
                                               VkDescriptorSetLayoutBinding.Buffer bindings,
                                               int colorFormat, int depthFormat,
                                               boolean blendEnable, boolean depthTestEnable, boolean depthWriteEnable,
                                               int depthCompareOp, boolean stencilTestEnable, int cullMode) {
        return createGraphics(device, vertModule, fragModule, bindings, colorFormat, depthFormat,
                blendEnable, depthTestEnable, depthWriteEnable, depthCompareOp, stencilTestEnable, cullMode,
                0, 0, VK12.VK_COMPARE_OP_ALWAYS, VK12.VK_STENCIL_OP_REPLACE);
    }

    public static VkVoxyPipeline createGraphics(VkVoxyDevice device, long vertModule, long fragModule,
                                               VkDescriptorSetLayoutBinding.Buffer bindings,
                                               int colorFormat, int depthFormat,
                                               boolean blendEnable, boolean depthTestEnable, boolean depthWriteEnable,
                                               int depthCompareOp, boolean stencilTestEnable, int cullMode,
                                               int pushConstantSize, int pushConstantStages) {
        return createGraphics(device, vertModule, fragModule, bindings, colorFormat, depthFormat,
                blendEnable, depthTestEnable, depthWriteEnable, depthCompareOp, stencilTestEnable, cullMode,
                pushConstantSize, pushConstantStages, VK12.VK_COMPARE_OP_ALWAYS, VK12.VK_STENCIL_OP_REPLACE);
    }

    public static VkVoxyPipeline createGraphics(VkVoxyDevice device, long vertModule, long fragModule,
                                               VkDescriptorSetLayoutBinding.Buffer bindings,
                                               int colorFormat, int depthFormat,
                                               boolean blendEnable, boolean depthTestEnable, boolean depthWriteEnable,
                                               int depthCompareOp, boolean stencilTestEnable, int cullMode,
                                               int pushConstantSize, int pushConstantStages,
                                               int stencilCompareOp, int stencilPassOp) {
        return createGraphics(device, vertModule, fragModule, bindings, colorFormat, depthFormat,
                blendEnable, depthTestEnable, depthWriteEnable, depthCompareOp, stencilTestEnable, cullMode,
                pushConstantSize, pushConstantStages, stencilCompareOp, stencilPassOp, false, 0.0f, 1.0f);
    }

    public static VkVoxyPipeline createGraphics(VkVoxyDevice device, long vertModule, long fragModule,
                                               VkDescriptorSetLayoutBinding.Buffer bindings,
                                               int colorFormat, int depthFormat,
                                               boolean blendEnable, boolean depthTestEnable, boolean depthWriteEnable,
                                               int depthCompareOp, boolean stencilTestEnable, int cullMode,
                                               int pushConstantSize, int pushConstantStages,
                                               int stencilCompareOp, int stencilPassOp,
                                               boolean depthBoundsTest, float minDepthBounds, float maxDepthBounds) {
        return createGraphics(device, vertModule, fragModule, bindings, colorFormat, depthFormat,
                blendEnable, depthTestEnable, depthWriteEnable, depthCompareOp, stencilTestEnable, cullMode,
                pushConstantSize, pushConstantStages, stencilCompareOp, stencilPassOp,
                depthBoundsTest, minDepthBounds, maxDepthBounds, 0.0f, 0.0f);
    }

    public static VkVoxyPipeline createGraphics(VkVoxyDevice device, long vertModule, long fragModule,
                                               VkDescriptorSetLayoutBinding.Buffer bindings,
                                               int colorFormat, int depthFormat,
                                               boolean blendEnable, boolean depthTestEnable, boolean depthWriteEnable,
                                               int depthCompareOp, boolean stencilTestEnable, int cullMode,
                                               int pushConstantSize, int pushConstantStages,
                                               int stencilCompareOp, int stencilPassOp,
                                               boolean depthBoundsTest, float minDepthBounds, float maxDepthBounds,
                                               float depthBiasConstant, float depthBiasSlope) {
        int[] colorFormats = colorFormat != 0 ? new int[]{colorFormat} : new int[0];
        AttachmentBlend[] blends = colorFormat != 0 ? new AttachmentBlend[]{blendEnable ? AttachmentBlend.ALPHA : AttachmentBlend.OFF} : new AttachmentBlend[0];
        return createGraphics(device, vertModule, fragModule, bindings, colorFormats, blends, depthFormat,
                depthTestEnable, depthWriteEnable, depthCompareOp, stencilTestEnable, cullMode,
                pushConstantSize, pushConstantStages, stencilCompareOp, stencilPassOp,
                depthBoundsTest, minDepthBounds, maxDepthBounds, depthBiasConstant, depthBiasSlope);
    }

    public static VkVoxyPipeline createGraphics(VkVoxyDevice device, long vertModule, long fragModule,
                                               VkDescriptorSetLayoutBinding.Buffer bindings,
                                               int[] colorFormats, AttachmentBlend[] blends, int depthFormat,
                                               boolean depthTestEnable, boolean depthWriteEnable,
                                               int depthCompareOp, boolean stencilTestEnable, int cullMode,
                                               int pushConstantSize, int pushConstantStages,
                                               int stencilCompareOp, int stencilPassOp,
                                               boolean depthBoundsTest, float minDepthBounds, float maxDepthBounds,
                                               float depthBiasConstant, float depthBiasSlope) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutCreateInfo descLayoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default();
            descLayoutInfo.flags(KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR);
            descLayoutInfo.pBindings(bindings);

            LongBuffer pDescLayout = stack.callocLong(1);
            int res = VK12.vkCreateDescriptorSetLayout(device.vkDevice(), descLayoutInfo, null, pDescLayout);
            if (res != VK12.VK_SUCCESS) {
                throw new RuntimeException("vkCreateDescriptorSetLayout failed: " + res);
            }
            long descriptorSetLayout = pDescLayout.get(0);

            VkPipelineLayoutCreateInfo pipeLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default();
            pipeLayoutInfo.pSetLayouts(stack.longs(descriptorSetLayout));
            if (pushConstantSize > 0) {
                VkPushConstantRange.Buffer pcRange = VkPushConstantRange.calloc(1, stack);
                pcRange.stageFlags(pushConstantStages);
                pcRange.offset(0);
                pcRange.size(pushConstantSize);
                pipeLayoutInfo.pPushConstantRanges(pcRange);
            }

            LongBuffer pPipeLayout = stack.callocLong(1);
            res = VK12.vkCreatePipelineLayout(device.vkDevice(), pipeLayoutInfo, null, pPipeLayout);
            if (res != VK12.VK_SUCCESS) {
                throw new RuntimeException("vkCreatePipelineLayout failed: " + res);
            }
            long pipelineLayout = pPipeLayout.get(0);

            VkPipelineRenderingCreateInfo renderingInfo = VkPipelineRenderingCreateInfo.calloc(stack).sType$Default();
            if (colorFormats.length != 0) {
                renderingInfo.pColorAttachmentFormats(stack.ints(colorFormats));
            }
            if (depthFormat != 0) {
                renderingInfo.depthAttachmentFormat(depthFormat);
                boolean hasStencil = depthFormat == VK12.VK_FORMAT_D24_UNORM_S8_UINT || depthFormat == VK12.VK_FORMAT_D32_SFLOAT_S8_UINT;
                if (hasStencil) {
                    renderingInfo.stencilAttachmentFormat(depthFormat);
                }
            }

            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(fragModule != 0 ? 2 : 1, stack);
            stages.get(0).sType$Default().stage(VK12.VK_SHADER_STAGE_VERTEX_BIT).module(vertModule).pName(stack.UTF8("main"));
            if (fragModule != 0) {
                stages.get(1).sType$Default().stage(VK12.VK_SHADER_STAGE_FRAGMENT_BIT).module(fragModule).pName(stack.UTF8("main"));
            }

            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();

            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default();
            inputAssembly.topology(VK12.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);

            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default();
            viewportState.viewportCount(1);
            viewportState.scissorCount(1);

            VkPipelineRasterizationStateCreateInfo rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default();
            rasterizer.polygonMode(VK12.VK_POLYGON_MODE_FILL);
            rasterizer.cullMode(cullMode);
            rasterizer.frontFace(VK12.VK_FRONT_FACE_COUNTER_CLOCKWISE);
            rasterizer.lineWidth(1.0f);
            if (depthBiasConstant != 0.0f || depthBiasSlope != 0.0f) {
                rasterizer.depthBiasEnable(true);
                rasterizer.depthBiasConstantFactor(depthBiasConstant);
                rasterizer.depthBiasSlopeFactor(depthBiasSlope);
                rasterizer.depthBiasClamp(0.0f);
            }

            VkPipelineMultisampleStateCreateInfo multisampling = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default();
            multisampling.rasterizationSamples(VK12.VK_SAMPLE_COUNT_1_BIT);

            VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default();
            depthStencil.depthTestEnable(depthTestEnable);
            depthStencil.depthWriteEnable(depthWriteEnable);
            depthStencil.depthCompareOp(depthCompareOp);
            depthStencil.depthBoundsTestEnable(depthBoundsTest);
            depthStencil.minDepthBounds(minDepthBounds);
            depthStencil.maxDepthBounds(maxDepthBounds);
            depthStencil.stencilTestEnable(stencilTestEnable);
            if (stencilTestEnable) {
                depthStencil.front().compareOp(stencilCompareOp);
                depthStencil.front().passOp(stencilPassOp);
                depthStencil.front().failOp(VK12.VK_STENCIL_OP_KEEP);
                depthStencil.front().depthFailOp(VK12.VK_STENCIL_OP_KEEP);
                depthStencil.front().compareMask(0xFF);
                depthStencil.front().writeMask(0xFF);
                depthStencil.back(depthStencil.front());
            }

            VkPipelineColorBlendStateCreateInfo colorBlending = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default();
            if (colorFormats.length != 0) {
                VkPipelineColorBlendAttachmentState.Buffer colorAttachments = VkPipelineColorBlendAttachmentState.calloc(colorFormats.length, stack);
                for (int i = 0; i < colorFormats.length; i++) {
                    AttachmentBlend blend = i < blends.length && blends[i] != null ? blends[i] : AttachmentBlend.OFF;
                    VkPipelineColorBlendAttachmentState colorAttachment = colorAttachments.get(i);
                    colorAttachment.colorWriteMask(blend == AttachmentBlend.NO_COLOUR ? 0 : VK12.VK_COLOR_COMPONENT_R_BIT | VK12.VK_COLOR_COMPONENT_G_BIT | VK12.VK_COLOR_COMPONENT_B_BIT | VK12.VK_COLOR_COMPONENT_A_BIT);
                    colorAttachment.blendEnable(blend.enabled());
                    if (blend.enabled()) {
                        colorAttachment.srcColorBlendFactor(blend.srcColour());
                        colorAttachment.dstColorBlendFactor(blend.dstColour());
                        colorAttachment.colorBlendOp(VK12.VK_BLEND_OP_ADD);
                        colorAttachment.srcAlphaBlendFactor(blend.srcAlpha());
                        colorAttachment.dstAlphaBlendFactor(blend.dstAlpha());
                        colorAttachment.alphaBlendOp(VK12.VK_BLEND_OP_ADD);
                    }
                }
                colorBlending.pAttachments(colorAttachments);
            }

            IntBuffer dynamicStates = stack.ints(
                    VK12.VK_DYNAMIC_STATE_VIEWPORT,
                    VK12.VK_DYNAMIC_STATE_SCISSOR,
                    VK12.VK_DYNAMIC_STATE_STENCIL_REFERENCE
            );
            VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default();
            dynamicState.pDynamicStates(dynamicStates);

            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack).sType$Default();
            pipelineInfo.pNext(renderingInfo);
            pipelineInfo.pStages(stages);
            pipelineInfo.pVertexInputState(vertexInput);
            pipelineInfo.pInputAssemblyState(inputAssembly);
            pipelineInfo.pViewportState(viewportState);
            pipelineInfo.pRasterizationState(rasterizer);
            pipelineInfo.pMultisampleState(multisampling);
            pipelineInfo.pDepthStencilState(depthStencil);
            pipelineInfo.pColorBlendState(colorBlending);
            pipelineInfo.pDynamicState(dynamicState);
            pipelineInfo.layout(pipelineLayout);

            LongBuffer pPipeline = stack.callocLong(1);
            res = VK12.vkCreateGraphicsPipelines(device.vkDevice(), 0, pipelineInfo, null, pPipeline);
            if (res != VK12.VK_SUCCESS) {
                throw new RuntimeException("vkCreateGraphicsPipelines failed: " + res);
            }

            return new VkVoxyPipeline(device, pPipeline.get(0), pipelineLayout, descriptorSetLayout, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS);
        }
    }

    public void bind(VkCommandBuffer cmd) {
        VK12.vkCmdBindPipeline(cmd, this.bindPoint, this.pipeline);
    }

    public void pushBuffer(VkCommandBuffer cmd, int binding, long vkBuffer, long offset, long range) {
        pushBuffer(cmd, binding, vkBuffer, offset, range, binding == 0 ? VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER : VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
    }

    public void pushUniformBuffer(VkCommandBuffer cmd, int binding, long vkBuffer, long offset, long range) {
        pushBuffer(cmd, binding, vkBuffer, offset, range, VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
    }

    public void pushStorageBuffer(VkCommandBuffer cmd, int binding, long vkBuffer, long offset, long range) {
        pushBuffer(cmd, binding, vkBuffer, offset, range, VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
    }

    public void pushBuffer(VkCommandBuffer cmd, int binding, long vkBuffer, long offset, long range, int descriptorType) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
            bufferInfo.buffer(vkBuffer);
            bufferInfo.offset(offset);
            bufferInfo.range(range);

            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack).sType$Default();
            write.dstBinding(binding);
            write.descriptorCount(1);
            write.descriptorType(descriptorType);
            write.pBufferInfo(bufferInfo);

            KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cmd, this.bindPoint, this.pipelineLayout, 0, write);
        }
    }

    public void pushImage(VkCommandBuffer cmd, int binding, long imageView, long sampler, int layout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack);
            imageInfo.imageView(imageView);
            imageInfo.sampler(sampler);
            imageInfo.imageLayout(layout);

            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack).sType$Default();
            write.dstBinding(binding);
            write.descriptorCount(1);
            write.descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            write.pImageInfo(imageInfo);

            KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cmd, this.bindPoint, this.pipelineLayout, 0, write);
        }
    }

    public void pushStorageImage(VkCommandBuffer cmd, int binding, long imageView, int layout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack);
            imageInfo.imageView(imageView);
            imageInfo.imageLayout(layout);

            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack).sType$Default();
            write.dstBinding(binding);
            write.descriptorCount(1);
            write.descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
            write.pImageInfo(imageInfo);

            KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cmd, this.bindPoint, this.pipelineLayout, 0, write);
        }
    }

    public void pushConstants(VkCommandBuffer cmd, int stageFlags, int offset, java.nio.ByteBuffer data) {
        VK12.vkCmdPushConstants(cmd, this.pipelineLayout, stageFlags, offset, data);
    }

    public void dispatch(VkCommandBuffer cmd, int x, int y, int z) {
        VK12.vkCmdDispatch(cmd, x, y, z);
    }

    public void dispatchIndirect(VkCommandBuffer cmd, long buffer, long offset) {
        VK12.vkCmdDispatchIndirect(cmd, buffer, offset);
    }

    public void draw(VkCommandBuffer cmd, int vertexCount) {
        VK12.vkCmdDraw(cmd, vertexCount, 1, 0, 0);
    }

    public void draw(VkCommandBuffer cmd, int vertexCount, int firstVertex) {
        VK12.vkCmdDraw(cmd, vertexCount, 1, firstVertex, 0);
    }

    public void drawIndexedIndirectCount(VkCommandBuffer cmd, long buffer, long offset, long countBuffer, long countBufferOffset, int maxDrawCount, int stride) {
        VK12.vkCmdDrawIndexedIndirectCount(cmd, buffer, offset, countBuffer, countBufferOffset, maxDrawCount, stride);
    }

    public long pipeline() {
        return this.pipeline;
    }

    public long pipelineLayout() {
        return this.pipelineLayout;
    }

    public long descriptorSetLayout() {
        return this.descriptorSetLayout;
    }

    @Override
    public void free() {
        super.free0();
        if (!this.closed) {
            this.closed = true;
            VK12.vkDestroyPipeline(this.device.vkDevice(), this.pipeline, null);
            VK12.vkDestroyPipelineLayout(this.device.vkDevice(), this.pipelineLayout, null);
            VK12.vkDestroyDescriptorSetLayout(this.device.vkDevice(), this.descriptorSetLayout, null);
        }
    }
}
