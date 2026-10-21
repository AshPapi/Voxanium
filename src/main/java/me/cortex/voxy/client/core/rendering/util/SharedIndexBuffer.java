package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.lwjgl.system.MemoryUtil;


public class SharedIndexBuffer {
    public static final int CUBE_INDEX_OFFSET = (1<<16)*6*2;
    public static final SharedIndexBuffer INSTANCE = new SharedIndexBuffer();
    public static final SharedIndexBuffer INSTANCE_BYTE = new SharedIndexBuffer(true);
    public static final SharedIndexBuffer INSTANCE_BB_BYTE = new SharedIndexBuffer(true, true);

    private final GlBuffer indexBuffer;
    private final int bufferMode;
    private final long bufferSize;
    private me.cortex.voxy.client.core.backend.vk.VkVoxyBuffer vkIndexBuffer;

    private synchronized void initVkBuffer() {
        if (this.vkIndexBuffer != null || !me.cortex.voxy.client.core.backend.vk.VkVoxyDevice.isVulkanActive()) {
            return;
        }
        this.vkIndexBuffer = me.cortex.voxy.client.core.backend.vk.VkVoxyBuffer.createDeviceLocal(
                me.cortex.voxy.client.core.backend.vk.VkVoxyDevice.getInstance(), this.bufferSize,
                org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_INDEX_BUFFER_BIT | org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT);

        if (this.bufferMode == 0) {
            var quadIndexBuff = generateQuadIndicesShort(16380);
            var cubeBuff = generateCubeIndexBuffer();
            long ptr = UploadStream.INSTANCE.upload(this.vkIndexBuffer, 0, this.vkIndexBuffer.size());
            quadIndexBuff.cpyTo(ptr);
            cubeBuff.cpyTo((1<<16)*2*6 + ptr);
            quadIndexBuff.free();
            cubeBuff.free();
            UploadStream.INSTANCE.commit();
        } else if (this.bufferMode == 1) {
            var quadIndexBuff = generateQuadIndicesByte(63);
            var cubeBuff = generateCubeIndexBuffer();
            long ptr = UploadStream.INSTANCE.upload(this.vkIndexBuffer, 0, this.vkIndexBuffer.size());
            quadIndexBuff.cpyTo(ptr);
            cubeBuff.cpyTo((1<<8)*6 + ptr);
            quadIndexBuff.free();
            cubeBuff.free();
            UploadStream.INSTANCE.commit();
        } else if (this.bufferMode == 2) {
            var cubeBuff = generateByteCubesIndexBuffer(256/8);
            cubeBuff.cpyTo(UploadStream.INSTANCE.upload(this.vkIndexBuffer, 0, this.vkIndexBuffer.size()));
            UploadStream.INSTANCE.commit();
            cubeBuff.free();
        }
    }

    public SharedIndexBuffer() {
        this.bufferMode = 0;
        this.bufferSize = (1<<16)*6*2 + 6*2*3;
        if (me.cortex.voxy.client.core.backend.vk.VkVoxyDevice.isVulkanActive()) {
            this.indexBuffer = null;
            this.initVkBuffer();
        } else {
            this.indexBuffer = new GlBuffer(this.bufferSize);
            var quadIndexBuff = generateQuadIndicesShort(16380);
            var cubeBuff = generateCubeIndexBuffer();

            long ptr = UploadStream.INSTANCE.upload(this.indexBuffer, 0, this.indexBuffer.size());
            quadIndexBuff.cpyTo(ptr);
            cubeBuff.cpyTo((1<<16)*2*6 + ptr);

            quadIndexBuff.free();
            cubeBuff.free();
            UploadStream.INSTANCE.commit();
        }
    }

    private SharedIndexBuffer(boolean type2) {
        this.bufferMode = 1;
        this.bufferSize = (1<<8)*6 + 6*2*3;
        if (me.cortex.voxy.client.core.backend.vk.VkVoxyDevice.isVulkanActive()) {
            this.indexBuffer = null;
            this.initVkBuffer();
        } else {
            this.indexBuffer = new GlBuffer(this.bufferSize);
            var quadIndexBuff = generateQuadIndicesByte(63);
            var cubeBuff = generateCubeIndexBuffer();

            long ptr = UploadStream.INSTANCE.upload(this.indexBuffer, 0, this.indexBuffer.size());
            quadIndexBuff.cpyTo(ptr);
            cubeBuff.cpyTo((1<<8)*6 + ptr);

            quadIndexBuff.free();
            cubeBuff.free();
        }
    }

