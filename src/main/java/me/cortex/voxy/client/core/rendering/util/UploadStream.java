package me.cortex.voxy.client.core.rendering.util;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import me.cortex.voxy.client.core.backend.IBufferHolder;
import me.cortex.voxy.client.core.backend.vk.VkVoxyBuffer;
import me.cortex.voxy.client.core.backend.vk.VkVoxyDevice;
import me.cortex.voxy.client.core.backend.vk.VkVoxyTexture;
import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlFence;
import me.cortex.voxy.client.core.gl.GlPersistentMappedBuffer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.AllocationArena;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier;

import java.util.ArrayDeque;
import java.util.Deque;

import static me.cortex.voxy.common.util.AllocationArena.SIZE_LIMIT;
import static org.lwjgl.opengl.ARBDirectStateAccess.glCopyNamedBufferSubData;
import static org.lwjgl.opengl.ARBMapBufferRange.*;
import static org.lwjgl.opengl.GL11.glFinish;
import static org.lwjgl.opengl.GL42.glMemoryBarrier;
import static org.lwjgl.opengl.GL42C.GL_BUFFER_UPDATE_BARRIER_BIT;
import static org.lwjgl.opengl.GL44.GL_CLIENT_STORAGE_BIT;
import static org.lwjgl.opengl.GL44.GL_MAP_COHERENT_BIT;
import static org.lwjgl.opengl.GL45C.glFlushMappedNamedBufferRange;

public class UploadStream {
    public static final int BASE_ALLOCATION_ALIGNEMENT = Math.max(Capabilities.INSTANCE.ssboBindingAlignment, 16);

    private final AllocationArena allocationArena = new AllocationArena();
    private final GlPersistentMappedBuffer uploadBuffer;
    private final VkVoxyBuffer vkUploadBuffer;

    private final Deque<UploadFrame> frames = new ArrayDeque<>();
    private final LongArrayList thisFrameAllocations = new LongArrayList();
    private final Deque<UploadData> uploadList = new ArrayDeque<>();
    private final Deque<ImageUploadData> imageUploadList = new ArrayDeque<>();

    private static final boolean USE_COHERENT = false;

