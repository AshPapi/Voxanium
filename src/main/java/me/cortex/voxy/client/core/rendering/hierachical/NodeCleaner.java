package me.cortex.voxy.client.core.rendering.hierachical;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import me.cortex.voxy.client.core.backend.IBufferHolder;
import me.cortex.voxy.client.core.backend.vk.VkVoxyBuffer;
import me.cortex.voxy.client.core.backend.vk.VkVoxyDevice;
import me.cortex.voxy.client.core.backend.vk.VkVoxyPipeline;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.shader.AutoBindingShader;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.PrintfDebugUtil;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import org.lwjgl.opengl.ARBDirectStateAccess;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkMemoryBarrier;

import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL30C.glBindBufferRange;
import static org.lwjgl.opengl.GL42C.glMemoryBarrier;
import static org.lwjgl.opengl.GL43C.*;

public class NodeCleaner {


    private static final int SORTING_WORKER_SIZE = 64;
    private static final int WORK_PER_THREAD = 8;
    static final int OUTPUT_COUNT = 256;


    private final AutoBindingShader sorter = Shader.makeAuto(PrintfDebugUtil.PRINTF_processor)
            .define("WORK_SIZE", SORTING_WORKER_SIZE)
            .define("ELEMS_PER_THREAD", WORK_PER_THREAD)
            .define("OUTPUT_SIZE", OUTPUT_COUNT)
            .define("VISIBILITY_BUFFER_BINDING", 1)
            .define("OUTPUT_BUFFER_BINDING", 2)
            .define("NODE_DATA_BINDING", 3)
            .add(ShaderType.COMPUTE, "voxy:lod/hierarchical/cleaner/sort_visibility.comp")
            .compile();

    private final AutoBindingShader resultTransformer = Shader.makeAuto()
            .define("OUTPUT_SIZE", OUTPUT_COUNT)
            .define("MIN_ID_BUFFER_BINDING", 0)
            .define("NODE_BUFFER_BINDING", 1)
            .define("OUTPUT_BUFFER_BINDING", 2)
            .define("VISIBILITY_BUFFER_BINDING", 3)
            .add(ShaderType.COMPUTE, "voxy:lod/hierarchical/cleaner/result_transformer.comp")
            .compile();

    private final AutoBindingShader batchClear = Shader.makeAuto()
            .define("VISIBILITY_BUFFER_BINDING", 0)
            .define("LIST_BUFFER_BINDING", 1)
            .add(ShaderType.COMPUTE, "voxy:lod/hierarchical/cleaner/batch_visibility_set.comp")
            .compile();


    final GlBuffer visibilityBuffer;
    public me.cortex.voxy.client.core.backend.vk.VkVoxyBuffer vkVisibilityBuffer;
    private VkVoxyPipeline vkSorter;
    private VkVoxyPipeline vkResultTransformer;
    private VkVoxyBuffer vkOutputBuffer;
    private final GlBuffer outputBuffer = new GlBuffer(OUTPUT_COUNT*4+OUTPUT_COUNT*8);

    private final AsyncNodeManager nodeManager;
    public int visibilityId = 0;