    private SharedIndexBuffer(boolean type2, boolean type3) {
        this.bufferMode = 2;
        this.bufferSize = 6*2*3*(256/8);
        if (me.cortex.voxy.client.core.backend.vk.VkVoxyDevice.isVulkanActive()) {
            this.indexBuffer = null;
            this.initVkBuffer();
        } else {
            this.indexBuffer = new GlBuffer(this.bufferSize);
            var cubeBuff = generateByteCubesIndexBuffer(256/8);

            cubeBuff.cpyTo(UploadStream.INSTANCE.upload(this.indexBuffer, 0, this.indexBuffer.size()));
            UploadStream.INSTANCE.commit();
            cubeBuff.free();
        }
    }

    private static MemoryBuffer generateCubeIndexBuffer() {
        var buffer = new MemoryBuffer(6*2*3);
        long ptr = buffer.address;
        MemoryUtil.memSet(ptr, 0, 6*2*3 );

        MemoryUtil.memPutByte(ptr++, (byte) 0);
        MemoryUtil.memPutByte(ptr++, (byte) 1);
        MemoryUtil.memPutByte(ptr++, (byte) 2);
        MemoryUtil.memPutByte(ptr++, (byte) 3);
        MemoryUtil.memPutByte(ptr++, (byte) 2);
        MemoryUtil.memPutByte(ptr++, (byte) 1);

        MemoryUtil.memPutByte(ptr++, (byte) 6);
        MemoryUtil.memPutByte(ptr++, (byte) 5);
        MemoryUtil.memPutByte(ptr++, (byte) 4);
        MemoryUtil.memPutByte(ptr++, (byte) 5);
        MemoryUtil.memPutByte(ptr++, (byte) 6);
        MemoryUtil.memPutByte(ptr++, (byte) 7);

        MemoryUtil.memPutByte(ptr++, (byte) 0);
        MemoryUtil.memPutByte(ptr++, (byte) 4);
        MemoryUtil.memPutByte(ptr++, (byte) 1);
        MemoryUtil.memPutByte(ptr++, (byte) 5);
        MemoryUtil.memPutByte(ptr++, (byte) 1);
        MemoryUtil.memPutByte(ptr++, (byte) 4);

        MemoryUtil.memPutByte(ptr++, (byte) 3);
        MemoryUtil.memPutByte(ptr++, (byte) 6);
        MemoryUtil.memPutByte(ptr++, (byte) 2);
        MemoryUtil.memPutByte(ptr++, (byte) 6);
        MemoryUtil.memPutByte(ptr++, (byte) 3);
        MemoryUtil.memPutByte(ptr++, (byte) 7);

        MemoryUtil.memPutByte(ptr++, (byte) 2);
        MemoryUtil.memPutByte(ptr++, (byte) 4);
        MemoryUtil.memPutByte(ptr++, (byte) 0);
        MemoryUtil.memPutByte(ptr++, (byte) 4);
        MemoryUtil.memPutByte(ptr++, (byte) 2);
        MemoryUtil.memPutByte(ptr++, (byte) 6);

        MemoryUtil.memPutByte(ptr++, (byte) 1);
        MemoryUtil.memPutByte(ptr++, (byte) 5);
        MemoryUtil.memPutByte(ptr++, (byte) 3);
        MemoryUtil.memPutByte(ptr++, (byte) 7);
        MemoryUtil.memPutByte(ptr++, (byte) 3);
        MemoryUtil.memPutByte(ptr++, (byte) 5);

        return buffer;
    }

