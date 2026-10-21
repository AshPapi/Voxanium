package me.cortex.voxy.client.core.rendering.hierachical;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import me.cortex.voxy.client.RenderStatistics;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.shader.AutoBindingShader;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.PrintfDebugUtil;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import org.lwjgl.system.MemoryUtil;

import java.util.List;

import static me.cortex.voxy.client.core.rendering.util.PrintfDebugUtil.PRINTF_processor;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_UNPACK_IMAGE_HEIGHT;
import static org.lwjgl.opengl.GL12.GL_UNPACK_SKIP_IMAGES;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL30C.GL_RED_INTEGER;
import static org.lwjgl.opengl.GL42.glMemoryBarrier;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BARRIER_BIT;
import static org.lwjgl.opengl.GL45.*;

public class HierarchicalOcclusionTraverser {
    public static final boolean HIERARCHICAL_SHADER_DEBUG = System.getProperty("voxy.hierarchicalShaderDebug", "false").equals("true");

    public static final int MAX_REQUEST_QUEUE_SIZE = 50;
    public static final int MAX_QUEUE_SIZE = 200_000;


    private static final int MAX_ITERATIONS = WorldEngine.MAX_LOD_LAYER+1;
    private static final int LOCAL_WORK_SIZE_BITS = 5;

    private final AsyncNodeManager nodeManager;
    private final NodeCleaner nodeCleaner;
    private final RenderGenerationService meshGen;

    private final me.cortex.voxy.client.core.backend.vk.VkHierarchicalOcclusionTraverser vkTraverser;

    private final GlBuffer requestBuffer;

    private final GlBuffer nodeBuffer;
    private final GlBuffer uniformBuffer;
    private final GlBuffer statisticsBuffer;


    private int topNodeCount;
    private final Int2IntOpenHashMap topNode2idxMapping = new Int2IntOpenHashMap();
    private final int[] idx2topNodeMapping = new int[MAX_QUEUE_SIZE];
    private final GlBuffer topNodeIds;
    private final GlBuffer queueMetaBuffer;
    private final GlBuffer scratchQueueA;
    private final GlBuffer scratchQueueB;

    private static int BINDING_COUNTER = 1;
    private static final int SCENE_UNIFORM_BINDING = BINDING_COUNTER++;
    private static final int REQUEST_QUEUE_BINDING = BINDING_COUNTER++;
    private static final int RENDER_QUEUE_BINDING = BINDING_COUNTER++;
    private static final int NODE_DATA_BINDING = BINDING_COUNTER++;
    private static final int NODE_QUEUE_INDEX_BINDING = BINDING_COUNTER++;
    private static final int NODE_QUEUE_META_BINDING = BINDING_COUNTER++;
    private static final int NODE_QUEUE_SOURCE_BINDING = BINDING_COUNTER++;
    private static final int NODE_QUEUE_SINK_BINDING = BINDING_COUNTER++;
    private static final int RENDER_TRACKER_BINDING = BINDING_COUNTER++;
    private static final int STATISTICS_BUFFER_BINDING = BINDING_COUNTER++;

    private final int hizSampler = me.cortex.voxy.client.core.backend.vk.VkVoxyDevice.isVulkanActive() ? 0 : glGenSamplers();

    private AutoBindingShader traversal;

    private AbstractRenderPipeline pipeline;

