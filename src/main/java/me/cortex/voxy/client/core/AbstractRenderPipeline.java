package me.cortex.voxy.client.core;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import me.cortex.voxy.client.RenderStatistics;
import me.cortex.voxy.client.TimingStatistics;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.gl.GlFramebuffer;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.bounding.StreamedBoundStore;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.post.FullscreenBlit;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.util.DepthFramebuffer;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.util.GPUTiming;
import me.cortex.voxy.common.util.TrackedObject;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.util.List;
import java.util.function.BooleanSupplier;

import static org.lwjgl.opengl.GL11C.GL_ALWAYS;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11C.GL_EQUAL;
import static org.lwjgl.opengl.GL11C.GL_KEEP;
import static org.lwjgl.opengl.GL11C.GL_REPLACE;
import static org.lwjgl.opengl.GL11C.GL_STENCIL_TEST;
import static org.lwjgl.opengl.GL11C.glColorMask;
import static org.lwjgl.opengl.GL11C.glDisable;
import static org.lwjgl.opengl.GL11C.glEnable;
import static org.lwjgl.opengl.GL11C.glStencilFunc;
import static org.lwjgl.opengl.GL11C.glStencilMask;
import static org.lwjgl.opengl.GL11C.glStencilOp;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30C.GL_DEPTH24_STENCIL8;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;
import static org.lwjgl.opengl.GL42.*;
import static org.lwjgl.opengl.GL42.GL_DEPTH_ATTACHMENT;
import static org.lwjgl.opengl.GL42.GL_DEPTH_STENCIL;
import static org.lwjgl.opengl.GL42.GL_NEAREST;
import static org.lwjgl.opengl.GL42.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL42.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL42.glDepthFunc;
import static org.lwjgl.opengl.GL42.glDepthMask;
import static org.lwjgl.opengl.GL42.glUniform2f;
import static org.lwjgl.opengl.GL42.nglUniformMatrix4fv;
import static org.lwjgl.opengl.GL45.glClearNamedFramebufferfi;
import static org.lwjgl.opengl.GL45C.glBindTextureUnit;

public abstract class AbstractRenderPipeline extends TrackedObject {
    public final RenderProperties properties;
    private final BooleanSupplier frexStillHasWork;

    private final AsyncNodeManager nodeManager;
    private final NodeCleaner nodeCleaner;
    private final HierarchicalOcclusionTraverser traversal;

    protected AbstractSectionRenderer<?,?> sectionRenderer;

    private final FullscreenBlit depthStencilSetup;

    public final DepthFramebuffer fb = new DepthFramebuffer(GL_DEPTH24_STENCIL8);

    private final GlFramebuffer scratchFramebuffer = new GlFramebuffer();

    protected final boolean deferTranslucency;

