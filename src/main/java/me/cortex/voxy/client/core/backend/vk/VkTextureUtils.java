package me.cortex.voxy.client.core.backend.vk;

import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

public class VkTextureUtils {

    public static int[] downloadRgba8Texture(VkVoxyDevice device, VulkanGpuTexture texture) {
        int width = texture.getWidth(0);
        int height = texture.getHeight(0);
        long size = (long) width * height * 4;

        VkVoxyBuffer stagingBuffer = new VkVoxyBuffer(device, size,
                VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                org.lwjgl.util.vma.Vma.VMA_MEMORY_USAGE_GPU_TO_CPU,
                org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);

        VkCommandBuffer cmd = device.allocateCommandBuffer();
        device.beginSingleTimeCommands(cmd);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer toSrc = VkImageMemoryBarrier.calloc(1, stack).sType$Default();
            toSrc.oldLayout(VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            toSrc.newLayout(VK12.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            toSrc.srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED);
            toSrc.dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED);
            toSrc.image(texture.vkImage());
            toSrc.subresourceRange().aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT);
            toSrc.subresourceRange().baseMipLevel(0);
            toSrc.subresourceRange().levelCount(1);
            toSrc.subresourceRange().baseArrayLayer(0);
            toSrc.subresourceRange().layerCount(1);
            toSrc.srcAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT);
            toSrc.dstAccessMask(VK12.VK_ACCESS_TRANSFER_READ_BIT);

            VK12.vkCmdPipelineBarrier(cmd, VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, toSrc);

            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.bufferOffset(0);
            region.bufferRowLength(width);
            region.bufferImageHeight(height);
            region.imageSubresource().aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT);
            region.imageSubresource().mipLevel(0);
            region.imageSubresource().baseArrayLayer(0);
            region.imageSubresource().layerCount(1);
            region.imageOffset().set(0, 0, 0);
            region.imageExtent().set(width, height, 1);

            VK12.vkCmdCopyImageToBuffer(cmd, texture.vkImage(), VK12.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, stagingBuffer.vkBuffer(), region);

            VkImageMemoryBarrier.Buffer toShader = VkImageMemoryBarrier.calloc(1, stack).sType$Default();
            toShader.oldLayout(VK12.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            toShader.newLayout(VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            toShader.srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED);
            toShader.dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED);
            toShader.image(texture.vkImage());
            toShader.subresourceRange().aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT);
            toShader.subresourceRange().baseMipLevel(0);
            toShader.subresourceRange().levelCount(1);
            toShader.subresourceRange().baseArrayLayer(0);
            toShader.subresourceRange().layerCount(1);
            toShader.srcAccessMask(VK12.VK_ACCESS_TRANSFER_READ_BIT);
            toShader.dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT);

            VK12.vkCmdPipelineBarrier(cmd, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT, VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, null, null, toShader);
        }

        device.endAndSubmitSingleTimeCommands(cmd, device.graphicsQueue());
        device.freeCommandBuffer(cmd);

        stagingBuffer.invalidate(0, size);
        int[] result = new int[width * height];
        MemoryUtil.memIntBuffer(stagingBuffer.mappedAddress(), width * height).get(result);

        stagingBuffer.free();
        return result;
    }

    public static void copyBufferToImage(VkCommandBuffer cmd, long vkBuffer, long bufferOffset, long vkImage,
                                        int mipLevel, int x, int y, int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.bufferOffset(bufferOffset);
            region.bufferRowLength(width);
            region.bufferImageHeight(height);
            region.imageSubresource().aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT);
            region.imageSubresource().mipLevel(mipLevel);
            region.imageSubresource().baseArrayLayer(0);
            region.imageSubresource().layerCount(1);
            region.imageOffset().set(x, y, 0);
            region.imageExtent().set(width, height, 1);

            VK12.vkCmdCopyBufferToImage(cmd, vkBuffer, vkImage, VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
        }
    }
}
