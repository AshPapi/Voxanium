package me.cortex.voxy.client.core.backend.vk;

import me.cortex.voxy.common.util.TrackedObject;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.LongBuffer;

public class VkVoxyBuffer extends TrackedObject implements me.cortex.voxy.client.core.backend.IBufferHolder {
    @Override
    public long rawHandle() {
        return this.vkBuffer;
    }
    private final VkVoxyDevice device;
    private final long vkBuffer;
    private final long vmaAllocation;
    private final long size;
    private long mappedAddress = 0;
    private boolean closed = false;

    public VkVoxyBuffer(VkVoxyDevice device, long size, int vkUsage, int vmaUsage) {
        this(device, size, vkUsage, vmaUsage, 0);
    }

    public VkVoxyBuffer(VkVoxyDevice device, long size, int vkUsage, int vmaUsage, int vmaFlags) {
        this.device = device;
        this.size = size;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferCreateInfo = VkBufferCreateInfo.calloc(stack).sType$Default();
            bufferCreateInfo.size(size);
            bufferCreateInfo.usage(vkUsage);
            bufferCreateInfo.sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE);

            VmaAllocationCreateInfo allocCreateInfo = VmaAllocationCreateInfo.calloc(stack);
            allocCreateInfo.usage(vmaUsage);
            allocCreateInfo.flags(vmaFlags);

            LongBuffer pBuffer = stack.callocLong(1);
            PointerBuffer pAlloc = stack.callocPointer(1);

            int result = Vma.vmaCreateBuffer(device.vma(), bufferCreateInfo, allocCreateInfo, pBuffer, pAlloc, null);
            if (result != VK12.VK_SUCCESS) {
                throw new RuntimeException("vmaCreateBuffer failed for size " + size + ", error: " + result);
            }
            this.vkBuffer = pBuffer.get(0);
            this.vmaAllocation = pAlloc.get(0);

            if ((vmaFlags & Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT) != 0) {
                PointerBuffer pMapped = stack.callocPointer(1);
                Vma.vmaMapMemory(device.vma(), this.vmaAllocation, pMapped);
                this.mappedAddress = pMapped.get(0);
            }
        }
    }

    public static VkVoxyBuffer createDeviceLocal(VkVoxyDevice device, long size, int vkUsage) {
        return new VkVoxyBuffer(device, size,
                vkUsage | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                Vma.VMA_MEMORY_USAGE_GPU_ONLY, 0);
    }

    public static VkVoxyBuffer createHostMapped(VkVoxyDevice device, long size, int vkUsage) {
        return new VkVoxyBuffer(device, size,
                vkUsage | VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                Vma.VMA_MEMORY_USAGE_CPU_TO_GPU,
                Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT | Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
    }

    public static VkVoxyBuffer createHostReadMapped(VkVoxyDevice device, long size, int vkUsage) {
        return new VkVoxyBuffer(device, size,
                vkUsage | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                Vma.VMA_MEMORY_USAGE_GPU_TO_CPU,
                Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT | Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT);
    }

    public long vkBuffer() {
        return this.vkBuffer;
    }

    public long size() {
        return this.size;
    }

    public long mappedAddress() {
        if (this.mappedAddress == 0) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer pMapped = stack.callocPointer(1);
                Vma.vmaMapMemory(this.device.vma(), this.vmaAllocation, pMapped);
                this.mappedAddress = pMapped.get(0);
            }
        }
        return this.mappedAddress;
    }

    public void flush(long offset, long length) {
        Vma.vmaFlushAllocation(this.device.vma(), this.vmaAllocation, offset, length);
    }

    public void invalidate(long offset, long length) {
        Vma.vmaInvalidateAllocation(this.device.vma(), this.vmaAllocation, offset, length);
    }

    public void zero(VkCommandBuffer cmd) {
        this.zeroRange(cmd, 0, this.size);
    }

    public void zeroRange(VkCommandBuffer cmd, long offset, long length) {
        long alignedLength = (length & ~3L);
        if (alignedLength > 0) {
            VK12.vkCmdFillBuffer(cmd, this.vkBuffer, offset, alignedLength, 0);
        }
    }

    @Override
    public void free() {
        super.free0();
        if (!this.closed) {
            this.closed = true;
            if (this.mappedAddress != 0) {
                Vma.vmaUnmapMemory(this.device.vma(), this.vmaAllocation);
                this.mappedAddress = 0;
            }
            Vma.vmaDestroyBuffer(this.device.vma(), this.vkBuffer, this.vmaAllocation);
        }
    }
}