    public NodeCleaner(AsyncNodeManager nodeManager) {
        this.nodeManager = nodeManager;
        this.visibilityBuffer = new GlBuffer(nodeManager.maxNodeCount*4L).zero();
        this.visibilityBuffer.fill(-1);
        if (me.cortex.voxy.client.core.backend.vk.VkVoxyDevice.isVulkanActive()) {
            var dev = me.cortex.voxy.client.core.backend.vk.VkVoxyDevice.getInstance();
            this.vkVisibilityBuffer = me.cortex.voxy.client.core.backend.vk.VkVoxyBuffer.createDeviceLocal(dev,
                    nodeManager.maxNodeCount * 4L,
                    org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            var cmd = dev.allocateCommandBuffer();
            dev.beginSingleTimeCommands(cmd);
            org.lwjgl.vulkan.VK12.vkCmdFillBuffer(cmd, this.vkVisibilityBuffer.vkBuffer(), 0, this.vkVisibilityBuffer.size(), -1);
            dev.endAndSubmitSingleTimeCommands(cmd, dev.graphicsQueue());
            dev.freeCommandBuffer(cmd);
            this.createVulkanCleaner(dev);
        }

        this.batchClear
                .ssbo("VISIBILITY_BUFFER_BINDING", this.visibilityBuffer);

        this.sorter
                .ssbo("VISIBILITY_BUFFER_BINDING", this.visibilityBuffer)
                .ssbo("OUTPUT_BUFFER_BINDING", this.outputBuffer);

    }


    public void tick(me.cortex.voxy.client.core.backend.IBufferHolder nodeDataBuffer) {
        this.visibilityId++;
        if (this.vkVisibilityBuffer != null) {
            if (this.shouldCleanGeometry()) {
                this.cleanGeometryVulkan(nodeDataBuffer);
            }
            return;
        }
        if (this.shouldCleanGeometry()) {
            this.outputBuffer.fill(this.nodeManager.maxNodeCount - 2);

            this.sorter.bind();
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, nodeDataBuffer.id());

            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            glDispatchCompute((this.nodeManager.getCurrentMaxNodeId() + (SORTING_WORKER_SIZE*WORK_PER_THREAD) - 1) / (SORTING_WORKER_SIZE*WORK_PER_THREAD), 1, 1);

            this.resultTransformer.bind();
            glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0, this.outputBuffer.id, 0, 4 * OUTPUT_COUNT);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, nodeDataBuffer.id());
            glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 2, this.outputBuffer.id, 4 * OUTPUT_COUNT, 8 * OUTPUT_COUNT);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, this.visibilityBuffer.id);
            glUniform1ui(0, this.visibilityId);

            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            glDispatchCompute(1, 1, 1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

            DownloadStream.INSTANCE.download(this.outputBuffer, 4 * OUTPUT_COUNT, 8 * OUTPUT_COUNT,
                    buffer -> this.nodeManager.submitRemoveBatch(buffer.copy())
            );
        }
    }

    private void createVulkanCleaner(VkVoxyDevice dev) {
        this.vkOutputBuffer = VkVoxyBuffer.createDeviceLocal(dev, OUTPUT_COUNT*4L+OUTPUT_COUNT*8L, VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);

        Map<String, String> sortDefines = new HashMap<>();
        sortDefines.put("WORK_SIZE", String.valueOf(SORTING_WORKER_SIZE));
        sortDefines.put("ELEMS_PER_THREAD", String.valueOf(WORK_PER_THREAD));
        sortDefines.put("OUTPUT_SIZE", String.valueOf(OUTPUT_COUNT));
        sortDefines.put("VISIBILITY_BUFFER_BINDING", "1");
        sortDefines.put("OUTPUT_BUFFER_BINDING", "2");
        sortDefines.put("NODE_DATA_BINDING", "3");
        String sortSrc = ShaderLoader.parse("voxy:lod/hierarchical/cleaner/sort_visibility.comp").replace("printf", "//printf");
        long sortModule = dev.compiler().compileShaderModule(dev.vkDevice(), Shaderc.shaderc_compute_shader, "sort_visibility.comp",
                sortSrc, sortDefines);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(3, stack);
            for (int i = 0; i < 3; i++) {
                bindings.get(i).binding(i + 1).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            this.vkSorter = VkVoxyPipeline.createCompute(dev, sortModule, bindings);
        } finally {
            VK12.vkDestroyShaderModule(dev.vkDevice(), sortModule, null);
        }

        Map<String, String> transformDefines = new HashMap<>();
        transformDefines.put("OUTPUT_SIZE", String.valueOf(OUTPUT_COUNT));
        transformDefines.put("MIN_ID_BUFFER_BINDING", "0");
        transformDefines.put("NODE_BUFFER_BINDING", "1");
        transformDefines.put("OUTPUT_BUFFER_BINDING", "2");
        transformDefines.put("VISIBILITY_BUFFER_BINDING", "3");
        long transformModule = dev.compiler().compileShaderModule(dev.vkDevice(), Shaderc.shaderc_compute_shader, "result_transformer.comp",
                ShaderLoader.parse("voxy:lod/hierarchical/cleaner/result_transformer.comp"), transformDefines);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(4, stack);
            for (int i = 0; i < 4; i++) {
                bindings.get(i).binding(i).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            this.vkResultTransformer = VkVoxyPipeline.createCompute(dev, transformModule, bindings, 4);
        } finally {
            VK12.vkDestroyShaderModule(dev.vkDevice(), transformModule, null);
        }
    }

    private void cleanGeometryVulkan(me.cortex.voxy.client.core.backend.IBufferHolder nodeDataBuffer) {
        var dev = VkVoxyDevice.getInstance();
        VkCommandBuffer cmd = dev.allocateCommandBuffer();
        dev.beginSingleTimeCommands(cmd);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack).sType$Default();
            barrier.srcAccessMask(VK12.VK_ACCESS_MEMORY_WRITE_BIT | VK12.VK_ACCESS_MEMORY_READ_BIT);
            barrier.dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT | VK12.VK_ACCESS_TRANSFER_WRITE_BIT);
            VK12.vkCmdPipelineBarrier(cmd, VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, barrier, null, null);

            VK12.vkCmdFillBuffer(cmd, this.vkOutputBuffer.vkBuffer(), 0, this.vkOutputBuffer.size(), this.nodeManager.maxNodeCount - 2);
            barrier.srcAccessMask(VK12.VK_ACCESS_TRANSFER_WRITE_BIT);
            barrier.dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
            VK12.vkCmdPipelineBarrier(cmd, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, barrier, null, null);

            this.vkSorter.bind(cmd);
            this.vkSorter.pushStorageBuffer(cmd, 1, this.vkVisibilityBuffer.vkBuffer(), 0, this.vkVisibilityBuffer.size());
            this.vkSorter.pushStorageBuffer(cmd, 2, this.vkOutputBuffer.vkBuffer(), 0, 4L * OUTPUT_COUNT);
            this.vkSorter.pushStorageBuffer(cmd, 3, nodeDataBuffer.rawHandle(), 0, nodeDataBuffer.size());
            this.vkSorter.dispatch(cmd, (this.nodeManager.getCurrentMaxNodeId() + (SORTING_WORKER_SIZE*WORK_PER_THREAD) - 1) / (SORTING_WORKER_SIZE*WORK_PER_THREAD), 1, 1);

            barrier.srcAccessMask(VK12.VK_ACCESS_SHADER_WRITE_BIT);
            barrier.dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
            VK12.vkCmdPipelineBarrier(cmd, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, barrier, null, null);

            this.vkResultTransformer.bind(cmd);
            this.vkResultTransformer.pushStorageBuffer(cmd, 0, this.vkOutputBuffer.vkBuffer(), 0, 4L * OUTPUT_COUNT);
            this.vkResultTransformer.pushStorageBuffer(cmd, 1, nodeDataBuffer.rawHandle(), 0, nodeDataBuffer.size());
            this.vkResultTransformer.pushStorageBuffer(cmd, 2, this.vkOutputBuffer.vkBuffer(), 4L * OUTPUT_COUNT, 8L * OUTPUT_COUNT);
            this.vkResultTransformer.pushStorageBuffer(cmd, 3, this.vkVisibilityBuffer.vkBuffer(), 0, this.vkVisibilityBuffer.size());
            this.vkResultTransformer.pushConstants(cmd, VK12.VK_SHADER_STAGE_COMPUTE_BIT, 0, stack.malloc(4).putInt(0, this.visibilityId));
            this.vkResultTransformer.dispatch(cmd, 1, 1, 1);

            barrier.srcAccessMask(VK12.VK_ACCESS_SHADER_WRITE_BIT);
            barrier.dstAccessMask(VK12.VK_ACCESS_MEMORY_READ_BIT | VK12.VK_ACCESS_MEMORY_WRITE_BIT);
            VK12.vkCmdPipelineBarrier(cmd, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, barrier, null, null);
        }
        dev.endAndSubmitSingleTimeCommands(cmd, dev.graphicsQueue());
        dev.freeCommandBuffer(cmd);

        DownloadStream.INSTANCE.download(this.vkOutputBuffer, 4 * OUTPUT_COUNT, 8 * OUTPUT_COUNT,
                buffer -> this.nodeManager.submitRemoveBatch(buffer.copy())
        );
    }

    private boolean shouldCleanGeometry() {
        if (false) {
            long used = this.nodeManager.getUsedGeometryCapacity();
            return 3 < ((double) used) / ((double) (this.nodeManager.getGeometryCapacity() - used));
        } else {
            long remaining = this.nodeManager.getGeometryCapacity() - this.nodeManager.getUsedGeometryCapacity();
            return remaining < 256_000_000;
        }
    }

    public void updateIds(IntOpenHashSet collection) {
        if (!collection.isEmpty() && this.vkVisibilityBuffer != null) {
            this.updateIdsVulkan(collection);
            return;
        }
        if (!collection.isEmpty()) {
            int count = collection.size();
            long addr = UploadStream.INSTANCE.rawUploadAddress(count*4);

            long ptr = UploadStream.INSTANCE.getBaseAddress() + addr;
            var iter = collection.iterator();
            while (iter.hasNext()) {
                MemoryUtil.memPutInt(ptr, iter.nextInt()); ptr+=4;
            }
            UploadStream.INSTANCE.commit();

            this.batchClear.bind();
            glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 1, UploadStream.INSTANCE.getRawBufferId(), addr, UploadStream.alignUpAlloc(count*4));
            glUniform1ui(0, count);
            glUniform1ui(1, this.visibilityId);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            glDispatchCompute((count+127)/128, 1, 1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        }
    }

    private void updateIdsVulkan(IntOpenHashSet collection) {
        var values = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap(collection.size());
        var iter = collection.iterator();
        while (iter.hasNext()) {
            int pos = iter.nextInt();
            values.put(pos & 0x7fffffff, (pos & (1 << 31)) == 0 ? -1 : this.visibilityId);
        }

        long addr = UploadStream.INSTANCE.rawUploadAddress(values.size() * 4);
        long ptr = UploadStream.INSTANCE.getBaseAddress() + addr;
        int i = 0;
        for (var entry : values.int2IntEntrySet()) {
            MemoryUtil.memPutInt(ptr + i * 4L, entry.getIntValue());
            UploadStream.INSTANCE.copyFromStream(this.vkVisibilityBuffer, addr + i * 4L, entry.getIntKey() * 4L, 4);
            i++;
        }
        UploadStream.INSTANCE.commit();
    }

    private void dumpDebugData() {
        int[] outData = new int[OUTPUT_COUNT*3];
        ARBDirectStateAccess.glGetNamedBufferSubData(this.outputBuffer.id, 0, outData);
        for(int i =0;i < OUTPUT_COUNT; i++) {
            System.out.println(outData[i]);
        }
        int[] visData = new int[(int) (this.visibilityBuffer.size()/4)];
        ARBDirectStateAccess.glGetNamedBufferSubData(this.visibilityBuffer.id, 0, visData);
        int a = 0;
    }

    public me.cortex.voxy.client.core.backend.IBufferHolder getVisibilityBuffer() {
        return this.vkVisibilityBuffer != null ? this.vkVisibilityBuffer : this.visibilityBuffer;
    }

    public void free() {
        if (this.vkVisibilityBuffer != null) {
            this.vkVisibilityBuffer.free();
            this.vkVisibilityBuffer = null;
            this.vkSorter.free();
            this.vkResultTransformer.free();
            this.vkOutputBuffer.free();
        }
        this.sorter.free();
        this.visibilityBuffer.free();
        this.outputBuffer.free();
        this.batchClear.free();
        this.resultTransformer.free();
    }
}
