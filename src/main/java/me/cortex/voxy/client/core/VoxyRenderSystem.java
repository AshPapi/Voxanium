package me.cortex.voxy.client.core;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.backend.opengl.GlConst;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import me.cortex.voxy.client.TimingStatistics;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.backend.vk.VkVoxyDevice;
import me.cortex.voxy.client.core.backend.vk.VulkanRenderPipeline;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.ViewportSelector;
import me.cortex.voxy.client.core.rendering.bounding.BoundRenderer;
import me.cortex.voxy.client.core.rendering.bounding.ColumnStreamedBoundStore;
import me.cortex.voxy.client.core.rendering.bounding.StreamedBoundStore;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.section.IUsesMeshlets;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.VkMDICSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.PrintfDebugUtil;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.core.util.GPUTiming;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.util.GlobalCleaner;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11;

import java.lang.ref.Cleaner;
import java.util.Arrays;
import java.util.List;

import static org.lwjgl.opengl.ARBDirectStateAccess.glGetTextureLevelParameteri;
import static org.lwjgl.opengl.GL11.glGetIntegerv;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER_BINDING;

public class VoxyRenderSystem {
    private final WorldEngine worldIn;

    private final ModelBakerySubsystem modelService;
    private final RenderGenerationService renderGen;
    private final IGeometryData geometryData;
    private final AsyncNodeManager nodeManager;
    private final NodeCleaner nodeCleaner;
    private final HierarchicalOcclusionTraverser traversal;

    private final Cleaner.Cleanable geoRef;

    private final RenderDistanceTracker renderDistanceTracker;
    private final @Nullable BoundRenderer boundOutlineRenderer;
    public final StreamedBoundStore visbleSectionStream;
    private @Nullable ColumnStreamedBoundStore columnStreamedBoundStore;

    private final ViewportSelector<?> viewportSelector;

    private final AbstractRenderPipeline pipeline;
    private final RenderProperties properties;

    private static AbstractSectionRenderer.Factory<?,? extends IGeometryData> getRenderBackendFactory() {
        if (VkVoxyDevice.isVulkanActive()) {
            return VkMDICSectionRenderer.FACTORY;
        }
        return MDICSectionRenderer.FACTORY;
    }

    public VoxyRenderSystem(WorldEngine world, ServiceManager sm) {
        world.acquireRef();
        Logger.info("Creating Voxy render system");

        System.gc();

        if (Minecraft.getInstance().options.renderDistance().get()<3) {
            Logger.warn("Voxy: Having a vanilla render distance of 2 can cause rare culling near the edge of your screen issues, please use 3 or more");
            Minecraft.getInstance().gui.chatListener().handleSystemMessage(Component.translatable("voxy.warning.low_render_distance"), false);
        }

        int[] oldBufferBindings = new int[10];
        if (!VkVoxyDevice.isVulkanActive()) {
            for (int i = 0; i < oldBufferBindings.length; i++) {
                oldBufferBindings[i] = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, i);
            }
        }

