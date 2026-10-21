package me.cortex.voxy.client.core.rendering.section.backend.mdic;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.backend.IBufferHolder;
import me.cortex.voxy.client.core.backend.vk.VkVoxyBuffer;
import me.cortex.voxy.client.core.backend.vk.VkVoxyDevice;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;

public class VkMDICViewport extends Viewport<VkMDICViewport> {
    public final VkVoxyBuffer drawCountCallBuffer;
    public final VkVoxyBuffer drawCallBuffer;
    public final VkVoxyBuffer positionScratchBuffer;
    public final VkVoxyBuffer indirectLookupBuffer;
    public final VkVoxyBuffer visibilityBuffer;

    public VkMDICViewport(RenderProperties properties, int maxSectionCount, VkVoxyDevice device) {
        super(properties);
        this.drawCountCallBuffer = VkVoxyBuffer.createDeviceLocal(device, 1024,
                VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT);

        long drawCallSize = 5L * 4L * (MDICSectionRenderer.OPAQUE_DRAW_COUNT + MDICSectionRenderer.TRANSLUCENT_DRAW_COUNT + MDICSectionRenderer.TEMPORAL_DRAW_COUNT);
        this.drawCallBuffer = VkVoxyBuffer.createDeviceLocal(device, drawCallSize,
                VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT);

        this.positionScratchBuffer = VkVoxyBuffer.createDeviceLocal(device, 8L * 400000L,
                VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);

        this.indirectLookupBuffer = VkVoxyBuffer.createDeviceLocal(device, HierarchicalOcclusionTraverser.MAX_QUEUE_SIZE * 4L + 4L,
                VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT);

        this.visibilityBuffer = VkVoxyBuffer.createDeviceLocal(device, maxSectionCount * 4L,
                VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT);

        VkCommandBuffer cmd = device.allocateCommandBuffer();
        device.beginSingleTimeCommands(cmd);
        this.drawCountCallBuffer.zero(cmd);
        this.drawCallBuffer.zero(cmd);
        this.indirectLookupBuffer.zero(cmd);
        this.visibilityBuffer.zero(cmd);
        device.endAndSubmitSingleTimeCommands(cmd, device.graphicsQueue());
        device.freeCommandBuffer(cmd);
    }

    @Override
    protected void delete0() {
        super.delete0();
        this.visibilityBuffer.free();
        this.indirectLookupBuffer.free();
        this.drawCountCallBuffer.free();
        this.drawCallBuffer.free();
        this.positionScratchBuffer.free();
    }

    @Override
    public IBufferHolder getRenderList() {
        return this.indirectLookupBuffer;
    }
}
