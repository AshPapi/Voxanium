package me.cortex.voxy.client.core.rendering.section.geometry;

import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.ThreadUtils;

import static org.lwjgl.opengl.ARBSparseBuffer.GL_SPARSE_STORAGE_BIT_ARB;
import static org.lwjgl.opengl.ARBSparseBuffer.glBufferPageCommitmentARB;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.glBindBuffer;

public class BasicSectionGeometryData implements IGeometryData {
    public static final int SECTION_METADATA_SIZE = 32;
    private final me.cortex.voxy.client.core.backend.IBufferHolder sectionMetadataBuffer;
    private final me.cortex.voxy.client.core.backend.IBufferHolder geometryBuffer;
    public final boolean isExternalGeometryBuffer;

    private final int maxSectionCount;
    private int currentSectionCount;

    public BasicSectionGeometryData(int maxSectionCount, GlBuffer geometryBuffer) {
        this.maxSectionCount = maxSectionCount;
        this.sectionMetadataBuffer = new GlBuffer((long) maxSectionCount * SECTION_METADATA_SIZE);
        if ((geometryBuffer.size()%8)!=0) {
            throw new IllegalStateException();
        }
        this.geometryBuffer = geometryBuffer;
        this.isExternalGeometryBuffer = true;
    }

    public BasicSectionGeometryData(int maxSectionCount, long geometryCapacity) {
        this.isExternalGeometryBuffer = false;
        this.maxSectionCount = maxSectionCount;
        if (me.cortex.voxy.client.core.backend.vk.VkVoxyDevice.isVulkanActive()) {
            var dev = me.cortex.voxy.client.core.backend.vk.VkVoxyDevice.getInstance();
            this.geometryBuffer = me.cortex.voxy.client.core.backend.vk.VkVoxyBuffer.createDeviceLocal(dev,
                    geometryCapacity,
                    org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            this.sectionMetadataBuffer = me.cortex.voxy.client.core.backend.vk.VkVoxyBuffer.createDeviceLocal(dev,
                    (long) maxSectionCount * SECTION_METADATA_SIZE,
                    org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            return;
        }
        this.sectionMetadataBuffer = new GlBuffer((long) maxSectionCount * SECTION_METADATA_SIZE);
        if ((geometryCapacity%8)!=0) {
            throw new IllegalStateException();
        }
        long start = System.currentTimeMillis();
        String msg = "Creating and zeroing " + (geometryCapacity/(1024*1024)) + "MB geometry buffer";
        if (Capabilities.INSTANCE.canQueryGpuMemory) {
            msg += " driver states " + (Capabilities.INSTANCE.getFreeDedicatedGpuMemory()/(1024*1024)) + "MB of free memory";
        }
        Logger.info(msg);
        Logger.info("if your game crashes/exits here without any other log message, try manually decreasing the geometry capacity");
        glGetError();
        GlBuffer buffer = null;
        if (!(Capabilities.INSTANCE.isNvidia&&ThreadUtils.isWindows&&Capabilities.INSTANCE.sparseBuffer)) {
            buffer = new GlBuffer(geometryCapacity, false);
        } else {
            Logger.info("Running on nvidia, using workaround sparse buffer allocation");
        }
        int error = glGetError();
        if (error != GL_NO_ERROR || buffer == null) {
            if ((buffer == null || error == GL_OUT_OF_MEMORY) && Capabilities.INSTANCE.sparseBuffer) {
                if (buffer != null) {
                    Logger.error("Failed to allocate geometry buffer, attempting workaround with sparse buffers");
                    buffer.free();
                }
                buffer = new GlBuffer(geometryCapacity, GL_SPARSE_STORAGE_BIT_ARB);
                error = glGetError();
                if (error != GL_NO_ERROR) {
                    buffer.free();
                    throw new IllegalStateException("Unable to allocate geometry buffer using workaround, got gl error " + error);
                }
            } else {
                throw new IllegalStateException("Unable to allocate geometry buffer, got gl error " + error);
            }
        }
        this.geometryBuffer = buffer;
        long delta = System.currentTimeMillis() - start;
        Logger.info("Successfully allocated the geometry buffer in " + delta + "ms");
    }

    private long sparseCommitment = 0;
    public void ensureAccessable(int maxElementAccess) {
        long size = (Integer.toUnsignedLong(maxElementAccess)*8L+65535L)&~65535L;
        if (this.geometryBuffer instanceof GlBuffer glBuf && glBuf.isSparse()) {
            if (this.sparseCommitment < size) {
                glBindBuffer(GL_ARRAY_BUFFER, glBuf.id);
                size += 65536L*1024;
                glBufferPageCommitmentARB(GL_ARRAY_BUFFER, this.sparseCommitment, size-this.sparseCommitment, true);
                glBindBuffer(GL_ARRAY_BUFFER, 0);
                this.sparseCommitment = size;
            }
        }
    }

    public me.cortex.voxy.client.core.backend.IBufferHolder getGeometryBuffer() {
        return this.geometryBuffer;
    }

    public me.cortex.voxy.client.core.backend.IBufferHolder getMetadataBuffer() {
        return this.sectionMetadataBuffer;
    }

    @Override
    public int getSectionCount() {
        return this.currentSectionCount;
    }

    public void setSectionCount(int count) {
        this.currentSectionCount = count;
    }

    public int getMaxSectionCount() {
        return this.maxSectionCount;
    }

    public long getGeometryCapacityBytes() {
        return this.geometryBuffer.size();
    }

    @Override
    public void free() {
        if (this.sectionMetadataBuffer instanceof GlBuffer gl) gl.free();
        else if (this.sectionMetadataBuffer instanceof me.cortex.voxy.client.core.backend.vk.VkVoxyBuffer vk) vk.free();

        if (this.geometryBuffer instanceof me.cortex.voxy.client.core.backend.vk.VkVoxyBuffer vk) {
            vk.free();
            return;
        }

        long gpuMemory = 0;
        if (Capabilities.INSTANCE.canQueryGpuMemory) {
            glFinish();
            gpuMemory = Capabilities.INSTANCE.getFreeDedicatedGpuMemory();
        }
        if (this.geometryBuffer instanceof GlBuffer glBuf && glBuf.isSparse()) {
            glBindBuffer(GL_ARRAY_BUFFER, glBuf.id);
            glBufferPageCommitmentARB(GL_ARRAY_BUFFER, 0, this.sparseCommitment, false);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
        }

        glFinish();

        if (!this.isExternalGeometryBuffer) {
            if (this.geometryBuffer instanceof GlBuffer gl) gl.free();
            else if (this.geometryBuffer instanceof me.cortex.voxy.client.core.backend.vk.VkVoxyBuffer vk) vk.free();
            glFinish();
            if (Capabilities.INSTANCE.canQueryGpuMemory) {
                long releaseSize = (long) (this.geometryBuffer.size() * 0.75);
                if (this.geometryBuffer instanceof GlBuffer glBuf && glBuf.isSparse()) {
                    releaseSize = (long) (this.sparseCommitment * 0.75);
                }
                if (Capabilities.INSTANCE.getFreeDedicatedGpuMemory() - gpuMemory <= releaseSize) {
                    Logger.info("Attempting to wait for gpu memory to release");
                    long start = System.currentTimeMillis();

                    long TIMEOUT = 400;

                    while (System.currentTimeMillis() - start < TIMEOUT) {
                        glFinish();
                        if (Capabilities.INSTANCE.getFreeDedicatedGpuMemory() - gpuMemory > releaseSize) break;
                    }
                    if (Capabilities.INSTANCE.getFreeDedicatedGpuMemory() - gpuMemory <= releaseSize) {
                        Logger.warn("Failed to wait for gpu memory to be freed, this could indicate an issue with the driver");
                    }
                }
            }
        }
    }

    @Override
    public long getMaxCapacity() {
        return this.geometryBuffer.size();
    }
}