        try {
            if (!VkVoxyDevice.isVulkanActive()) {
                glFinish();
                glFinish();
            }

            this.worldIn = world;

            this.properties = RenderProperties.getRenderProperties();
            this.visbleSectionStream = new StreamedBoundStore();
            var backendFactory = getRenderBackendFactory();
            {
                this.modelService = new ModelBakerySubsystem(world.getMapper());
                this.renderGen = new RenderGenerationService(world, this.modelService, sm, IUsesMeshlets.class.isAssignableFrom(backendFactory.clz()));

                if (VkVoxyDevice.isVulkanActive()) {
                    long capacity = VkVoxyDevice.getInstance().geometryBufferSize();
                    BasicSectionGeometryData geometry;
                    try {
                        geometry = new BasicSectionGeometryData(1<<20, capacity);
                        Logger.info("Vulkan geometry buffer size: " + (capacity>>20) + "mb");
                    } catch (RuntimeException e) {
                        Logger.error("Failed to allocate a " + (capacity>>20) + "mb geometry buffer, falling back to 512mb", e);
                        geometry = new BasicSectionGeometryData(1<<20, 512L * 1024L * 1024L);
                    }
                    this.geometryData = geometry;
                    this.geoRef = null;
                } else {
                    this.geometryData = new BasicSectionGeometryData(1<<20, RenderResourceReuse.getOrCreateGeometryBuffer());

                    if (((BasicSectionGeometryData)this.geometryData).isExternalGeometryBuffer) {
                        var buffer = ((BasicSectionGeometryData)this.geometryData).getGeometryBuffer();
                        this.geoRef = GlobalCleaner.CLEANER.register(this.geometryData,() -> RenderResourceReuse.giveBackGeometryBuffer((GlBuffer) buffer));
                    } else {
                        this.geoRef = null;
                    }
                }

                this.nodeManager = new AsyncNodeManager(1 << 21, this.geometryData, this.renderGen);
                this.nodeCleaner = new NodeCleaner(this.nodeManager);
                this.traversal = new HierarchicalOcclusionTraverser(this.nodeManager, this.nodeCleaner, this.renderGen);

                world.setDirtyCallback(this.nodeManager::worldEvent);

                Arrays.stream(world.getMapper().getBiomeEntries()).forEach(this.modelService::addBiome);
                world.getMapper().setBiomeCallback(this.modelService::addBiome);

                this.nodeManager.start();
            }

            this.pipeline = RenderPipelineFactory.createPipeline(this.properties, this.nodeManager, this.nodeCleaner, this.traversal, this::frexStillHasWork);
            this.pipeline.setupExtraModelBakeryData(this.modelService);

            this.traversal.lateStageCompile(this.pipeline);


            var sectionRenderer = backendFactory.create(this.pipeline, this.modelService.getStore(), this.geometryData);
            this.pipeline.setSectionRenderer(sectionRenderer);
            this.viewportSelector = new ViewportSelector<>(sectionRenderer::createViewport);

            {
                int minSec = Minecraft.getInstance().level.getMinSectionY() >> 5;
                int maxSec = (Minecraft.getInstance().level.getMaxSectionY() - 1) >> 5;

                if (VoxyCommon.IS_MINE_IN_ABYSS) {
                    minSec = -8;
                    maxSec = 7;
                }

                this.renderDistanceTracker = new RenderDistanceTracker(40,
                        minSec,
                        maxSec,
                        this.nodeManager::addTopLevel,
                        this.nodeManager::removeTopLevel);

                this.setRenderDistance(VoxyConfig.CONFIG.sectionRenderDistance);
            }

            this.boundOutlineRenderer = VkVoxyDevice.isVulkanActive() ? null : new BoundRenderer(this.pipeline);

            Logger.info("Voxy render system created with " + this.geometryData.getMaxCapacity() + " geometry capacity, using pipeline '" + this.pipeline.getClass().getSimpleName() + "' with renderer '" + sectionRenderer.getClass().getSimpleName() + "'");
        } catch (RuntimeException e) {
            world.releaseRef();
            throw e;
        }

        if (!VkVoxyDevice.isVulkanActive()) {
            for (int i = 0; i < oldBufferBindings.length; i++) {
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, oldBufferBindings[i]);
            }

