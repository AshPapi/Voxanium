package me.cortex.voxy.client.core.rendering.util;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import me.cortex.voxy.client.core.backend.IBufferHolder;
import me.cortex.voxy.client.core.backend.vk.VkVoxyBuffer;
import me.cortex.voxy.client.core.backend.vk.VkVoxyDevice;
import me.cortex.voxy.client.core.gl.GlFence;
import me.cortex.voxy.client.core.gl.GlPersistentMappedBuffer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.AllocationArena;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.function.Consumer;

import static me.cortex.voxy.common.util.AllocationArena.SIZE_LIMIT;
import static org.lwjgl.opengl.GL11.glFinish;
import static org.lwjgl.opengl.GL30C.GL_MAP_READ_BIT;
import static org.lwjgl.opengl.GL42.GL_BUFFER_UPDATE_BARRIER_BIT;
import static org.lwjgl.opengl.GL42.glMemoryBarrier;
import static org.lwjgl.opengl.GL44.GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT;
import static org.lwjgl.opengl.GL45.glCopyNamedBufferSubData;

public class DownloadStream {
    public interface DownloadResultConsumer {
        void consume(long ptr, long size);
    }

    private final AllocationArena allocationArena = new AllocationArena();
    private final GlPersistentMappedBuffer downloadBuffer;
    private final VkVoxyBuffer vkDownloadBuffer;

    private final Deque<DownloadFrame> frames = new ArrayDeque<>();
    private final LongArrayList thisFrameAllocations = new LongArrayList();
    private final Deque<DownloadData> downloadList = new ArrayDeque<>();
    private final ArrayList<DownloadData> thisFrameDownloadList = new ArrayList<>();