    public HierarchicalOcclusionTraverser(AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, RenderGenerationService meshGen) {
        this.nodeCleaner = nodeCleaner;
        this.nodeManager = nodeManager;
        this.meshGen = meshGen;

        if (me.cortex.voxy.client.core.backend.vk.VkVoxyDevice.isVulkanActive()) {
            this.vkTraverser = new me.cortex.voxy.client.core.backend.vk.VkHierarchicalOcclusionTraverser(
                    me.cortex.voxy.client.core.backend.vk.VkVoxyDevice.getInstance(), nodeManager, nodeCleaner, meshGen);
            this.requestBuffer = null;
            this.nodeBuffer = null;
            this.uniformBuffer = null;
            this.statisticsBuffer = null;
            this.topNodeIds = null;
            this.queueMetaBuffer = null;
            this.scratchQueueA = null;
            this.scratchQueueB = null;
        } else {
            this.vkTraverser = null;
            this.requestBuffer = new GlBuffer(MAX_REQUEST_QUEUE_SIZE*8L+8).zero();
            this.nodeBuffer = new GlBuffer(nodeManager.maxNodeCount*16L).fill(-1);
            this.uniformBuffer = new GlBuffer(1024).zero();
            this.statisticsBuffer = new GlBuffer(1024).zero();
            this.topNodeIds = new GlBuffer(MAX_QUEUE_SIZE*4).zero();
            this.queueMetaBuffer = new GlBuffer(4*4*MAX_ITERATIONS).zero();
            this.scratchQueueA = new GlBuffer(MAX_QUEUE_SIZE*4).zero();
            this.scratchQueueB = new GlBuffer(MAX_QUEUE_SIZE*4).zero();

            if (this.hizSampler != 0) {
                glSamplerParameteri(this.hizSampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_NEAREST);
                glSamplerParameteri(this.hizSampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
                glSamplerParameteri(this.hizSampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                glSamplerParameteri(this.hizSampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            }

            this.nodeManager.setTLNAddRemoveCallbacks(this::addTLN, this::remTLN);
        }

        this.topNode2idxMapping.defaultReturnValue(-1);
    }

    public void lateStageCompile(AbstractRenderPipeline pipeline) {
        if (this.vkTraverser != null) {
            this.vkTraverser.lateStageCompile(pipeline);
            return;
        }
        String taa = pipeline.taaFunction("getTAA");
        var scr = ShaderLoader.parse("voxy:lod/hierarchical/traversal_dev.comp");
        if (taa != null) {
            scr += "\n\n\n" + taa;
            this.pipeline = pipeline;
        }
        this.traversal = Shader.makeAuto(PRINTF_processor)
            .apply(pipeline.properties::apply)
            .defineIf("DEBUG", HIERARCHICAL_SHADER_DEBUG)
            .define("MAX_ITERATIONS", MAX_ITERATIONS)
            .define("LOCAL_SIZE_BITS", LOCAL_WORK_SIZE_BITS)
            .define("MAX_REQUEST_QUEUE_SIZE", MAX_REQUEST_QUEUE_SIZE)

            .define("HIZ_BINDING", 0)

            .define("SCENE_UNIFORM_BINDING", SCENE_UNIFORM_BINDING)
            .define("REQUEST_QUEUE_BINDING", REQUEST_QUEUE_BINDING)
            .define("RENDER_QUEUE_BINDING", RENDER_QUEUE_BINDING)
            .define("NODE_DATA_BINDING", NODE_DATA_BINDING)

            .define("NODE_QUEUE_INDEX_BINDING", NODE_QUEUE_INDEX_BINDING)
            .define("NODE_QUEUE_META_BINDING", NODE_QUEUE_META_BINDING)
            .define("NODE_QUEUE_SOURCE_BINDING", NODE_QUEUE_SOURCE_BINDING)
            .define("NODE_QUEUE_SINK_BINDING", NODE_QUEUE_SINK_BINDING)

            .define("RENDER_TRACKER_BINDING", RENDER_TRACKER_BINDING)

            .defineIf("HAS_STATISTICS", RenderStatistics.enabled)
            .defineIf("STATISTICS_BUFFER_BINDING", RenderStatistics.enabled, STATISTICS_BUFFER_BINDING)

            .defineIf("TAA", taa != null)

            .addSource(ShaderType.COMPUTE, scr)
            .compile();


        this.traversal
                .ubo("SCENE_UNIFORM_BINDING", this.uniformBuffer)
                .ssbo("REQUEST_QUEUE_BINDING", this.requestBuffer)
                .ssbo("NODE_DATA_BINDING", this.nodeBuffer)
                .ssbo("NODE_QUEUE_META_BINDING", this.queueMetaBuffer)
                .ssbo("RENDER_TRACKER_BINDING", this.nodeCleaner.visibilityBuffer)
                .ssboIf("STATISTICS_BUFFER_BINDING", this.statisticsBuffer);
    }

    private void addTLN(int id) {
        if (this.vkTraverser != null) {
            this.vkTraverser.addTLN(id);
            return;
        }
        int aid = this.topNodeCount++;
        if (this.topNodeCount > this.topNodeIds.size()/4) {
            throw new IllegalStateException("Top level node count greater than capacity");
        }

        MemoryUtil.memPutInt(SCRATCH, id);
        nglClearNamedBufferSubData(this.topNodeIds.id, GL_R32UI, aid * 4L, 4, GL_RED_INTEGER, GL_UNSIGNED_INT, SCRATCH);

        if (this.topNode2idxMapping.put(id, aid) != -1) {
            throw new IllegalStateException();
        }
        this.idx2topNodeMapping[aid] = id;
    }

    private void remTLN(int id) {
        if (this.vkTraverser != null) {
            this.vkTraverser.remTLN(id);
            return;
        }
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
        if (this.topNode2idxMapping.put(endTLNId, idx) == -1)
            throw new IllegalStateException();

        MemoryUtil.memPutInt(SCRATCH, endTLNId);
        nglClearNamedBufferSubData(this.topNodeIds.id, GL_R32UI, idx*4L, 4, GL_RED_INTEGER, GL_UNSIGNED_INT, SCRATCH);
    }

    private static void setFrustum(Viewport<?> viewport, long ptr) {
        for (int i = 0; i < 6; i++) {
            var plane = viewport.frustumPlanes[i];
            plane.getToAddress(ptr); ptr += 4*4;
        }
    }

    private void uploadUniform(Viewport<?> viewport) {
        long ptr = UploadStream.INSTANCE.upload(this.uniformBuffer, 0, 1024);

        viewport.MVP.getToAddress(ptr); ptr += 4*4*4;

        viewport.section.getToAddress(ptr); ptr += 4*3;

        MemoryUtil.memPutInt(ptr, viewport.hiZBuffer.getPackedLevels()); ptr += 4;

        viewport.innerTranslation.getToAddress(ptr); ptr += 4*3;

        final float screenspaceAreaDecreasingSize = VoxyConfig.CONFIG.subDivisionSize*VoxyConfig.CONFIG.subDivisionSize;
        MemoryUtil.memPutFloat(ptr, (float) (screenspaceAreaDecreasingSize) /(viewport.width*viewport.height)); ptr += 4;

        setFrustum(viewport, ptr); ptr += 4*4*6;

        MemoryUtil.memPutInt(ptr, (int) (viewport.getRenderList().size()/4-1)); ptr += 4;

        MemoryUtil.memPutInt(ptr, this.nodeCleaner.visibilityId); ptr += 4;

        {
            final double TARGET_COUNT = 4000;
            double iFillness = Math.max(0, (TARGET_COUNT - this.meshGen.getTaskCount()) / TARGET_COUNT);
            iFillness = Math.pow(iFillness, 2);
            final int requestSize = (int) Math.ceil(iFillness * MAX_REQUEST_QUEUE_SIZE);
            MemoryUtil.memPutInt(ptr, Math.max(0, Math.min(MAX_REQUEST_QUEUE_SIZE, requestSize)));ptr += 4;
        }

        MemoryUtil.memPutFloat(ptr, (float) Math.pow(VoxyConfig.CONFIG.sectionRenderDistance*16*32,2));ptr += 4;


    }

    private void bindings(Viewport<?> viewport) {
        glBindBuffer(GL_DISPATCH_INDIRECT_BUFFER, this.queueMetaBuffer.id);

        glBindTextureUnit(0, viewport.hiZBuffer.getHizTextureId());
        glBindSampler(0, this.hizSampler);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, RENDER_QUEUE_BINDING, (int) viewport.getRenderList().rawHandle());
    }

    public void doTraversal(Viewport<?> viewport) {
        if (this.vkTraverser != null) {
            this.vkTraverser.doTraversal(viewport);
            return;
        }
        this.uploadUniform(viewport);

        this.traversal.bind();
        this.bindings(viewport);

        if (this.pipeline != null) this.pipeline.bindUniforms();

        PrintfDebugUtil.bind();

        if (RenderStatistics.enabled) {
            this.statisticsBuffer.zero();
        }

        nglClearNamedBufferSubData((int) viewport.getRenderList().rawHandle(), GL_R32UI, 0, 4, GL_RED_INTEGER, GL_UNSIGNED_INT, 0);

        this.traverseInternal();

        this.downloadResetRequestQueue();

        if (RenderStatistics.enabled) {
            DownloadStream.INSTANCE.download(this.statisticsBuffer, down->{
                for (int i = 0; i < MAX_ITERATIONS; i++) {
                    RenderStatistics.hierarchicalTraversalCounts[i] = MemoryUtil.memGetInt(down.address+i*4L);
                }

                for (int i = 0; i < MAX_ITERATIONS; i++) {
                    RenderStatistics.hierarchicalRenderSections[i] = MemoryUtil.memGetInt(down.address+MAX_ITERATIONS*4L+i*4L);
                }
            });
        }

        glBindSampler(0, 0);
        glBindTextureUnit(0, 0);
    }

    private void traverseInternal() {
        {
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
            glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, 0);
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
            glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
            glPixelStorei(GL_UNPACK_SKIP_IMAGES, 0);
        }

        int firstDispatchSize = (this.topNodeCount+(1<<LOCAL_WORK_SIZE_BITS)-1)>>LOCAL_WORK_SIZE_BITS;

        {
            long ptr = UploadStream.INSTANCE.upload(this.queueMetaBuffer, 0, 16*MAX_ITERATIONS);
            MemoryUtil.memPutInt(ptr +  0, firstDispatchSize);
            MemoryUtil.memPutInt(ptr +  4, 1);
            MemoryUtil.memPutInt(ptr +  8, 1);
            MemoryUtil.memPutInt(ptr + 12, this.topNodeCount);
            for (int i = 1; i < MAX_ITERATIONS; i++) {
                MemoryUtil.memPutInt(ptr + (i*16)+ 0, 0);
                MemoryUtil.memPutInt(ptr + (i*16)+ 4, 1);
                MemoryUtil.memPutInt(ptr + (i*16)+ 8, 1);
                MemoryUtil.memPutInt(ptr + (i*16)+12, 0);
            }
            UploadStream.INSTANCE.commit();
        }

        glUniform1ui(NODE_QUEUE_INDEX_BINDING, 0);

        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, NODE_QUEUE_SOURCE_BINDING, this.topNodeIds.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, NODE_QUEUE_SINK_BINDING, this.scratchQueueB.id);

        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_COMMAND_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT);
        if (firstDispatchSize!=0) {
            glDispatchCompute(firstDispatchSize, 1,1);
        }
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_COMMAND_BARRIER_BIT);

        for (int iter = 1; iter < MAX_ITERATIONS; iter++) {
            glUniform1ui(NODE_QUEUE_INDEX_BINDING, iter);

            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, NODE_QUEUE_SOURCE_BINDING, ((iter & 1) == 0 ? this.scratchQueueA : this.scratchQueueB).id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, NODE_QUEUE_SINK_BINDING, ((iter & 1) == 0 ? this.scratchQueueB : this.scratchQueueA).id);

            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_COMMAND_BARRIER_BIT);

            glDispatchComputeIndirect(iter * 4 * 4);
        }

        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_COMMAND_BARRIER_BIT);
    }


    private void downloadResetRequestQueue() {
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        DownloadStream.INSTANCE.download(this.requestBuffer, this::forwardDownloadResult);
        nglClearNamedBufferSubData(this.requestBuffer.id, GL_R32UI, 0, 4, GL_RED_INTEGER, GL_UNSIGNED_INT, 0);
    }

    private void forwardDownloadResult(long ptr, long size) {
        int count = MemoryUtil.memGetInt(ptr);ptr += 8;
        if (count < 0 || count > 50000) {
            Logger.error(new IllegalStateException("Count unexpected extreme value: " + count + " things may get weird"));
            return;
        }
        if (count > (this.requestBuffer.size()>>3)-1) {

            count = (int) ((this.requestBuffer.size()>>3)-1);

        }
        if (count != 0) {
            var buffer = new MemoryBuffer(count*8L+8).cpyFrom(ptr-8);
            MemoryUtil.memPutInt(buffer.address, count);
            this.nodeManager.submitRequestBatch(buffer);
        }
    }

    public me.cortex.voxy.client.core.backend.IBufferHolder getNodeBuffer() {
        return this.vkTraverser != null ? this.vkTraverser.getNodeBuffer() : this.nodeBuffer;
    }

    public me.cortex.voxy.client.core.backend.vk.VkHierarchicalOcclusionTraverser getVkTraverser() {
        return this.vkTraverser;
    }

    public void free() {
        if (this.vkTraverser != null) {
            this.vkTraverser.free();
            return;
        }
        if (this.traversal != null) this.traversal.free();
        this.requestBuffer.free();
        this.nodeBuffer.free();
        this.uniformBuffer.free();
        this.statisticsBuffer.free();
        this.queueMetaBuffer.free();
        this.topNodeIds.free();
        this.scratchQueueA.free();
        this.scratchQueueB.free();
        if (this.hizSampler != 0) glDeleteSamplers(this.hizSampler);
    }

    private static final long SCRATCH = MemoryUtil.nmemAlloc(32);

    public void addDebug(List<String> debug) {
        if (this.vkTraverser != null) {
            this.vkTraverser.addDebug(debug);
            return;
        }
        if (this.topNodeCount>this.idx2topNodeMapping.length/2) {
            debug.add("TLN#: " + this.topNodeCount);
        }
    }
}