    private static final int DEPTH_SAMPLER = me.cortex.voxy.client.core.backend.vk.VkVoxyDevice.isVulkanActive() ? 0 : glGenSamplers();
    static {
        if (DEPTH_SAMPLER != 0) {
        glSamplerParameteri(DEPTH_SAMPLER, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glSamplerParameteri(DEPTH_SAMPLER, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        }
    }

    protected AbstractRenderPipeline(RenderProperties properties, AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier, boolean deferTranslucency) {
        this.properties = properties;
        this.frexStillHasWork = frexSupplier;
        this.nodeManager = nodeManager;
        this.nodeCleaner = nodeCleaner;
        this.traversal = traversal;
        this.deferTranslucency = deferTranslucency;

        this.depthStencilSetup = me.cortex.voxy.client.core.backend.vk.VkVoxyDevice.isVulkanActive() ? null : new FullscreenBlit(properties, "voxy:post/fullscreen2.vert", "voxy:post/setup_stencil_depth.frag");
    }

    public void setupExtraModelBakeryData(ModelBakerySubsystem modelService) {}

    public final void setSectionRenderer(AbstractSectionRenderer<?,?> sectionRenderer) {
        if (this.sectionRenderer != null) throw new IllegalStateException();
        this.sectionRenderer = sectionRenderer;
    }

    public void preSetup(Viewport<?> viewport) {

    }

    protected abstract int setup(Viewport<?> viewport, int sourceDepthTexture, int srcWidth, int srcHeight);
    protected abstract void postOpaquePreTranslucent(Viewport<?> viewport, int sourceDepthTexture);
    protected void finish(Viewport<?> viewport, int sourceDepthTexture, int outputFramebuffer, int srcWidth, int srcHeight) {
        glDisable(GL_STENCIL_TEST);
    }

    public void runPipeline(Viewport<?> viewport, int sourceDepthTexture, int sourceColourTexture, int srcWidth, int srcHeight) {
        int depthTexture = this.setup(viewport, sourceDepthTexture, srcWidth, srcHeight);

        var rs = ((AbstractSectionRenderer)this.sectionRenderer);
        GPUTiming.INSTANCE.marker("RO");
        rs.renderOpaque(viewport);
        var occlusionDebug = VoxyClient.getOcclusionDebugState();
        if (occlusionDebug==0) {
            GPUTiming.INSTANCE.marker("I");
            this.innerPrimaryWork(viewport, depthTexture);
            GPUTiming.INSTANCE.marker();
        }

        if (occlusionDebug<=1) {
            TimingStatistics.G.start();
            rs.buildDrawCalls(viewport);
            TimingStatistics.G.stop();
        }

        GPUTiming.INSTANCE.marker("TP");
        rs.renderTemporal(viewport);

        rs.postOpaquePreperation(viewport);

        this.postOpaquePreTranslucent(viewport, sourceDepthTexture);
        GPUTiming.INSTANCE.marker("RT");

        if (!this.deferTranslucency) {
            rs.renderTranslucent(viewport);
        }
        GPUTiming.INSTANCE.marker();

        this.scratchFramebuffer.bind(GL_DEPTH_ATTACHMENT, sourceDepthTexture)
                .bind(GL_COLOR_ATTACHMENT0, sourceColourTexture);
        this.finish(viewport, sourceDepthTexture, this.scratchFramebuffer.id, srcWidth, srcHeight);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    public void runPipelineVulkan(Viewport<?> viewport, VulkanGpuTextureView depthView, VulkanGpuTextureView colorView, RenderPass pass, StreamedBoundStore bounds) {
        if (this.sectionRenderer != null) {
            ((AbstractSectionRenderer) this.sectionRenderer).renderOpaque(viewport);
        }
    }

    protected void initDepthStencil(Viewport<?> viewport, int sourceDepthTexture, int targetFb, int srcWidth, int srcHeight, int width, int height) {
        glClearNamedFramebufferfi(targetFb, GL_DEPTH_STENCIL, 0, this.properties.clearDepth(), 1);
        glBindFramebuffer(GL30.GL_FRAMEBUFFER, targetFb);

        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_ALWAYS);

        glEnable(GL_STENCIL_TEST);
        glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
        glStencilFunc(GL_ALWAYS, 0, 0xFF);
        glStencilMask(0xFF);


        this.depthStencilSetup.bind();
        glBindTextureUnit(0, sourceDepthTexture);
        glBindSampler(0, DEPTH_SAMPLER);
        glUniform2f(1,((float)width)/srcWidth, ((float)height)/srcHeight);
        glDepthMask(true);
        glColorMask(false,false,false,false);
        this.depthStencilSetup.blit();


        glDepthFunc(this.properties.closerEqualDepthCompare());
        glColorMask(true,true,true,true);

        glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
        glStencilFunc(GL_EQUAL, 1, 0xFF);
    }

    private static final long SCRATCH = MemoryUtil.nmemAlloc(4*4*4);
    protected static void transformBlitDepth(FullscreenBlit blitShader, int srcDepthTex, int dstFB, Viewport<?> viewport, Matrix4f targetTransform) {
        glDisable(GL_STENCIL_TEST);
        glBindFramebuffer(GL30.GL_FRAMEBUFFER, dstFB);

        blitShader.bind();
        glBindTextureUnit(0, srcDepthTex);
        new Matrix4f(viewport.MVP).invert().getToAddress(SCRATCH);
        nglUniformMatrix4fv(1, 1, false, SCRATCH);
        targetTransform.getToAddress(SCRATCH);
        nglUniformMatrix4fv(2, 1, false, SCRATCH);

        glEnable(GL_DEPTH_TEST);
        blitShader.blit();
        glDisable(GL_STENCIL_TEST);
        glDisable(GL_DEPTH_TEST);
    }

    protected void innerPrimaryWork(Viewport<?> viewport, int depthBuffer) {

        viewport.hiZBuffer.buildMipChain(depthBuffer, viewport.width, viewport.height);

        do {
            TimingStatistics.main.stop();
            TimingStatistics.dynamic.start();

            TimingStatistics.D.start();
            DownloadStream.INSTANCE.tick();
            TimingStatistics.D.stop();

            this.nodeManager.tick(this.traversal.getNodeBuffer(), this.nodeCleaner);

            this.nodeCleaner.tick(this.traversal.getNodeBuffer());

            TimingStatistics.dynamic.stop();
            TimingStatistics.main.start();

            glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT | GL_PIXEL_BUFFER_BARRIER_BIT);

            TimingStatistics.F.start();
            this.traversal.doTraversal(viewport);
            TimingStatistics.F.stop();
        } while (this.frexStillHasWork.getAsBoolean());
    }

    protected void runTraversalVulkan(Viewport<?> viewport) {
        do {
            TimingStatistics.main.stop();
            TimingStatistics.dynamic.start();

            TimingStatistics.D.start();
            DownloadStream.INSTANCE.tick();
            TimingStatistics.D.stop();

            this.nodeManager.tick(this.traversal.getNodeBuffer(), this.nodeCleaner);
            this.nodeCleaner.tick(this.traversal.getNodeBuffer());

            TimingStatistics.dynamic.stop();
            TimingStatistics.main.start();

            TimingStatistics.F.start();
            this.traversal.doTraversal(viewport);
            TimingStatistics.F.stop();
        } while (this.frexStillHasWork.getAsBoolean());
    }

    @Override
    protected void free0() {
        this.scratchFramebuffer.free();
        this.fb.free();
        this.sectionRenderer.free();
        if (this.depthStencilSetup != null) {
            this.depthStencilSetup.delete();
        }
        super.free0();
    }

    public void addDebug(List<String> debug) {
        this.sectionRenderer.addDebug(debug);
        this.traversal.addDebug(debug);
        RenderStatistics.addDebug(debug);
    }

    public abstract void setupAndBindOpaque(Viewport<?> viewport);
    public abstract void setupAndBindTranslucent(Viewport<?> viewport);


    public void bindUniforms() {
        this.bindUniforms(-1);
    }

    public void bindUniforms(int index) {
    }

    public boolean hasTAA() {
        return false;
    }

    public String taaFunction(String functionName) {
        return this.taaFunction(-1, functionName);
    }

    public String taaFunction(int uboBindingPoint, String functionName) {
        return null;
    }

    public String patchOpaqueShader(AbstractSectionRenderer<?,?> renderer, String input) {
        return null;
    }

    public String patchTranslucentShader(AbstractSectionRenderer<?,?> renderer, String input) {
        return null;
    }

    public float[] getRenderScalingFactor() {return null;}

}