    private static MemoryBuffer generateByteCubesIndexBuffer(int cnt) {
        var buffer = new MemoryBuffer((long) cnt *6*2*3);
        long ptr = buffer.address;
        MemoryUtil.memSet(ptr, 0, buffer.size);

        for (int i = 0; i < cnt; i++) {
            int j = i*8;

            MemoryUtil.memPutByte(ptr++, (byte) (0+j));
            MemoryUtil.memPutByte(ptr++, (byte) (1+j));
            MemoryUtil.memPutByte(ptr++, (byte) (2+j));
            MemoryUtil.memPutByte(ptr++, (byte) (3+j));
            MemoryUtil.memPutByte(ptr++, (byte) (2+j));
            MemoryUtil.memPutByte(ptr++, (byte) (1+j));

            MemoryUtil.memPutByte(ptr++, (byte) (6+j));
            MemoryUtil.memPutByte(ptr++, (byte) (5+j));
            MemoryUtil.memPutByte(ptr++, (byte) (4+j));
            MemoryUtil.memPutByte(ptr++, (byte) (5+j));
            MemoryUtil.memPutByte(ptr++, (byte) (6+j));
            MemoryUtil.memPutByte(ptr++, (byte) (7+j));

            MemoryUtil.memPutByte(ptr++, (byte) (0+j));
            MemoryUtil.memPutByte(ptr++, (byte) (4+j));
            MemoryUtil.memPutByte(ptr++, (byte) (1+j));
            MemoryUtil.memPutByte(ptr++, (byte) (5+j));
            MemoryUtil.memPutByte(ptr++, (byte) (1+j));
            MemoryUtil.memPutByte(ptr++, (byte) (4+j));

            MemoryUtil.memPutByte(ptr++, (byte) (3+j));
            MemoryUtil.memPutByte(ptr++, (byte) (6+j));
            MemoryUtil.memPutByte(ptr++, (byte) (2+j));
            MemoryUtil.memPutByte(ptr++, (byte) (6+j));
            MemoryUtil.memPutByte(ptr++, (byte) (3+j));
            MemoryUtil.memPutByte(ptr++, (byte) (7+j));

            MemoryUtil.memPutByte(ptr++, (byte) (2+j));
            MemoryUtil.memPutByte(ptr++, (byte) (4+j));
            MemoryUtil.memPutByte(ptr++, (byte) (0+j));
            MemoryUtil.memPutByte(ptr++, (byte) (4+j));
            MemoryUtil.memPutByte(ptr++, (byte) (2+j));
            MemoryUtil.memPutByte(ptr++, (byte) (6+j));

            MemoryUtil.memPutByte(ptr++, (byte) (1+j));
            MemoryUtil.memPutByte(ptr++, (byte) (5+j));
            MemoryUtil.memPutByte(ptr++, (byte) (3+j));
            MemoryUtil.memPutByte(ptr++, (byte) (7+j));
            MemoryUtil.memPutByte(ptr++, (byte) (3+j));
            MemoryUtil.memPutByte(ptr++, (byte) (5+j));
        }

        return buffer;
    }

    public static MemoryBuffer generateQuadIndicesByte(int quadCount) {
        if ((quadCount*4) >= 1<<8) {
            throw new IllegalArgumentException("Quad count to large");
        }
        MemoryBuffer buffer = new MemoryBuffer(quadCount * 6L);
        long ptr = buffer.address;
        for(int i = 0; i < quadCount*4; i += 4) {
            MemoryUtil.memPutByte(ptr + (0), (byte) (i + 1));
            MemoryUtil.memPutByte(ptr + (1), (byte) (i + 2));
            MemoryUtil.memPutByte(ptr + (2), (byte) (i + 0));
            MemoryUtil.memPutByte(ptr + (3), (byte) (i + 1));
            MemoryUtil.memPutByte(ptr + (4), (byte) (i + 3));
            MemoryUtil.memPutByte(ptr + (5), (byte) (i + 2));

            ptr += 6;
        }

        return buffer;
    }
    public static MemoryBuffer generateQuadIndicesShort(int quadCount) {
        if ((quadCount*4) >= 1<<16) {
            throw new IllegalArgumentException("Quad count to large");
        }
        MemoryBuffer buffer = new MemoryBuffer(quadCount * 6L * 2);
        long ptr = buffer.address;
        for(int i = 0; i < quadCount*4; i += 4) {
            MemoryUtil.memPutShort(ptr + (0*2), (short) (i + 1));
            MemoryUtil.memPutShort(ptr + (1*2), (short) (i + 2));
            MemoryUtil.memPutShort(ptr + (2*2), (short) (i + 0));
            MemoryUtil.memPutShort(ptr + (3*2), (short) (i + 1));
            MemoryUtil.memPutShort(ptr + (4*2), (short) (i + 3));
            MemoryUtil.memPutShort(ptr + (5*2), (short) (i + 2));

            ptr += 6 * 2;
        }

        return buffer;
    }

    public static MemoryBuffer generateQuadIndicesInt(int quadCount) {
        MemoryBuffer buffer = new MemoryBuffer(quadCount * 6L * 2);
        long ptr = buffer.address;
        for(int i = 0; i < quadCount*4; i += 4) {
            MemoryUtil.memPutInt(ptr + (0*4), i);
            MemoryUtil.memPutInt(ptr + (1*4), (i + 1));
            MemoryUtil.memPutInt(ptr + (2*4), (i + 2));
            MemoryUtil.memPutInt(ptr + (3*4), (i + 1));
            MemoryUtil.memPutInt(ptr + (4*4), (i + 3));
            MemoryUtil.memPutInt(ptr + (5*4), (i + 2));
            ptr += 6 * 4;
        }
        return buffer;
    }

    public int id() {
        return this.indexBuffer != null ? this.indexBuffer.id : 0;
    }

    public synchronized me.cortex.voxy.client.core.backend.vk.VkVoxyBuffer vkBuffer() {
        if (this.vkIndexBuffer == null && me.cortex.voxy.client.core.backend.vk.VkVoxyDevice.isVulkanActive()) {
            initVkBuffer();
        }
        return this.vkIndexBuffer;
    }
}
