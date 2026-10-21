package me.cortex.voxy.client.core.backend.vk;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import me.cortex.voxy.client.RenderStatistics;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.backend.IBufferHolder;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VkHierarchicalOcclusionTraverser {
    public static final boolean HIERARCHICAL_SHADER_DEBUG = HierarchicalOcclusionTraverser.HIERARCHICAL_SHADER_DEBUG;

    public static final int MAX_REQUEST_QUEUE_SIZE = HierarchicalOcclusionTraverser.MAX_REQUEST_QUEUE_SIZE;
    public static final int MAX_QUEUE_SIZE = HierarchicalOcclusionTraverser.MAX_QUEUE_SIZE;

    private static final int MAX_ITERATIONS = WorldEngine.MAX_LOD_LAYER + 1;
    private static final int LOCAL_WORK_SIZE_BITS = 5;

    public static final int HIZ_BINDING = 0;
    public static final int SCENE_UNIFORM_BINDING = 1;
    public static final int REQUEST_QUEUE_BINDING = 2;
    public static final int RENDER_QUEUE_BINDING = 3;
    public static final int NODE_DATA_BINDING = 4;
    public static final int NODE_QUEUE_META_BINDING = 5;
    public static final int NODE_QUEUE_SOURCE_BINDING = 6;
    public static final int NODE_QUEUE_SINK_BINDING = 7;
    public static final int RENDER_TRACKER_BINDING = 8;
    public static final int STATISTICS_BUFFER_BINDING = 9;

    private final VkVoxyDevice device;
    private final AsyncNodeManager nodeManager;
    private final NodeCleaner nodeCleaner;
    private final RenderGenerationService meshGen;

    private final VkVoxyBuffer requestBuffer;
    private final VkVoxyBuffer nodeBuffer;
    private final VkVoxyBuffer uniformBuffer;
    private final VkVoxyBuffer statisticsBuffer;
    private final VkVoxyBuffer topNodeIds;
    private final VkVoxyBuffer queueMetaBuffer;
    private final VkVoxyBuffer scratchQueueA;
    private final VkVoxyBuffer scratchQueueB;

    private int topNodeCount;
    private final Int2IntOpenHashMap topNode2idxMapping = new Int2IntOpenHashMap();
    private final int[] idx2topNodeMapping = new int[MAX_QUEUE_SIZE];

    private VkVoxyPipeline traversalPipeline;
    private AbstractRenderPipeline pipeline;

    private static final int DOWNLOAD_FRAMES = 3;
    private final DownloadFrame[] downloadFrames;
    private int currentDownloadFrame = 0;

    private final ByteBuffer pushConstantBuffer = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());

    public VkHierarchicalOcclusionTraverser(VkVoxyDevice device, AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, RenderGenerationService meshGen) {
        this.device = device;
        this.nodeCleaner = nodeCleaner;
        this.nodeManager = nodeManager;
        this.meshGen = meshGen;

        this.requestBuffer = VkVoxyBuffer.createDeviceLocal(device,
                MAX_REQUEST_QUEUE_SIZE * 8L + 8L,
                VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT);

        this.nodeBuffer = VkVoxyBuffer.createDeviceLocal(device,
                nodeManager.maxNodeCount * 16L,
                VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT);

        this.uniformBuffer = VkVoxyBuffer.createDeviceLocal(device,
                1024L,
                VK12.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT);

        this.statisticsBuffer = VkVoxyBuffer.createDeviceLocal(device,
                1024L,
                VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT);

        this.topNodeIds = VkVoxyBuffer.createDeviceLocal(device,
                MAX_QUEUE_SIZE * 4L,
                VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT);

        this.queueMetaBuffer = VkVoxyBuffer.createDeviceLocal(device,
                4L * 4L * MAX_ITERATIONS,
                VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT);

        this.scratchQueueA = VkVoxyBuffer.createDeviceLocal(device,
                MAX_QUEUE_SIZE * 4L,
                VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);

        this.scratchQueueB = VkVoxyBuffer.createDeviceLocal(device,
                MAX_QUEUE_SIZE * 4L,
                VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);

        VkCommandBuffer initCmd = device.allocateCommandBuffer();
        device.beginSingleTimeCommands(initCmd);
        VK12.vkCmdFillBuffer(initCmd, this.nodeBuffer.vkBuffer(), 0, this.nodeBuffer.size(), 0xFFFFFFFF);
        VK12.vkCmdFillBuffer(initCmd, this.requestBuffer.vkBuffer(), 0, this.requestBuffer.size(), 0);
        VK12.vkCmdFillBuffer(initCmd, this.uniformBuffer.vkBuffer(), 0, this.uniformBuffer.size(), 0);
        VK12.vkCmdFillBuffer(initCmd, this.statisticsBuffer.vkBuffer(), 0, this.statisticsBuffer.size(), 0);
        VK12.vkCmdFillBuffer(initCmd, this.topNodeIds.vkBuffer(), 0, this.topNodeIds.size(), 0);
        VK12.vkCmdFillBuffer(initCmd, this.queueMetaBuffer.vkBuffer(), 0, this.queueMetaBuffer.size(), 0);
        device.endAndSubmitSingleTimeCommands(initCmd, device.graphicsQueue());
        device.freeCommandBuffer(initCmd);

        this.topNode2idxMapping.defaultReturnValue(-1);
        this.nodeManager.setTLNAddRemoveCallbacks(this::addTLN, this::remTLN);

        this.downloadFrames = new DownloadFrame[DOWNLOAD_FRAMES];
        for (int i = 0; i < DOWNLOAD_FRAMES; i++) {
            this.downloadFrames[i] = new DownloadFrame(device, this.requestBuffer.size(), this.statisticsBuffer.size());
        }
    }

    public void lateStageCompile(AbstractRenderPipeline pipeline) {
        this.pipeline = pipeline;
        String taa = pipeline.taaFunction("getTAA");
        String src = ShaderLoader.parse("voxy:lod/hierarchical/traversal_dev.comp");
        if (taa != null) {
            src += "\n\n\n" + taa;
        }

        if (!HIERARCHICAL_SHADER_DEBUG) {
            src = src.replace("printf", "//printf");
        }

        Map<String, String> defines = new HashMap<>();
        defines.put("MAX_ITERATIONS", String.valueOf(MAX_ITERATIONS));
        defines.put("LOCAL_SIZE_BITS", String.valueOf(LOCAL_WORK_SIZE_BITS));
        defines.put("MAX_REQUEST_QUEUE_SIZE", String.valueOf(MAX_REQUEST_QUEUE_SIZE));

        defines.put("HIZ_BINDING", String.valueOf(HIZ_BINDING));
        defines.put("SCENE_UNIFORM_BINDING", String.valueOf(SCENE_UNIFORM_BINDING));
        defines.put("REQUEST_QUEUE_BINDING", String.valueOf(REQUEST_QUEUE_BINDING));
        defines.put("RENDER_QUEUE_BINDING", String.valueOf(RENDER_QUEUE_BINDING));
        defines.put("NODE_DATA_BINDING", String.valueOf(NODE_DATA_BINDING));
        defines.put("NODE_QUEUE_META_BINDING", String.valueOf(NODE_QUEUE_META_BINDING));
        defines.put("NODE_QUEUE_SOURCE_BINDING", String.valueOf(NODE_QUEUE_SOURCE_BINDING));
        defines.put("NODE_QUEUE_SINK_BINDING", String.valueOf(NODE_QUEUE_SINK_BINDING));
        defines.put("RENDER_TRACKER_BINDING", String.valueOf(RENDER_TRACKER_BINDING));
        defines.put("STATISTICS_BUFFER_BINDING", String.valueOf(STATISTICS_BUFFER_BINDING));

        if (RenderStatistics.enabled) {
            defines.put("HAS_STATISTICS", "1");
        }

        if (HIERARCHICAL_SHADER_DEBUG) {
            defines.put("DEBUG", "1");
        }

        if (pipeline.properties.isReverseZ()) {
            defines.put("USE_REVERSE_Z", "1");
        }
        defines.put("USE_ZERO_ONE_DEPTH", "1");

        if (taa != null) {
            defines.put("TAA", "1");
        }

        long shaderModule = this.device.compiler().compileShaderModule(
                this.device.vkDevice(),
                Shaderc.shaderc_compute_shader,
                "traversal_dev.comp",
                src,
                defines);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(10, stack);

            bindings.get(0).binding(HIZ_BINDING)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT);

            bindings.get(1).binding(SCENE_UNIFORM_BINDING)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT);

            int[] storageBindings = new int[] {
                    REQUEST_QUEUE_BINDING,
                    RENDER_QUEUE_BINDING,
                    NODE_DATA_BINDING,
                    NODE_QUEUE_META_BINDING,
                    NODE_QUEUE_SOURCE_BINDING,
                    NODE_QUEUE_SINK_BINDING,
                    RENDER_TRACKER_BINDING,
                    STATISTICS_BUFFER_BINDING
            };
            for (int i = 0; i < storageBindings.length; i++) {
                bindings.get(2 + i).binding(storageBindings[i])
                        .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .descriptorCount(1)
                        .stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT);
            }

            this.traversalPipeline = VkVoxyPipeline.createCompute(this.device, shaderModule, bindings, 4);
        } finally {
            VK12.vkDestroyShaderModule(this.device.vkDevice(), shaderModule, null);
        }
    }

    public void addTLN(int id) {
        int aid = this.topNodeCount++;
        if (this.topNodeCount > this.topNodeIds.size() / 4) {
            throw new IllegalStateException("Top level node count greater than capacity");
        }

        long ptr = UploadStream.INSTANCE.upload(this.topNodeIds, aid * 4L, 4);
        MemoryUtil.memPutInt(ptr, id);

        if (this.topNode2idxMapping.put(id, aid) != -1) {
            throw new IllegalStateException();
        }
        this.idx2topNodeMapping[aid] = id;
    }

    public void remTLN(int id) {
        int idx = this.topNode2idxMapping.remove(id);
        this.topNodeCount--;
        if (idx == -1) {
            throw new IllegalStateException();
        }

        if (idx == this.topNodeCount) {
            return;
        }

        int endTLNId = this.idx2topNodeMapping[this.topNodeCount];
        this.idx2topNodeMapping[idx] = endTLNId;
        if (this.topNode2idxMapping.put(endTLNId, idx) == -1) {
            throw new IllegalStateException();
        }

        long ptr = UploadStream.INSTANCE.upload(this.topNodeIds, idx * 4L, 4);
        MemoryUtil.memPutInt(ptr, endTLNId);
    }

    private static void setFrustum(Viewport<?> viewport, long ptr) {
        for (int i = 0; i < 6; i++) {
            var plane = viewport.frustumPlanes[i];
            plane.getToAddress(ptr);
            ptr += 4 * 4;
        }
    }

    private void uploadUniform(Viewport<?> viewport) {
        long ptr = UploadStream.INSTANCE.upload(this.uniformBuffer, 0, 1024);

        viewport.MVP.getToAddress(ptr);
        ptr += 4 * 4 * 4;

        viewport.section.getToAddress(ptr);
        ptr += 4 * 3;

        int packedHiZ = viewport.vkHiZBuffer != null ? viewport.vkHiZBuffer.getPackedLevels() : viewport.hiZBuffer.getPackedLevels();
        MemoryUtil.memPutInt(ptr, packedHiZ);
        ptr += 4;

        viewport.innerTranslation.getToAddress(ptr);
        ptr += 4 * 3;

        final float screenspaceAreaDecreasingSize = VoxyConfig.CONFIG.subDivisionSize * VoxyConfig.CONFIG.subDivisionSize;
        MemoryUtil.memPutFloat(ptr, (float) (screenspaceAreaDecreasingSize) / (viewport.width * viewport.height));
        ptr += 4;

        setFrustum(viewport, ptr);
        ptr += 4 * 4 * 6;

        MemoryUtil.memPutInt(ptr, (int) (viewport.getRenderList().size() / 4 - 1));
        ptr += 4;

        MemoryUtil.memPutInt(ptr, this.nodeCleaner.visibilityId);
        ptr += 4;

        {
            final double TARGET_COUNT = 4000;
            double iFillness = Math.max(0, (TARGET_COUNT - this.meshGen.getTaskCount()) / TARGET_COUNT);
            iFillness = Math.pow(iFillness, 2);
            final int requestSize = (int) Math.ceil(iFillness * MAX_REQUEST_QUEUE_SIZE);
            MemoryUtil.memPutInt(ptr, Math.max(0, Math.min(MAX_REQUEST_QUEUE_SIZE, requestSize)));
            ptr += 4;
        }

        MemoryUtil.memPutFloat(ptr, (float) Math.pow(VoxyConfig.CONFIG.sectionRenderDistance * 16 * 32, 2));
    }

    public void doTraversal(Viewport<?> viewport) {
        if (this.traversalPipeline == null || viewport.vkHiZBuffer == null || viewport.vkHiZBuffer.getTexture() == null) {
            return;
        }

        this.pollFinishedDownloads();

        this.uploadUniform(viewport);

        int firstDispatchSize = (this.topNodeCount + (1 << LOCAL_WORK_SIZE_BITS) - 1) >> LOCAL_WORK_SIZE_BITS;
        long metaPtr = UploadStream.INSTANCE.upload(this.queueMetaBuffer, 0, 16L * MAX_ITERATIONS);
        MemoryUtil.memPutInt(metaPtr + 0, firstDispatchSize);
        MemoryUtil.memPutInt(metaPtr + 4, 1);
        MemoryUtil.memPutInt(metaPtr + 8, 1);
        MemoryUtil.memPutInt(metaPtr + 12, this.topNodeCount);
        for (int i = 1; i < MAX_ITERATIONS; i++) {
            MemoryUtil.memPutInt(metaPtr + (i * 16) + 0, 0);
            MemoryUtil.memPutInt(metaPtr + (i * 16) + 4, 1);
            MemoryUtil.memPutInt(metaPtr + (i * 16) + 8, 1);
            MemoryUtil.memPutInt(metaPtr + (i * 16) + 12, 0);
        }
        UploadStream.INSTANCE.commit();

        DownloadFrame frame = this.downloadFrames[this.currentDownloadFrame];
        if (frame.inFlight) {
            VK12.vkWaitForFences(this.device.vkDevice(), frame.fence, true, 20_000_000L);
            this.processDownloadFrame(frame);
        }

        VkCommandBuffer cmd = frame.cmd;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack).sType$Default();
            beginInfo.flags(VK12.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            VK12.vkBeginCommandBuffer(cmd, beginInfo);

            VK12.vkCmdFillBuffer(cmd, viewport.getRenderList().rawHandle(), 0, 4, 0);

            if (RenderStatistics.enabled) {
                this.statisticsBuffer.zero(cmd);
            }

            this.traversalPipeline.bind(cmd);

            this.traversalPipeline.pushImage(cmd, HIZ_BINDING,
                    viewport.vkHiZBuffer.getTexture().vkImageView(),
                    viewport.vkHiZBuffer.getSampler(),
                    VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

            this.traversalPipeline.pushUniformBuffer(cmd, SCENE_UNIFORM_BINDING, this.uniformBuffer.vkBuffer(), 0, 1024);
            this.traversalPipeline.pushStorageBuffer(cmd, REQUEST_QUEUE_BINDING, this.requestBuffer.vkBuffer(), 0, this.requestBuffer.size());
            this.traversalPipeline.pushStorageBuffer(cmd, RENDER_QUEUE_BINDING, viewport.getRenderList().rawHandle(), 0, viewport.getRenderList().size());
            this.traversalPipeline.pushStorageBuffer(cmd, NODE_DATA_BINDING, this.nodeBuffer.vkBuffer(), 0, this.nodeBuffer.size());
            this.traversalPipeline.pushStorageBuffer(cmd, NODE_QUEUE_META_BINDING, this.queueMetaBuffer.vkBuffer(), 0, this.queueMetaBuffer.size());

            IBufferHolder visBuffer = this.nodeCleaner.getVisibilityBuffer();
            this.traversalPipeline.pushStorageBuffer(cmd, RENDER_TRACKER_BINDING, visBuffer.rawHandle(), 0, visBuffer.size());

            if (RenderStatistics.enabled) {
                this.traversalPipeline.pushStorageBuffer(cmd, STATISTICS_BUFFER_BINDING, this.statisticsBuffer.vkBuffer(), 0, this.statisticsBuffer.size());
            }

            VkMemoryBarrier.Buffer transferBarrier = VkMemoryBarrier.calloc(1, stack).sType$Default();
            transferBarrier.srcAccessMask(VK12.VK_ACCESS_TRANSFER_WRITE_BIT);
            transferBarrier.dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT | VK12.VK_ACCESS_INDIRECT_COMMAND_READ_BIT);
            VK12.vkCmdPipelineBarrier(cmd,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK12.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,
                    0, transferBarrier, null, null);

            this.recordComputeIterations(cmd, firstDispatchSize);

            VkMemoryBarrier.Buffer computeDoneBarrier = VkMemoryBarrier.calloc(1, stack).sType$Default();
            computeDoneBarrier.srcAccessMask(VK12.VK_ACCESS_SHADER_WRITE_BIT);
            computeDoneBarrier.dstAccessMask(VK12.VK_ACCESS_TRANSFER_READ_BIT | VK12.VK_ACCESS_TRANSFER_WRITE_BIT);
            VK12.vkCmdPipelineBarrier(cmd,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0, computeDoneBarrier, null, null);

            VkBufferCopy.Buffer copyReq = VkBufferCopy.calloc(1, stack);
            copyReq.srcOffset(0);
            copyReq.dstOffset(0);
            copyReq.size(this.requestBuffer.size());
            VK12.vkCmdCopyBuffer(cmd, this.requestBuffer.vkBuffer(), frame.requestStaging.vkBuffer(), copyReq);

            if (RenderStatistics.enabled) {
                VkBufferCopy.Buffer copyStats = VkBufferCopy.calloc(1, stack);
                copyStats.srcOffset(0);
                copyStats.dstOffset(0);
                copyStats.size(1024);
                VK12.vkCmdCopyBuffer(cmd, this.statisticsBuffer.vkBuffer(), frame.statisticsStaging.vkBuffer(), copyStats);
            }

            VkMemoryBarrier.Buffer copyDoneBarrier = VkMemoryBarrier.calloc(1, stack).sType$Default();
            copyDoneBarrier.srcAccessMask(VK12.VK_ACCESS_TRANSFER_READ_BIT);
            copyDoneBarrier.dstAccessMask(VK12.VK_ACCESS_TRANSFER_WRITE_BIT);
            VK12.vkCmdPipelineBarrier(cmd,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0, copyDoneBarrier, null, null);

            VK12.vkCmdFillBuffer(cmd, this.requestBuffer.vkBuffer(), 0, 8, 0);

            VkMemoryBarrier.Buffer postTransferBarrier = VkMemoryBarrier.calloc(1, stack).sType$Default();
            postTransferBarrier.srcAccessMask(VK12.VK_ACCESS_TRANSFER_WRITE_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
            postTransferBarrier.dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK12.VK_ACCESS_HOST_READ_BIT);
            VK12.vkCmdPipelineBarrier(cmd,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT | VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT | VK12.VK_PIPELINE_STAGE_HOST_BIT,
                    0, postTransferBarrier, null, null);

            VK12.vkEndCommandBuffer(cmd);

            VK12.vkResetFences(this.device.vkDevice(), frame.fence);

            VkSubmitInfo.Buffer submitInfo = VkSubmitInfo.calloc(1, stack).sType$Default();
            submitInfo.pCommandBuffers(stack.pointers(cmd));
            int res = VK12.vkQueueSubmit(this.device.graphicsQueue().vkQueue(), submitInfo, frame.fence);
            if (res != VK12.VK_SUCCESS) {
                throw new RuntimeException("vkQueueSubmit failed for traversal: " + res);
            }
            frame.inFlight = true;
            this.currentDownloadFrame = (this.currentDownloadFrame + 1) % DOWNLOAD_FRAMES;
        }
    }

    private void recordComputeIterations(VkCommandBuffer cmd, int firstDispatchSize) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer pc = stack.calloc(4);

            pc.putInt(0, 0);
            this.traversalPipeline.pushConstants(cmd, VK12.VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
            this.traversalPipeline.pushStorageBuffer(cmd, NODE_QUEUE_SOURCE_BINDING, this.topNodeIds.vkBuffer(), 0, this.topNodeIds.size());
            this.traversalPipeline.pushStorageBuffer(cmd, NODE_QUEUE_SINK_BINDING, this.scratchQueueB.vkBuffer(), 0, this.scratchQueueB.size());

            if (firstDispatchSize > 0) {
                this.traversalPipeline.dispatch(cmd, firstDispatchSize, 1, 1);
            }

            VkMemoryBarrier.Buffer iterBarrier = VkMemoryBarrier.calloc(1, stack).sType$Default();
            iterBarrier.srcAccessMask(VK12.VK_ACCESS_SHADER_WRITE_BIT);
            iterBarrier.dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT | VK12.VK_ACCESS_INDIRECT_COMMAND_READ_BIT);

            for (int iter = 1; iter < MAX_ITERATIONS; iter++) {
                VK12.vkCmdPipelineBarrier(cmd,
                        VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK12.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,
                        0, iterBarrier, null, null);

                pc.putInt(0, iter);
                this.traversalPipeline.pushConstants(cmd, VK12.VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);

                VkVoxyBuffer src = (iter & 1) == 0 ? this.scratchQueueA : this.scratchQueueB;
                VkVoxyBuffer dst = (iter & 1) == 0 ? this.scratchQueueB : this.scratchQueueA;
                this.traversalPipeline.pushStorageBuffer(cmd, NODE_QUEUE_SOURCE_BINDING, src.vkBuffer(), 0, src.size());
                this.traversalPipeline.pushStorageBuffer(cmd, NODE_QUEUE_SINK_BINDING, dst.vkBuffer(), 0, dst.size());

                this.traversalPipeline.dispatchIndirect(cmd, this.queueMetaBuffer.vkBuffer(), iter * 16L);
            }

            VK12.vkCmdPipelineBarrier(cmd,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0, iterBarrier, null, null);
        }
    }

    private void pollFinishedDownloads() {
        for (int i = 0; i < DOWNLOAD_FRAMES; i++) {
            DownloadFrame frame = this.downloadFrames[i];
            if (frame.inFlight) {
                int status = VK12.vkGetFenceStatus(this.device.vkDevice(), frame.fence);
                if (status == VK12.VK_SUCCESS) {
                    this.processDownloadFrame(frame);
                }
            }
        }
    }

    private void processDownloadFrame(DownloadFrame frame) {
        frame.requestStaging.invalidate(0, this.requestBuffer.size());
        this.forwardDownloadResult(frame.requestStaging.mappedAddress(), this.requestBuffer.size());

        if (RenderStatistics.enabled) {
            frame.statisticsStaging.invalidate(0, 1024);
            long addr = frame.statisticsStaging.mappedAddress();
            for (int i = 0; i < MAX_ITERATIONS; i++) {
                RenderStatistics.hierarchicalTraversalCounts[i] = MemoryUtil.memGetInt(addr + i * 4L);
            }
            for (int i = 0; i < MAX_ITERATIONS; i++) {
                RenderStatistics.hierarchicalRenderSections[i] = MemoryUtil.memGetInt(addr + (MAX_ITERATIONS + i) * 4L);
            }
        }

        VK12.vkResetFences(this.device.vkDevice(), frame.fence);
        frame.inFlight = false;
    }

    private void forwardDownloadResult(long ptr, long size) {
        int count = MemoryUtil.memGetInt(ptr);
        ptr += 8;
        if (count < 0 || count > 50000) {
            Logger.error(new IllegalStateException("Count unexpected extreme value: " + count + " things may get weird"));
            return;
        }
        if (count > (this.requestBuffer.size() >> 3) - 1) {
            count = (int) ((this.requestBuffer.size() >> 3) - 1);
        }
        if (count != 0) {
            var buffer = new MemoryBuffer(count * 8L + 8).cpyFrom(ptr - 8);
            MemoryUtil.memPutInt(buffer.address, count);
            this.nodeManager.submitRequestBatch(buffer);
        }
    }

    public VkVoxyBuffer getNodeBuffer() {
        return this.nodeBuffer;
    }

    public VkVoxyBuffer getRequestBuffer() {
        return this.requestBuffer;
    }

    public VkVoxyBuffer getUniformBuffer() {
        return this.uniformBuffer;
    }

    public VkVoxyBuffer getStatisticsBuffer() {
        return this.statisticsBuffer;
    }

    public VkVoxyBuffer getTopNodeIds() {
        return this.topNodeIds;
    }

    public VkVoxyBuffer getQueueMetaBuffer() {
        return this.queueMetaBuffer;
    }

    public VkVoxyBuffer getScratchQueueA() {
        return this.scratchQueueA;
    }

    public VkVoxyBuffer getScratchQueueB() {
        return this.scratchQueueB;
    }

    public void addDebug(List<String> debug) {
        if (this.topNodeCount > this.idx2topNodeMapping.length / 2) {
            debug.add("TLN#: " + this.topNodeCount);
        }
    }

    public void free() {
        for (DownloadFrame frame : this.downloadFrames) {
            if (frame.inFlight) {
                VK12.vkWaitForFences(this.device.vkDevice(), frame.fence, true, 100_000_000L);
                frame.inFlight = false;
            }
            frame.free(this.device);
        }

        if (this.traversalPipeline != null) {
            this.traversalPipeline.free();
            this.traversalPipeline = null;
        }

        this.requestBuffer.free();
        this.nodeBuffer.free();
        this.uniformBuffer.free();
        this.statisticsBuffer.free();
        this.queueMetaBuffer.free();
        this.topNodeIds.free();
        this.scratchQueueA.free();
        this.scratchQueueB.free();
    }

    private static class DownloadFrame {
        final VkVoxyBuffer requestStaging;
        final VkVoxyBuffer statisticsStaging;
        final VkCommandBuffer cmd;
        final long fence;
        boolean inFlight = false;

        DownloadFrame(VkVoxyDevice device, long requestSize, long statsSize) {
            this.requestStaging = VkVoxyBuffer.createHostReadMapped(device, requestSize, VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            this.statisticsStaging = VkVoxyBuffer.createHostReadMapped(device, statsSize, VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            this.cmd = device.allocateCommandBuffer();

            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack).sType$Default();
                LongBuffer pFence = stack.callocLong(1);
                int res = VK12.vkCreateFence(device.vkDevice(), fenceInfo, null, pFence);
                if (res != VK12.VK_SUCCESS) {
                    throw new RuntimeException("vkCreateFence failed: " + res);
                }
                this.fence = pFence.get(0);
            }
        }

        void free(VkVoxyDevice device) {
            this.requestStaging.free();
            this.statisticsStaging.free();
            device.freeCommandBuffer(this.cmd);
            VK12.vkDestroyFence(device.vkDevice(), this.fence, null);
        }
    }
}