    public DownloadStream(long size) {
        if (VkVoxyDevice.isVulkanActive()) {
            this.downloadBuffer = null;
            this.vkDownloadBuffer = VkVoxyBuffer.createHostReadMapped(VkVoxyDevice.getInstance(), size, VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        } else {
            this.downloadBuffer = new GlPersistentMappedBuffer(size, GL_MAP_READ_BIT);
            this.vkDownloadBuffer = null;
        }
        this.allocationArena.setLimit(size);
    }

    private long caddr = -1;
    private long offset = 0;

    public void download(IBufferHolder buffer, DownloadResultConsumer resultConsumer) {
        this.download(buffer, 0, buffer.size(), resultConsumer);
    }

    public void download(IBufferHolder buffer, Consumer<MemoryBuffer> resultConsumer) {
        this.download(buffer, 0, buffer.size(), resultConsumer);
    }

    public void download(IBufferHolder buffer, long downloadOffset, long size, Consumer<MemoryBuffer> consumer) {
        this.download(buffer, downloadOffset, size, (ptr,size2)-> {
            consumer.accept(MemoryBuffer.createUntrackedUnfreeableRawFrom(ptr, size));
        });
    }

    public void download(IBufferHolder buffer, long downloadOffset, long size, DownloadResultConsumer resultConsumer) {
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException();
        }
        if (size <= 0) {
            throw new IllegalArgumentException();
        }
        if (downloadOffset+size > buffer.size()) {
            throw new IllegalArgumentException();
        }

        long addr;
        if (this.caddr == -1 || !this.allocationArena.expand(this.caddr, (int) size)) {
            this.caddr = this.allocationArena.alloc((int) size);
            if (this.caddr == SIZE_LIMIT) {
                Logger.warn("Download stream full, preemptively committing, this could cause bad things to happen");
                this.commit();
                int attempts = 10;
                while (--attempts != 0 && this.caddr == SIZE_LIMIT) {
                    if (this.downloadBuffer != null) {
                        glFinish();
                    }
                    this.tick();
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

        if (this.caddr + size > this.bufferSize()) {
            throw new IllegalStateException();
        }

        this.downloadList.add(new DownloadData(buffer, addr, downloadOffset, size, resultConsumer));

        this.commit();
    }


    public void commit() {
        if (this.downloadList.isEmpty()) {
            return;
        }
        if (this.vkDownloadBuffer != null) {
            this.commitVulkan();
        } else {
            glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
            for (var entry : this.downloadList) {
                glCopyNamedBufferSubData(entry.target.id(), this.downloadBuffer.id, entry.targetOffset, entry.downloadStreamOffset, entry.size);
            }
            glMemoryBarrier(GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT);
        }
        this.thisFrameDownloadList.addAll(this.downloadList);
        this.downloadList.clear();

        this.caddr = -1;
        this.offset = 0;
    }

    private void commitVulkan() {
        var dev = VkVoxyDevice.getInstance();
        VkCommandBuffer cmd = dev.allocateCommandBuffer();
        dev.beginSingleTimeCommands(cmd);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack).sType$Default();
            barrier.srcAccessMask(VK12.VK_ACCESS_SHADER_WRITE_BIT | VK12.VK_ACCESS_TRANSFER_WRITE_BIT);
            barrier.dstAccessMask(VK12.VK_ACCESS_TRANSFER_READ_BIT);
            VK12.vkCmdPipelineBarrier(cmd, VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT, 0, barrier, null, null);

            VkBufferCopy.Buffer copy = VkBufferCopy.calloc(1, stack);
            for (var entry : this.downloadList) {
                copy.srcOffset(entry.targetOffset).dstOffset(entry.downloadStreamOffset).size(entry.size);
                VK12.vkCmdCopyBuffer(cmd, entry.target.rawHandle(), this.vkDownloadBuffer.vkBuffer(), copy);
            }

            barrier.srcAccessMask(VK12.VK_ACCESS_TRANSFER_WRITE_BIT);
            barrier.dstAccessMask(VK12.VK_ACCESS_HOST_READ_BIT);
            VK12.vkCmdPipelineBarrier(cmd, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT, VK12.VK_PIPELINE_STAGE_HOST_BIT, 0, barrier, null, null);
        }
        dev.endAndSubmitSingleTimeCommands(cmd, dev.graphicsQueue());
        dev.freeCommandBuffer(cmd);
    }

    private long bufferSize() {
        return this.downloadBuffer != null ? this.downloadBuffer.size() : this.vkDownloadBuffer.size();
    }

    private long bufferAddress() {
        return this.downloadBuffer != null ? this.downloadBuffer.addr() : this.vkDownloadBuffer.mappedAddress();
    }

    private static boolean isDone(DownloadFrame frame) {
        return frame.fence == null || frame.fence.signaled();
    }

    public void tick() {
        this.commit();
        if (!this.thisFrameAllocations.isEmpty()) {
            GlFence fence = this.downloadBuffer != null ? new GlFence() : null;
            this.frames.add(new DownloadFrame(fence, new LongArrayList(this.thisFrameAllocations), new ArrayList<>(this.thisFrameDownloadList)));
            this.thisFrameAllocations.clear();
            this.thisFrameDownloadList.clear();
        }

        while (!this.frames.isEmpty()) {
            if (!isDone(this.frames.peek())) {
                break;
            }

            var frame = this.frames.pop();

            if (this.vkDownloadBuffer != null) {
                this.vkDownloadBuffer.invalidate(0, this.vkDownloadBuffer.size());
            }

            for (var data : frame.data) {
                data.resultConsumer.consume(this.bufferAddress() + data.downloadStreamOffset, data.size);
            }

            frame.allocations.forEach(this.allocationArena::free);
            if (frame.fence != null) {
                frame.fence.free();
            }
        }
    }

    public void waitDiscard() {
        if (this.vkDownloadBuffer != null) {
            this.commit();
            while (!this.frames.isEmpty()) {
                this.frames.pop().allocations.forEach(this.allocationArena::free);
            }
            return;
        }
        glFinish();
        var fence = new GlFence();
        glFinish();
        while (!fence.signaled())
            Thread.onSpinWait();
        fence.free();
        while (!this.frames.isEmpty()) {
            var frame = this.frames.pop();
            while (!frame.fence.signaled()) Thread.onSpinWait();
            frame.allocations.forEach(this.allocationArena::free);
            frame.fence.free();
        }
    }

    public void flushWaitClear() {
        if (this.vkDownloadBuffer != null) {
            this.tick();
            this.tick();
            if (!this.frames.isEmpty()) {
                throw new IllegalStateException();
            }
            return;
        }
        glFinish();
        this.tick();
        var fence = new GlFence();
        glFinish();
        while (!fence.signaled()) {
            glFinish();
            Thread.onSpinWait();
        }
        fence.free();
        this.tick();
        if (!this.frames.isEmpty()) {
            throw new IllegalStateException();
        }
    }

    private record DownloadFrame(GlFence fence, LongArrayList allocations, ArrayList<DownloadData> data) {}
    private record DownloadData(IBufferHolder target, long downloadStreamOffset, long targetOffset, long size, DownloadResultConsumer resultConsumer) {}


    public static final DownloadStream INSTANCE = new DownloadStream(1<<25);
}
