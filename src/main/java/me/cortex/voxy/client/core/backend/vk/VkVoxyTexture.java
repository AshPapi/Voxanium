package me.cortex.voxy.client.core.backend.vk;

import me.cortex.voxy.common.util.TrackedObject;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

public class VkVoxyTexture extends TrackedObject {
    private final VkVoxyDevice device;
    private final long vkImage;
    private final long vmaAllocation;
    private final long vkImageView;
    private final int width;
    private final int height;
    private final int format;
    private final int mipLevels;
    private int currentLayout = VK12.VK_IMAGE_LAYOUT_UNDEFINED;
    private long[] mipViews;
    private boolean closed = false;

    public VkVoxyTexture(VkVoxyDevice device, int width, int height, int format, int mipLevels, int vkUsage, int aspectMask) {
        this.device = device;
        this.width = width;
        this.height = height;
        this.format = format;
        this.mipLevels = mipLevels;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack).sType$Default();
            imageInfo.imageType(VK12.VK_IMAGE_TYPE_2D);
            imageInfo.extent().set(width, height, 1);
            imageInfo.mipLevels(mipLevels);
            imageInfo.arrayLayers(1);
            imageInfo.format(format);
            imageInfo.tiling(VK12.VK_IMAGE_TILING_OPTIMAL);
            imageInfo.initialLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED);
            imageInfo.usage(vkUsage);
            imageInfo.sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE);
            imageInfo.samples(VK12.VK_SAMPLE_COUNT_1_BIT);

            VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack);
            allocInfo.usage(Vma.VMA_MEMORY_USAGE_GPU_ONLY);

            LongBuffer pImage = stack.callocLong(1);
            PointerBuffer pAlloc = stack.callocPointer(1);
            int res = Vma.vmaCreateImage(device.vma(), imageInfo, allocInfo, pImage, pAlloc, null);
            if (res != VK12.VK_SUCCESS) {
                throw new RuntimeException("vmaCreateImage failed: " + res);
            }
            this.vkImage = pImage.get(0);
            this.vmaAllocation = pAlloc.get(0);

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default();
            viewInfo.image(this.vkImage);
            viewInfo.viewType(VK12.VK_IMAGE_VIEW_TYPE_2D);
            viewInfo.format(format);
            viewInfo.subresourceRange().aspectMask(aspectMask);
            viewInfo.subresourceRange().baseMipLevel(0);
            viewInfo.subresourceRange().levelCount(mipLevels);
            viewInfo.subresourceRange().baseArrayLayer(0);
            viewInfo.subresourceRange().layerCount(1);

            LongBuffer pView = stack.callocLong(1);
            res = VK12.vkCreateImageView(device.vkDevice(), viewInfo, null, pView);
            if (res != VK12.VK_SUCCESS) {
                throw new RuntimeException("vkCreateImageView failed: " + res);
            }
            this.vkImageView = pView.get(0);
        }
    }

    public static VkVoxyTexture createColorAttachment(VkVoxyDevice device, int width, int height, int format) {
        return new VkVoxyTexture(device, width, height, format, 1,
                VK12.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT | VK12.VK_IMAGE_USAGE_STORAGE_BIT | VK12.VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                VK12.VK_IMAGE_ASPECT_COLOR_BIT);
    }

    public static VkVoxyTexture createDepthAttachment(VkVoxyDevice device, int width, int height, int format) {
        int aspect = (format == VK12.VK_FORMAT_D32_SFLOAT || format == VK12.VK_FORMAT_D16_UNORM)
                ? VK12.VK_IMAGE_ASPECT_DEPTH_BIT
                : (VK12.VK_IMAGE_ASPECT_DEPTH_BIT | VK12.VK_IMAGE_ASPECT_STENCIL_BIT);
        return new VkVoxyTexture(device, width, height, format, 1,
                VK12.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT | VK12.VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                aspect);
    }

    public static VkVoxyTexture createSampledMipmap(VkVoxyDevice device, int width, int height, int format, int levels) {
        return new VkVoxyTexture(device, width, height, format, levels,
                VK12.VK_IMAGE_USAGE_SAMPLED_BIT | VK12.VK_IMAGE_USAGE_STORAGE_BIT | VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK12.VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                VK12.VK_IMAGE_ASPECT_COLOR_BIT);
    }

    public long vkImage() {
        return this.vkImage;
    }

    public long vkImageView() {
        return this.vkImageView;
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    public int format() {
        return this.format;
    }

    public int mipLevels() {
        return this.mipLevels;
    }

    public int currentLayout() {
        return this.currentLayout;
    }

    public int getAspectMask() {
        if (this.format == VK12.VK_FORMAT_D32_SFLOAT || this.format == VK12.VK_FORMAT_D16_UNORM) {
            return VK12.VK_IMAGE_ASPECT_DEPTH_BIT;
        } else if (this.format == VK12.VK_FORMAT_D24_UNORM_S8_UINT || this.format == VK12.VK_FORMAT_D32_SFLOAT_S8_UINT) {
            return VK12.VK_IMAGE_ASPECT_DEPTH_BIT | VK12.VK_IMAGE_ASPECT_STENCIL_BIT;
        } else {
            return VK12.VK_IMAGE_ASPECT_COLOR_BIT;
        }
    }

    public void transitionLayout(VkCommandBuffer cmd, int newLayout) {
        this.transitionLayout(cmd, newLayout, getAspectMask());
    }

    public void transitionLayout(VkCommandBuffer cmd, int newLayout, int aspectMask) {
        if (this.currentLayout == newLayout) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default();
            barrier.oldLayout(this.currentLayout);
            barrier.newLayout(newLayout);
            barrier.srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED);
            barrier.dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED);
            barrier.image(this.vkImage);
            barrier.subresourceRange().aspectMask(aspectMask);
            barrier.subresourceRange().baseMipLevel(0);
            barrier.subresourceRange().levelCount(this.mipLevels);
            barrier.subresourceRange().baseArrayLayer(0);
            barrier.subresourceRange().layerCount(1);

            int srcStage = VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
            int dstStage = VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
            barrier.srcAccessMask(VK12.VK_ACCESS_MEMORY_WRITE_BIT | VK12.VK_ACCESS_MEMORY_READ_BIT);
            barrier.dstAccessMask(VK12.VK_ACCESS_MEMORY_WRITE_BIT | VK12.VK_ACCESS_MEMORY_READ_BIT);

            VK12.vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, barrier);
        }

        this.currentLayout = newLayout;
    }

    public void transitionMipLayout(VkCommandBuffer cmd, int level, int oldLayout, int newLayout, int aspectMask) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default();
            barrier.oldLayout(oldLayout);
            barrier.newLayout(newLayout);
            barrier.srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED);
            barrier.dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED);
            barrier.image(this.vkImage);
            barrier.subresourceRange().aspectMask(aspectMask);
            barrier.subresourceRange().baseMipLevel(level);
            barrier.subresourceRange().levelCount(1);
            barrier.subresourceRange().baseArrayLayer(0);
            barrier.subresourceRange().layerCount(1);

            int srcStage = VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
            int dstStage = VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
            barrier.srcAccessMask(VK12.VK_ACCESS_MEMORY_WRITE_BIT | VK12.VK_ACCESS_MEMORY_READ_BIT);
            barrier.dstAccessMask(VK12.VK_ACCESS_MEMORY_WRITE_BIT | VK12.VK_ACCESS_MEMORY_READ_BIT);

            VK12.vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, barrier);
        }
    }

    public long getMipView(int level) {
        if (this.mipViews == null) {
            this.mipViews = new long[this.mipLevels];
        }
        if (this.mipViews[level] == 0) {
            int aspect = (this.format == VK12.VK_FORMAT_D32_SFLOAT || this.format == VK12.VK_FORMAT_D24_UNORM_S8_UINT || this.format == VK12.VK_FORMAT_D32_SFLOAT_S8_UINT || this.format == VK12.VK_FORMAT_D16_UNORM)
                    ? VK12.VK_IMAGE_ASPECT_DEPTH_BIT : VK12.VK_IMAGE_ASPECT_COLOR_BIT;
            this.mipViews[level] = createMipView(level, aspect);
        }
        return this.mipViews[level];
    }

    public long createMipView(int level, int aspectMask) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default();
            viewInfo.image(this.vkImage);
            viewInfo.viewType(VK12.VK_IMAGE_VIEW_TYPE_2D);
            viewInfo.format(this.format);
            viewInfo.subresourceRange().aspectMask(aspectMask);
            viewInfo.subresourceRange().baseMipLevel(level);
            viewInfo.subresourceRange().levelCount(1);
            viewInfo.subresourceRange().baseArrayLayer(0);
            viewInfo.subresourceRange().layerCount(1);

            LongBuffer pView = stack.callocLong(1);
            int res = VK12.vkCreateImageView(this.device.vkDevice(), viewInfo, null, pView);
            if (res != VK12.VK_SUCCESS) {
                throw new RuntimeException("vkCreateImageView for mip " + level + " failed: " + res);
            }
            return pView.get(0);
        }
    }

    @Override
    public void free() {
        super.free0();
        if (!this.closed) {
            this.closed = true;
            if (this.mipViews != null) {
                for (long v : this.mipViews) {
                    if (v != 0) {
                        VK12.vkDestroyImageView(this.device.vkDevice(), v, null);
                    }
                }
                this.mipViews = null;
            }
            VK12.vkDestroyImageView(this.device.vkDevice(), this.vkImageView, null);
            Vma.vmaDestroyImage(this.device.vma(), this.vkImage, this.vmaAllocation);
        }
    }
}