    public UploadStream(long size) {
        if (VkVoxyDevice.isVulkanActive()) {
            this.uploadBuffer = null;
            this.vkUploadBuffer = VkVoxyBuffer.createHostMapped(VkVoxyDevice.getInstance(), size, VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        } else {
            this.uploadBuffer = new GlPersistentMappedBuffer(size, GL_CLIENT_STORAGE_BIT | GL_MAP_WRITE_BIT | GL_MAP_UNSYNCHRONIZED_BIT | (USE_COHERENT ? GL_MAP_COHERENT_BIT : GL_MAP_FLUSH_EXPLICIT_BIT)).name("UploadStream");
            this.vkUploadBuffer = null;
        }
        this.allocationArena.setLimit(size);
    }

    private long caddr = -1;
    private long offset = 0;

    public void upload(IBufferHolder buffer, long destOffset, MemoryBuffer data) {
        data.cpyTo(this.upload(buffer, destOffset, data.size));
    }

    public long uploadTo(IBufferHolder buffer) {
        return this.upload(buffer, 0, buffer.size());
    }

    public long upload(IBufferHolder buffer, long destOffset, long size) {
        long addr = this.rawUploadAddress((int) size);
        this.uploadList.add(new UploadData(buffer, addr, destOffset, size));
        return this.getBaseAddress() + addr;
    }

    public long rawUpload(int size) {
        return this.getBaseAddress() + this.rawUploadAddress(size);
    }

    public void copyFromStream(IBufferHolder target, long uploadOffset, long targetOffset, long size) {
        this.uploadList.add(new UploadData(target, uploadOffset, targetOffset, size));
    }

    public void copyFromStreamToImage(VkVoxyTexture target, long uploadOffset, int level, int x, int y, int width, int height) {
        this.imageUploadList.add(new ImageUploadData(target, uploadOffset, level, x, y, width, height));
    }

    public long rawUploadAddress(int size) {
        if (size < 0) {
            throw new IllegalStateException("Negative size");
        }

        size = alignUp(size, BASE_ALLOCATION_ALIGNEMENT);

        long maxBufferSize = this.uploadBuffer != null ? this.uploadBuffer.size() : this.vkUploadBuffer.size();
        if (size > maxBufferSize) {
            throw new IllegalArgumentException();
        }

        long addr;
        if (this.caddr == -1 || !this.allocationArena.expand(this.caddr, (int) size)) {
            if ((!USE_COHERENT) && this.caddr != -1 && this.uploadBuffer != null) {
                glFlushMappedNamedBufferRange(this.uploadBuffer.id, this.caddr, this.offset);
            }
            this.caddr = this.allocationArena.alloc((int) size);
            if (this.caddr == SIZE_LIMIT) {
                Logger.error("Upload stream full, preemptively committing, this could cause bad things to happen");
                int attempts = 10;
                while (--attempts != 0 && this.caddr == SIZE_LIMIT) {
                    if (this.uploadBuffer != null) {
                        glFinish();
                    }
                    this.tick(false);
                    this.caddr = this.allocationArena.alloc((int) size);
                }
                if (this.caddr == SIZE_LIMIT) {
                    throw new IllegalStateException("Could not allocate memory segment big enough for upload even after force flush");
                }
            }
            this.thisFrameAllocations.add(this.caddr);
            this.offset = size;
            addr = this.caddr;
        } else {
            addr = this.caddr + this.offset;
            this.offset += size;
        }

        return addr;
    }

    public void commit() {
        if ((!USE_COHERENT) && this.caddr != -1 && this.uploadBuffer != null) {
            glFlushMappedNamedBufferRange(this.uploadBuffer.id, this.caddr, this.offset);
        }

        if (this.uploadList.isEmpty() && this.imageUploadList.isEmpty()) {
            return;
        }

        if (this.vkUploadBuffer != null) {
            this.commitVulkan();
        } else {
            glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
            for (var entry : this.uploadList) {
                glCopyNamedBufferSubData(this.uploadBuffer.id, (int) entry.target.rawHandle(), entry.uploadOffset, entry.targetOffset, entry.size);
            }
            glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
        }

        this.uploadList.clear();
        this.imageUploadList.clear();
        this.caddr = -1;
        this.offset = 0;
    }

    private void commitVulkan() {
        this.vkUploadBuffer.flush(0, this.vkUploadBuffer.size());

        var dev = VkVoxyDevice.getInstance();
        VkCommandBuffer cmd = dev.allocateCommandBuffer();
        dev.beginSingleTimeCommands(cmd);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack).sType$Default();
            barrier.srcAccessMask(VK12.VK_ACCESS_MEMORY_READ_BIT | VK12.VK_ACCESS_MEMORY_WRITE_BIT);
            barrier.dstAccessMask(VK12.VK_ACCESS_TRANSFER_WRITE_BIT);
            VK12.vkCmdPipelineBarrier(cmd, VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT, 0, barrier, null, null);

            var entries = this.uploadList.toArray(new UploadData[0]);
            var batchRanges = new java.util.TreeMap<Long, Long>();
            int start = 0;
            while (start < entries.length) {
                batchRanges.clear();
                int end = start;
                while (end < entries.length && entries[end].target == entries[start].target && !overlaps(batchRanges, entries[end])) {
                    batchRanges.put(entries[end].targetOffset, entries[end].targetOffset + entries[end].size);
                    end++;
                }
                VkBufferCopy.Buffer copies = VkBufferCopy.malloc(end - start);
                for (int i = start; i < end; i++) {
                    copies.get(i - start)
                            .srcOffset(entries[i].uploadOffset)
                            .dstOffset(entries[i].targetOffset)
                            .size(entries[i].size);
                }
                VK12.vkCmdCopyBuffer(cmd, this.vkUploadBuffer.vkBuffer(), entries[start].target.rawHandle(), copies);
                copies.free();
                if (end < entries.length) {
                    barrier.srcAccessMask(VK12.VK_ACCESS_TRANSFER_WRITE_BIT);
                    barrier.dstAccessMask(VK12.VK_ACCESS_TRANSFER_WRITE_BIT);
                    VK12.vkCmdPipelineBarrier(cmd, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT, 0, barrier, null, null);
                }
                start = end;
            }

            this.recordImageCopies(cmd);

            barrier.srcAccessMask(VK12.VK_ACCESS_TRANSFER_WRITE_BIT);
            barrier.dstAccessMask(VK12.VK_ACCESS_MEMORY_READ_BIT | VK12.VK_ACCESS_MEMORY_WRITE_BIT);
            VK12.vkCmdPipelineBarrier(cmd, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT, VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, barrier, null, null);
        }