            for (int i = 0; i < 12; i++) {
                GlStateManager._activeTexture(GlConst.GL_TEXTURE0+i);
                GlStateManager._bindTexture(0);
                glBindSampler(i, 0);
            }
        }
    }


    public Viewport<?> setupViewport(Matrix4fc vanillaProjection, Matrix4fc modelView, FogParameters fogParameters, int width, int height, double cameraX, double cameraY, double cameraZ) {
        var viewport = this.getViewport();
        if (viewport == null) {
            return null;
        }

        if (VoxyCommon.IS_MINE_IN_ABYSS) {
            int sector = (((int)Math.floor(cameraX)>>4)+512)>>10;
            cameraX -= sector<<14;
            cameraY += (16+(256-32-sector*30))*16;
        }

        float far = 3000 * 16;
        if (this.pipeline instanceof IrisVoxyRenderPipeline ivrp) {
            if (ivrp._getData().useDynamicFarPlane) {
                far  = (VoxyConfig.CONFIG.sectionRenderDistance * 32 + 2) * ((float) Math.sqrt(3)) * 16;
            }
        }

        float near = getVanillaRenderDistance()<=32.0f?8f:16f;
        near = VoxyClient.disableSodiumChunkRender()?0.1f:near;
        var voxyProjection = computeProjectionMat(this.properties, vanillaProjection, near, far);

        {
            var factor = this.pipeline.getRenderScalingFactor();
            if (factor != null) {
                width = (int) (width*factor[0]);
                height = (int) (height*factor[1]);
            }
        }
        if (width == 0 || height == 0) {
            Logger.error("Viewport width or height was zero, this is bad bad bad");
            return null;
        }

        viewport
                .setVanillaProjection(vanillaProjection)
                .setProjection(voxyProjection, near, far)
                .setModelView(new Matrix4f(modelView))
                .setCamera(cameraX, cameraY, cameraZ)
                .setScreenSize(width, height)
                .setFogParameters(fogParameters)
                .update();

        if (VoxyClient.getOcclusionDebugState()==0) {
            viewport.frameId++;
        }

        return viewport;
    }


    public void renderOpaque(Viewport<?> viewport, int sourceDepthTexture, int sourceColourTexture) {
        if (VkVoxyDevice.isVulkanActive()) {
            return;
        }

        if (viewport == null) {
            return;
        }

        if (viewport.width <= 0 || viewport.height <= 0) {
            Logger.error("Viewport width or height was zero, this is bad bad bad, exiting frame");
            return;
        }

        if (sourceDepthTexture == 0) {
            throw new IllegalStateException("Source depth texture cannot be 0");
        }

        TimingStatistics.resetSamplers();

        TimingStatistics.all.start();
        GPUTiming.INSTANCE.marker();
        TimingStatistics.main.start();

        int[] oldBufferBindings = new int[10];
        for (int i = 0; i < oldBufferBindings.length; i++) {
            oldBufferBindings[i] = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, i);
        }

        GlStateManager._enableDepthTest();
        GlStateManager._depthFunc(this.properties.closerEqualDepthCompare());
        GlStateManager._depthMask(true);
        GlStateManager._disablePolygonOffset();

        int oldFB = GL11.glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);

        int[] dims = new int[4];
        glGetIntegerv(GL_VIEWPORT, dims);

        glViewport(0, 0, viewport.width, viewport.height);

        int scrWidth  = glGetTextureLevelParameteri(sourceDepthTexture, 0, GL_TEXTURE_WIDTH);
        int scrHeight = glGetTextureLevelParameteri(sourceDepthTexture, 0, GL_TEXTURE_HEIGHT);

        this.pipeline.preSetup(viewport);

        TimingStatistics.E.start();
        if (this.visbleSectionStream != null && (!VoxyClient.disableSodiumChunkRender()) && !IrisUtil.irisShadowActive()) {
            if (VoxyClient.isFrexActive()!=(this.columnStreamedBoundStore!=null)) {
                if (this.columnStreamedBoundStore == null) {
                    this.columnStreamedBoundStore = new ColumnStreamedBoundStore();
                } else {
                    this.columnStreamedBoundStore.free();
                    this.columnStreamedBoundStore = null;
                }
            }
            if (this.boundOutlineRenderer != null) {
                this.boundOutlineRenderer.render(viewport, this.columnStreamedBoundStore==null?this.visbleSectionStream:this.columnStreamedBoundStore);
            }
        } else {
            viewport.depthBoundingBuffer.clear(this.properties.inverseClearDepth());
        }
        TimingStatistics.E.stop();


        GPUTiming.INSTANCE.marker();
        this.pipeline.runPipeline(viewport, sourceDepthTexture, sourceColourTexture, scrWidth, scrHeight);
        GPUTiming.INSTANCE.marker();


        TimingStatistics.main.stop();
        TimingStatistics.postDynamic.start();

        PrintfDebugUtil.tick();

        {
            UploadStream.INSTANCE.tick();

            while (this.renderDistanceTracker.setCenterAndProcess(viewport.cameraX, viewport.cameraZ) && VoxyClient.isFrexActive());
            TimingStatistics.H.start();
            do { this.modelService.tick(900_000); } while (VoxyClient.isFrexActive() && !this.modelService.areQueuesEmpty());
            TimingStatistics.H.stop();
        }

        GPUTiming.INSTANCE.marker();
        TimingStatistics.postDynamic.stop();

        GPUTiming.INSTANCE.tick();

        glBindFramebuffer(GlConst.GL_FRAMEBUFFER, oldFB);
        glViewport(dims[0], dims[1], dims[2], dims[3]);

        {
            GlStateManager._glUseProgram(0);
            glUseProgram(0);
            GlStateManager._enableDepthTest();
            glEnable(GL_DEPTH_TEST);
            glDisable(GL_STENCIL_TEST);

            GlStateManager._glBindVertexArray(0);
            glBindVertexArray(0);

            GlStateManager._activeTexture(GlConst.GL_TEXTURE1);
            for (int i = 0; i < 12; i++) {
                GlStateManager._activeTexture(GlConst.GL_TEXTURE0+i);
                GlStateManager._bindTexture(0);
                glBindSampler(i, 0);
            }

            IrisUtil.clearIrisSamplers();

            for (int i = 0; i < oldBufferBindings.length; i++) {
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, oldBufferBindings[i]);
            }
            GlStateManager._blendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD);
            glBlendEquation(GL_FUNC_ADD);
            GlStateManager._blendFuncSeparate(0,0, 0, 0);
            glBlendFunc(0, 0);
            GlStateManager._disableBlend(0);
            glDisable(GL_BLEND);
            GlStateManager._depthFunc(GL_LESS);
            glDepthFunc(GL_LESS);
        }

        TimingStatistics.all.stop();
    }

    public void captureSceneDepthVulkan() {
        if (this.pipeline instanceof VulkanRenderPipeline vkPipeline) {
            vkPipeline.captureSceneDepth(Minecraft.getInstance().gameRenderer.mainRenderTarget());
        }
    }

    public void compositeOverShaderPackVulkan() {
        if (this.pipeline instanceof VulkanRenderPipeline vkPipeline) {
            vkPipeline.compositeOverShaderPack(Minecraft.getInstance().gameRenderer.mainRenderTarget());
            vkPipeline.endPackFrame();
        }
    }

    public void drawPackOpaqueVulkan() {
        if (this.pipeline instanceof VulkanRenderPipeline vkPipeline) {
            vkPipeline.drawPackOpaque();
        }
    }

    public void drawPackTranslucentVulkan() {
        if (this.pipeline instanceof VulkanRenderPipeline vkPipeline) {
            vkPipeline.drawPackTranslucent();
        }
    }

    public void renderOpaqueVulkan(Viewport<?> viewport, VulkanGpuTextureView depthView, VulkanGpuTextureView colorView, RenderPass pass) {
        if (viewport == null) {
            return;
        }

        if (viewport.width <= 0 || viewport.height <= 0) {
            Logger.error("Viewport width or height was zero, this is bad bad bad, exiting frame");
            return;
        }

        if (depthView == null) {
            throw new IllegalStateException("Source depth texture cannot be null");
        }

        TimingStatistics.resetSamplers();

        TimingStatistics.all.start();
        GPUTiming.INSTANCE.marker();
        TimingStatistics.main.start();

        this.pipeline.preSetup(viewport);

        StreamedBoundStore bounds = null;
        if (this.visbleSectionStream != null && (!VoxyClient.disableSodiumChunkRender()) && !IrisUtil.irisShadowActive()) {
            bounds = this.visbleSectionStream;
        }

        GPUTiming.INSTANCE.marker();
        this.pipeline.runPipelineVulkan(viewport, depthView, colorView, pass, bounds);
        GPUTiming.INSTANCE.marker();

        TimingStatistics.main.stop();
        TimingStatistics.postDynamic.start();

        PrintfDebugUtil.tick();

        {
            UploadStream.INSTANCE.tick();

            while (this.renderDistanceTracker.setCenterAndProcess(viewport.cameraX, viewport.cameraZ) && VoxyClient.isFrexActive());
            TimingStatistics.H.start();
            do { this.modelService.tick(900_000); } while (VoxyClient.isFrexActive() && !this.modelService.areQueuesEmpty());
            TimingStatistics.H.stop();
        }

        GPUTiming.INSTANCE.marker();
        TimingStatistics.postDynamic.stop();

        GPUTiming.INSTANCE.tick();

        TimingStatistics.all.stop();
    }

    private void autoBalanceSubDivSize() {
        boolean canDecreaseSize = this.renderGen.getTaskCount() < 300;
        int MIN_FPS = 55;
        int MAX_FPS = 65;
        float INCREASE_PER_SECOND = 60;
        float DECREASE_PER_SECOND = 30;
        if (Minecraft.getInstance().getFps() < MIN_FPS) {
            VoxyConfig.CONFIG.subDivisionSize = Math.min(VoxyConfig.CONFIG.subDivisionSize + INCREASE_PER_SECOND / Math.max(1f, Minecraft.getInstance().getFps()), 256);
        }

        if (MAX_FPS < Minecraft.getInstance().getFps() && canDecreaseSize) {
            VoxyConfig.CONFIG.subDivisionSize = Math.max(VoxyConfig.CONFIG.subDivisionSize - DECREASE_PER_SECOND / Math.max(1f, Minecraft.getInstance().getFps()), 28);
        }
    }

    public static float getVanillaRenderDistance() {
        return Minecraft.getInstance().options.getEffectiveRenderDistance()*16;
    }

    private static Matrix4f computeProjectionMat(RenderProperties properties, Matrix4fc base, float nearPlane, float farPlane) {

        var rawMCProj = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState.projectionMatrix;
        var extraProjection = rawMCProj.invert(new Matrix4f()).mul(base);


        float far = farPlane;
        float near = nearPlane;

        if (properties.isReverseZ()) {
            float tmp = nearPlane;
            near = far;
            far = tmp;
        }

        return extraProjection.mulLocal(
                new Matrix4f(rawMCProj)
                .m22((properties.isZero2One()?far:(far+near)) / (near - far))
                .m32((properties.isZero2One()?far:(far+far)) * near / (near - far))
        );
    }

    private boolean frexStillHasWork() {
        if (!VoxyClient.isFrexActive()) {
            return false;
        }
        UploadStream.INSTANCE.tick();
        this.modelService.tick(100_000_000);
        if (!VkVoxyDevice.isVulkanActive()) {
            GL11.glFinish();
        }
        return this.nodeManager.hasWork() || this.renderGen.getTaskCount()!=0 || !this.modelService.areQueuesEmpty();
    }

    public void setRenderDistance(float renderDistance) {
        this.renderDistanceTracker.setRenderDistance((int) Math.ceil(renderDistance+1));
    }

    public Viewport<?> getViewport() {
        if (IrisUtil.irisShadowActive()) {
            return null;
        }
        return this.viewportSelector.getViewport();
    }

    public void addDebugInfo(List<String> debug) {
        if (!VkVoxyDevice.isVulkanActive()) {
            debug.add("Buf/Tex [#/Mb]: [" + GlBuffer.getCount() + "/" + (GlBuffer.getTotalSize()/1_000_000) + "],[" + GlTexture.getCount() + "/" + (GlTexture.getEstimatedTotalSize()/1_000_000)+"]");
        }
        {
            this.modelService.addDebugData(debug);
            this.renderGen.addDebugData(debug);
            this.nodeManager.addDebug(debug);
            this.pipeline.addDebug(debug);
        }
        {
            TimingStatistics.update();
            debug.add("Voxy frame runtime (millis): " + TimingStatistics.dynamic.pVal() + ", " + TimingStatistics.main.pVal()+ ", " + TimingStatistics.postDynamic.pVal()+ ", " + TimingStatistics.all.pVal());
            debug.add("Extra time: " + TimingStatistics.A.pVal() + ", " + TimingStatistics.B.pVal() + ", " + TimingStatistics.C.pVal() + ", " + TimingStatistics.D.pVal());
            debug.add("Extra 2 time: " + TimingStatistics.E.pVal() + ", " + TimingStatistics.F.pVal() + ", " + TimingStatistics.G.pVal() + ", " + TimingStatistics.H.pVal() + ", " + TimingStatistics.I.pVal());
        }
        debug.add(GPUTiming.INSTANCE.getDebug());
        PrintfDebugUtil.addToOut(debug);
    }

    public void shutdown() {
        if (VkVoxyDevice.isVulkanActive()) {
            VkVoxyDevice.getInstance().waitIdle();
        }
        Logger.info("Flushing download stream");
        try {
            DownloadStream.INSTANCE.flushWaitClear();
        } catch (Exception e) {Logger.error("Error flushing download stream", e);}
        Logger.info("Shutting down rendering");
        try {
            this.worldIn.setDirtyCallback(null);
            this.worldIn.getMapper().setBiomeCallback(null);
            this.worldIn.getMapper().setStateCallback(null);

            this.nodeManager.stop();

            this.modelService.shutdown();
            this.renderGen.shutdown();
            this.traversal.free();
            this.nodeCleaner.free();
            this.geometryData.free();
            if (this.geoRef != null) {
                this.geoRef.clean();
            }

            if (this.boundOutlineRenderer != null) {
                this.boundOutlineRenderer.free();
            }
            if (this.visbleSectionStream != null) {
                this.visbleSectionStream.free();
            }
            if (this.columnStreamedBoundStore != null) {
                this.columnStreamedBoundStore.free();
                this.columnStreamedBoundStore = null;
            }

            this.viewportSelector.free();
        } catch (Exception e) {Logger.error("Error shutting down renderer components", e);}
        Logger.info("Shutting down render pipeline");
        try {this.pipeline.free();} catch (Exception e){Logger.error("Error releasing render pipeline", e);}



        Logger.info("Flushing download stream");
        try {
            DownloadStream.INSTANCE.flushWaitClear();
        } catch (Exception e) {Logger.error("Error flushing download stream", e);}

        this.worldIn.releaseRef();
        Logger.info("Render shutdown completed");
    }

    public WorldEngine getEngine() {
        return this.worldIn;
    }
}