        dev.endAndSubmitSingleTimeCommands(cmd, dev.graphicsQueue());
        dev.freeCommandBuffer(cmd);
    }

    private void recordImageCopies(VkCommandBuffer cmd) {
        if (this.imageUploadList.isEmpty()) {
            return;
        }
        var entries = this.imageUploadList.toArray(new ImageUploadData[0]);
        int start = 0;
        while (start < entries.length) {
            int end = start + 1;
            while (end < entries.length && entries[end].target == entries[start].target) {
                end++;
            }
            var image = entries[start].target;
            image.transitionLayout(cmd, VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            VkBufferImageCopy.Buffer copies = VkBufferImageCopy.calloc(end - start);
            for (int i = start; i < end; i++) {
                var e = entries[i];
                var copy = copies.get(i - start);
                copy.bufferOffset(e.uploadOffset);
                copy.bufferRowLength(0);
                copy.bufferImageHeight(0);
                copy.imageSubresource().aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(e.level).baseArrayLayer(0).layerCount(1);
                copy.imageOffset().set(e.x, e.y, 0);
                copy.imageExtent().set(e.width, e.height, 1);
            }
            VK12.vkCmdCopyBufferToImage(cmd, this.vkUploadBuffer.vkBuffer(), image.vkImage(), VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copies);
            copies.free();
            image.transitionLayout(cmd, VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            start = end;
        }
    }

    private static boolean overlaps(java.util.TreeMap<Long, Long> ranges, UploadData entry) {
        long s = entry.targetOffset;
        long e = entry.targetOffset + entry.size;
        var below = ranges.floorEntry(s);
        if (below != null && below.getValue() > s) {
            return true;
        }
        var above = ranges.ceilingEntry(s);
        return above != null && above.getKey() < e;
    }

    public void tick() {
        this.tick(true);
    }

    private void tick(boolean commit) {
        if (commit) {
            this.commit();
        }

        if (!this.thisFrameAllocations.isEmpty()) {
            GlFence fence = this.uploadBuffer != null ? new GlFence() : null;
            this.frames.add(new UploadFrame(fence, new LongArrayList(this.thisFrameAllocations)));
            this.thisFrameAllocations.clear();
        }

        while (!this.frames.isEmpty()) {
            var frame = this.frames.peek();
            if (frame.fence != null && !frame.fence.signaled()) {
                break;
            }
            this.frames.pop();
            frame.allocations.forEach(this.allocationArena::free);
            if (frame.fence != null) {
                frame.fence.free();
            }
        }
    }

    public long getBaseAddress() {
        return this.uploadBuffer != null ? this.uploadBuffer.addr() : this.vkUploadBuffer.mappedAddress();
    }

    public int getRawBufferId() {
        return this.uploadBuffer != null ? this.uploadBuffer.id : 0;
    }

    private record UploadFrame(GlFence fence, LongArrayList allocations) {}
    private record UploadData(IBufferHolder target, long uploadOffset, long targetOffset, long size) {}
    private record ImageUploadData(VkVoxyTexture target, long uploadOffset, int level, int x, int y, int width, int height) {}

    public static final UploadStream INSTANCE = new UploadStream(1 << 26);

    public static long alignUp(long val, long alignment) {
        return ((val + alignment - 1) / alignment) * alignment;
    }

    public static int alignUp(int val, int alignment) {
        return ((val + alignment - 1) / alignment) * alignment;
    }

    public static int alignUpAlloc(int val) {
        return ((val + BASE_ALLOCATION_ALIGNEMENT - 1) / BASE_ALLOCATION_ALIGNEMENT) * BASE_ALLOCATION_ALIGNEMENT;
    }
}
