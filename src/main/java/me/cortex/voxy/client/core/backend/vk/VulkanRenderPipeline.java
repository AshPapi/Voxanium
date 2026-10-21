package me.cortex.voxy.client.core.backend.vk;


import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import org.lwjgl.vulkan.VkCommandBuffer;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.NormalRenderPipeline.FogMode;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.SSAO.SSAOMode;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.bounding.StreamedBoundStore;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.VkMDICSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.VkMDICViewport;
import me.cortex.voxy.client.core.rendering.util.SpecialFog;
import me.cortex.voxy.client.core.util.VitrailUtil;
import net.minecraft.client.Minecraft;
import me.cortex.voxy.client.mixin.minecraft.AccessorFrontendRenderPass;
import me.cortex.voxy.client.mixin.minecraft.AccessorVulkanRenderPass;
import me.cortex.voxy.common.Logger;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.BooleanSupplier;

public class VulkanRenderPipeline extends AbstractRenderPipeline {
    private final VkVoxyDevice device;
    private final int depthFormat;
    private final boolean hasStencil;
    private final FogMode fogMode;
    private final boolean enableSSAO;

    private VkVoxyTexture colourTex;
    private VkVoxyTexture colourSSAOTex;
    private VkVoxyTexture depthTex;

    private final VkBoundRenderer boundRenderer;

    private final long nearestSampler;

    private VkVoxyPipeline ssaoComputePipeline;
    private final Map<Long, VkVoxyPipeline> compositePipelines = new HashMap<>();
    private final Map<String, String> compositeDefines = new HashMap<>();

    private final VkVoxyBuffer blitUbo;
    private final VkVoxyBuffer ssaoUbo;

    private boolean warnedUnsupportedPass = false;
    private boolean announcedPackOverlay = false;

    private Viewport<?> overlayViewport;
    private final VkPackGbufferPass packGbuffer;
    private boolean packGbufferBroken;
    private GpuTexture sceneDepth;
    private GpuTextureView sceneDepthView;
    private boolean sceneDepthCaptured;

    private static final String FULLSCREEN_VERT_SHADER = """
            #version 450
            layout(location = 0) out vec2 UV;
            void main() {
                UV = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
                gl_Position = vec4(UV * 2.0f - 1.0f, 0.0f, 1.0f);
            }
            """;

    private static final String BLIT_TEXTURE_DEPTH_CUTOUT_FRAG_SHADER = """
            #version 450

            layout(std140, binding = 0) uniform BlitData {
                mat4 invProjMat;
                mat4 projMat;
                vec4 endParams;
                vec4 fogColour;
                vec4 fadeParams;
            } ubo;

            layout(binding = 1) uniform sampler2D depthTex;
            layout(binding = 2) uniform sampler2D colourTex;

            layout(location = 0) in vec2 UV;
            layout(location = 0) out vec4 outColour;

            #ifdef USE_REVERSE_Z
            #define NEAR 1.0f
            #define FAR 0.0f
            #define CLOSER_SIGN 1.0f
            #define REDUCTION2 max
            #else
            #define NEAR 0.0f
            #define FAR 1.0f
            #define CLOSER_SIGN -1.0f
            #define REDUCTION2 min
            #endif

            #ifdef USE_ZERO_ONE_DEPTH
            #define NDC_FAR FAR
            vec3 SCREEN2NDC(vec3 val) {
                return vec3(val.xy * 2.0f - 1.0f, val.z);
            }
            float NDC2SCREEN_DEPTH(float val) {
                return val;
            }
            #else
            #define NDC_FAR (FAR * 2.0f - 1.0f)
            vec3 SCREEN2NDC(vec3 val) {
                return val * 2.0f - 1.0f;
            }
            float NDC2SCREEN_DEPTH(float val) {
                return val * 0.5f + 0.5f;
            }
            #endif

            vec3 rev3d(vec3 clip) {
                vec4 view = ubo.invProjMat * vec4(SCREEN2NDC(clip), 1.0f);
                return view.xyz / view.w;
            }

            float projDepth(vec3 pos) {
                vec4 view = ubo.projMat * vec4(pos, 1.0f);
                return view.z / view.w;
            }

            void main() {
                vec4 colour = texture(colourTex, UV);
                if (colour.a == 0.0f) {
                    discard;
                }

                float depth = texture(depthTex, UV).r;
                #ifdef USE_REVERSE_Z
                if (depth == 0.0f) {
                    if (colour.a == 0.0f) {
                        discard;
                    }
                    depth = 0.000001f;
                }
                #else
                if (depth == 0.0f || depth == 1.0f) {
                    if (colour.a == 0.0f) {
                        discard;
                    }
                    depth = 0.999999f;
                }
                #endif

                vec3 point = rev3d(vec3(UV, depth));

                depth = projDepth(point);
                depth = REDUCTION2(NDC_FAR + CLOSER_SIGN * (2.0f / ((1 << 24) - 1)), depth);
                depth = NDC2SCREEN_DEPTH(depth);

                gl_FragDepth = depth;

                #ifdef HAS_FOG
                if (ubo.fogColour.a > 0.0f) {
                    float fogLerp = clamp(fma(length(point.xyz), ubo.endParams.x, ubo.endParams.y), 0.0f, ubo.endParams.z);
                    colour.rgb = mix(colour.rgb, ubo.fogColour.rgb, fogLerp * ubo.fogColour.a);
                }
                #endif

                #ifdef HAS_FADE
                if (ubo.fadeParams.x > 0.0f) {
                    float len = ubo.fadeParams.x > 1.5f ? length(point.xyz) : length(point.xz);
                    colour.a *= 1.0f - clamp(fma(len, ubo.fadeParams.z, ubo.fadeParams.y), 0.0f, 1.0f);
                }
                #endif

                outColour = colour;
            }
            """;

    private static final String SSAO_COMPUTE_SHADER = """
            #version 450
            layout(local_size_x = 8, local_size_y = 8) in;

            layout(binding = 0, rgba8) uniform writeonly restrict image2D colourTexOut;
            layout(binding = 1) uniform sampler2D colourTex;
            layout(binding = 2) uniform sampler2D depthTex;

            layout(std140, binding = 3) uniform SSAOUbo {
                mat4 MVP;
                mat4 invMVP;
                ivec2 size;
                vec2 scale;
            } ubo;

            vec3 rev3d(mat4 matrix, vec3 clip) {
                vec4 view = matrix * vec4(clip.xy * 2.0f - 1.0f, clip.z, 1.0f);
                return view.xyz / view.w;
            }

            void main() {
                ivec2 pos = ivec2(gl_GlobalInvocationID.xy);
                if (pos.x >= ubo.size.x || pos.y >= ubo.size.y) {
                    return;
                }

                vec4 baseColour = texelFetch(colourTex, pos, 0);
                float depth = texelFetch(depthTex, pos, 0).r;
            #ifdef USE_REVERSE_Z
                if (depth == 0.0f) {
                    imageStore(colourTexOut, pos, vec4(0.0f));
                    return;
                }
            #else
                if (depth == 1.0f || depth == 0.0f) {
                    imageStore(colourTexOut, pos, vec4(0.0f));
                    return;
                }
            #endif
            #ifdef NO_AO
                imageStore(colourTexOut, pos, vec4(baseColour.rgb, 1.0f));
                return;
            #endif

                vec2 uv = (vec2(pos) + 0.5f) * ubo.scale;
                vec3 origin = rev3d(ubo.invMVP, vec3(uv, depth));

                float occlusion = 0.0f;
                const int SAMPLES = 8;
                const float radius = 0.5f;

                for (int i = 0; i < SAMPLES; i++) {
                    float angle = float(i) * (6.2831853f / float(SAMPLES));
                    vec2 offset = vec2(cos(angle), sin(angle)) * (float(i + 1) / float(SAMPLES)) * 0.02f;
                    vec2 sampleUV = uv + offset;
                    float sampleDepth = texture(depthTex, sampleUV).r;
                    vec3 samplePos = rev3d(ubo.invMVP, vec3(sampleUV, sampleDepth));
                    vec3 diff = samplePos - origin;
                    float d = length(diff);
                    if (d > 0.01f && d < radius && diff.z > 0.0f) {
                        occlusion += 1.0f - (d / radius);
                    }
                }
                occlusion = 1.0f - clamp(occlusion / float(SAMPLES) * 1.5f, 0.0f, 0.8f);
                imageStore(colourTexOut, pos, vec4(baseColour.rgb * occlusion, 1.0f));
            }
            """;

    public VulkanRenderPipeline(RenderProperties properties, AsyncNodeManager nodeManager, NodeCleaner nodeCleaner,
                                HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier) {
        this(VkVoxyDevice.getInstance(), properties, nodeManager, nodeCleaner, traversal, frexSupplier);
    }

    public VulkanRenderPipeline(VkVoxyDevice device, RenderProperties properties, AsyncNodeManager nodeManager,
                                NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier) {
        super(properties, nodeManager, nodeCleaner, traversal, frexSupplier, false);
        this.device = device;
        this.depthFormat = selectDepthFormat(device);
        this.hasStencil = (this.depthFormat == VK12.VK_FORMAT_D24_UNORM_S8_UINT || this.depthFormat == VK12.VK_FORMAT_D32_SFLOAT_S8_UINT);
        this.fogMode = VoxyConfig.CONFIG.getFogMode();
        this.enableSSAO = VoxyConfig.CONFIG.getSSAOMode() != SSAOMode.AUTO;
        this.boundRenderer = new VkBoundRenderer(device, properties, this.depthFormat, this.hasStencil);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack).sType$Default();
            samplerInfo.magFilter(VK12.VK_FILTER_NEAREST);
            samplerInfo.minFilter(VK12.VK_FILTER_NEAREST);
            samplerInfo.mipmapMode(VK12.VK_SAMPLER_MIPMAP_MODE_NEAREST);
            samplerInfo.addressModeU(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            samplerInfo.addressModeV(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            samplerInfo.addressModeW(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            samplerInfo.minLod(0);
            samplerInfo.maxLod(VK12.VK_LOD_CLAMP_NONE);

            LongBuffer pSampler = stack.callocLong(1);
            int res = VK12.vkCreateSampler(device.vkDevice(), samplerInfo, null, pSampler);
            if (res != VK12.VK_SUCCESS) {
                throw new RuntimeException("vkCreateSampler for nearestSampler failed: " + res);
            }
            this.nearestSampler = pSampler.get(0);
        }

        this.blitUbo = VkVoxyBuffer.createHostMapped(device, 256, VK12.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
        this.ssaoUbo = VkVoxyBuffer.createHostMapped(device, 256, VK12.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);

        this.initPipelines();
        this.packGbuffer = VitrailUtil.VITRAIL_INSTALLED ? new VkPackGbufferPass(device, properties, this.depthFormat) : null;
        Logger.info("VulkanRenderPipeline initialized with depth format " + this.depthFormat + " (hasStencil=" + this.hasStencil + ", depthBounds=" + device.depthBoundsSupported() + ")");
    }

    public static int selectDepthFormat(VkVoxyDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkFormatProperties formatProps = VkFormatProperties.calloc(stack);

            VK12.vkGetPhysicalDeviceFormatProperties(device.physicalDevice(), VK12.VK_FORMAT_D32_SFLOAT_S8_UINT, formatProps);
            if ((formatProps.optimalTilingFeatures() & VK12.VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0) {
                return VK12.VK_FORMAT_D32_SFLOAT_S8_UINT;
            }

            VK12.vkGetPhysicalDeviceFormatProperties(device.physicalDevice(), VK12.VK_FORMAT_D24_UNORM_S8_UINT, formatProps);
            if ((formatProps.optimalTilingFeatures() & VK12.VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0) {
                return VK12.VK_FORMAT_D24_UNORM_S8_UINT;
            }

            return VK12.VK_FORMAT_D32_SFLOAT;
        }
    }

    private void initPipelines() {
        Map<String, String> commonDefines = new HashMap<>();
        if (this.properties.isReverseZ()) {
            commonDefines.put("USE_REVERSE_Z", "1");
        }
        if (this.properties.isZero2One()) {
            commonDefines.put("USE_ZERO_ONE_DEPTH", "1");
        }

        this.compositeDefines.putAll(commonDefines);
        this.compositeDefines.put("HAS_FOG", "1");
        if (this.fogMode.hasFade) this.compositeDefines.put("HAS_FADE", "1");

        var ssaoDefines = new java.util.HashMap<>(commonDefines);
        ssaoDefines.put("NO_AO", "1");
        long ssaoCompModule = this.device.compiler().compileShaderModule(
                this.device.vkDevice(),
                org.lwjgl.util.shaderc.Shaderc.shaderc_compute_shader,
                "ssao_compute",
                SSAO_COMPUTE_SHADER,
                ssaoDefines);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(4, stack);
            bindings.get(0).binding(0).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(1).binding(1).descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(2).binding(2).descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(3).binding(3).descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT);

            this.ssaoComputePipeline = VkVoxyPipeline.createCompute(this.device, ssaoCompModule, bindings);
        } finally {
            VK12.vkDestroyShaderModule(this.device.vkDevice(), ssaoCompModule, null);
        }
    }

    private VkVoxyPipeline getCompositePipeline(int colorFormat, int targetDepthFormat) {
        long key = (((long) colorFormat) << 32) | (targetDepthFormat & 0xFFFFFFFFL);
        return this.compositePipelines.computeIfAbsent(key, k -> this.createCompositePipeline(colorFormat, targetDepthFormat));
    }

    private VkVoxyPipeline createCompositePipeline(int colorFormat, int targetDepthFormat) {
        long vertModule = this.device.compiler().compileShaderModule(this.device.vkDevice(),
                org.lwjgl.util.shaderc.Shaderc.shaderc_vertex_shader, "fullscreen_vert", FULLSCREEN_VERT_SHADER, this.compositeDefines);
        long fragModule = this.device.compiler().compileShaderModule(this.device.vkDevice(),
                org.lwjgl.util.shaderc.Shaderc.shaderc_fragment_shader, "blit_texture_depth_cutout_frag", BLIT_TEXTURE_DEPTH_CUTOUT_FRAG_SHADER, this.compositeDefines);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(3, stack);
            bindings.get(0).binding(0).descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_FRAGMENT_BIT);
            bindings.get(1).binding(1).descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_FRAGMENT_BIT);
            bindings.get(2).binding(2).descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_FRAGMENT_BIT);

            int depthCompareOp = this.properties.isReverseZ() ? VK12.VK_COMPARE_OP_GREATER_OR_EQUAL : VK12.VK_COMPARE_OP_LESS_OR_EQUAL;
            boolean depthBounds = this.device.depthBoundsSupported();
            float clearDepth = this.properties.clearDepth();
            return VkVoxyPipeline.createGraphics(
                    this.device, vertModule, fragModule, bindings,
                    colorFormat, targetDepthFormat,
                    true, true, true,
                    depthCompareOp, false, VK12.VK_CULL_MODE_NONE,
                    0, 0, VK12.VK_COMPARE_OP_ALWAYS, VK12.VK_STENCIL_OP_KEEP,
                    depthBounds, clearDepth, clearDepth);
        } finally {
            VK12.vkDestroyShaderModule(this.device.vkDevice(), vertModule, null);
            VK12.vkDestroyShaderModule(this.device.vkDevice(), fragModule, null);
        }
    }

    public void resizeIfNeeded(int width, int height) {
        if (this.colourTex == null || this.colourTex.width() != width || this.colourTex.height() != height) {
            if (this.colourTex != null) {
                this.colourTex.free();
                this.colourSSAOTex.free();
                this.depthTex.free();
            }
            this.colourTex = VkVoxyTexture.createColorAttachment(this.device, width, height, VK12.VK_FORMAT_R8G8B8A8_UNORM);
            this.colourSSAOTex = VkVoxyTexture.createColorAttachment(this.device, width, height, VK12.VK_FORMAT_R8G8B8A8_UNORM);
            this.depthTex = VkVoxyTexture.createDepthAttachment(this.device, width, height, this.depthFormat);
        }
    }

    private long depthSampleView() {
        return this.depthTex.getMipView(0);
    }

    private void runSSAO(VkCommandBuffer cmd, Viewport<?> viewport) {
        this.colourTex.transitionLayout(cmd, VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        this.depthTex.transitionLayout(cmd, VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        this.colourSSAOTex.transitionLayout(cmd, VK12.VK_IMAGE_LAYOUT_GENERAL);

        long ptr = this.ssaoUbo.mappedAddress();
        viewport.MVP.getToAddress(ptr);
        new Matrix4f(viewport.MVP).invert().getToAddress(ptr + 64);
        MemoryUtil.memPutInt(ptr + 128, viewport.width);
        MemoryUtil.memPutInt(ptr + 132, viewport.height);
        MemoryUtil.memPutFloat(ptr + 136, 1.0f / viewport.width);
        MemoryUtil.memPutFloat(ptr + 140, 1.0f / viewport.height);
        this.ssaoUbo.flush(0, 144);

        this.ssaoComputePipeline.bind(cmd);
        this.ssaoComputePipeline.pushStorageImage(cmd, 0, this.colourSSAOTex.vkImageView(), VK12.VK_IMAGE_LAYOUT_GENERAL);
        this.ssaoComputePipeline.pushImage(cmd, 1, this.colourTex.vkImageView(), this.nearestSampler, VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        this.ssaoComputePipeline.pushImage(cmd, 2, this.depthSampleView(), this.nearestSampler, VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        this.ssaoComputePipeline.pushUniformBuffer(cmd, 3, this.ssaoUbo.vkBuffer(), 0, 144);

        this.ssaoComputePipeline.dispatch(cmd, (viewport.width + 7) / 8, (viewport.height + 7) / 8, 1);
    }

    private void writeBlitUniforms(Viewport<?> viewport) {
        long ptr = this.blitUbo.mappedAddress();
        new Matrix4f(viewport.MVP).invert().getToAddress(ptr);
        new Matrix4f(viewport.vanillaProjection).mul(viewport.modelView).getToAddress(ptr + 64);

        for (int i = 0; i < 16; i++) MemoryUtil.memPutFloat(ptr + 128 + i * 4, 0.0f);
        if (this.fogMode.hasFog || SpecialFog.isActive(Minecraft.getInstance().gameRenderer.mainCamera())) {
            float start = viewport.fogParameters.environmentalStart();
            float end = viewport.fogParameters.environmentalEnd();
            if (Math.abs(end - start) > 1.0f) {
                float invEndFogDelta = 1.0f / (end - start);
                float endDistance = Math.max(VoxyRenderSystem.getVanillaRenderDistance(), 20 * 16);
                endDistance *= (float) Math.sqrt(3);
                float startDelta = -start * invEndFogDelta;
                MemoryUtil.memPutFloat(ptr + 128, invEndFogDelta);
                MemoryUtil.memPutFloat(ptr + 132, startDelta);
                MemoryUtil.memPutFloat(ptr + 136, Math.clamp(endDistance * invEndFogDelta + startDelta, 0.0f, 1.0f));

                MemoryUtil.memPutFloat(ptr + 144, viewport.fogParameters.red());
                MemoryUtil.memPutFloat(ptr + 148, viewport.fogParameters.green());
                MemoryUtil.memPutFloat(ptr + 152, viewport.fogParameters.blue());
                MemoryUtil.memPutFloat(ptr + 156, viewport.fogParameters.alpha());
            }
        }

        if (this.fogMode.hasFade) {
            int mode = 1;
            float rd = VoxyConfig.CONFIG.sectionRenderDistance * 16 * 32 - (float) Math.sqrt(mode > 1 ? 32 * 32 * 32 : 32 * 32);
            float vanillaRd = VoxyRenderSystem.getVanillaRenderDistance();
            float start = Math.max(vanillaRd, rd * 0.9f);
            float end = Math.max(vanillaRd, rd);
            float scale = 1.0f / (end - start);
            MemoryUtil.memPutFloat(ptr + 160, (float) mode);
            MemoryUtil.memPutFloat(ptr + 164, (-start) * scale);
            MemoryUtil.memPutFloat(ptr + 168, scale);
        }
        this.blitUbo.flush(0, 176);
    }

    @Override
    public void runPipelineVulkan(Viewport<?> viewport, VulkanGpuTextureView depthView, VulkanGpuTextureView colorView, RenderPass pass, StreamedBoundStore bounds) {
        if (!(this.sectionRenderer instanceof VkMDICSectionRenderer vkSec)) {
            return;
        }

        boolean packFrame = VitrailUtil.packActive();
        PassInfo passInfo = packFrame ? null : getPassInfo(pass, colorView, depthView);
        this.overlayViewport = null;
        this.sceneDepthCaptured = false;
        if (packFrame) {
            this.overlayViewport = viewport;
            if (this.packGbuffer != null && !this.packGbufferBroken) {
                this.packGbuffer.prepareFrame(viewport);
            }
            if (!this.announcedPackOverlay) {
                this.announcedPackOverlay = true;
                Logger.info("A Vitrail shader pack draws the terrain pass, lods go into its gbuffers when it has a voxy patch, otherwise they are composited over its final image");
            }
        } else if (passInfo == null) {
            if (!this.warnedUnsupportedPass) {
                this.warnedUnsupportedPass = true;
                Logger.warn("Voxy cannot composite into this render pass (" + describePass(pass) + "), lods will not be visible. Shader packs that redirect the terrain pass to their own targets are not supported on vulkan yet");
            }
        }

        boolean packDraws = packFrame && this.packGbuffer != null && !this.packGbufferBroken && this.packGbuffer.drewLastFrame();
        this.renderOffscreen(viewport, vkSec, bounds, packDraws);

        if (passInfo != null) {
            this.runComposite(passInfo, viewport);
        }
    }

    private void renderOffscreen(Viewport<?> viewport, VkMDICSectionRenderer vkSec, StreamedBoundStore bounds, boolean packDraws) {
        var vkViewport = (VkMDICViewport) viewport;
        this.resizeIfNeeded(viewport.width, viewport.height);

        VkCommandBuffer cmd = this.beginCommands();
        this.boundRenderer.render(cmd, viewport, bounds);
        vkSec.setActiveCommandBuffer(cmd);
        this.beginTerrainRendering(cmd, viewport, this.colourTex, true);
        vkSec.renderOpaque(vkViewport, cmd);
        this.endTerrainRendering(cmd);
        this.depthTex.transitionLayout(cmd, VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        if (viewport.vkHiZBuffer != null) {
            viewport.vkHiZBuffer.buildMipChain(cmd, this.depthSampleView(), viewport.width, viewport.height);
        }
        this.submitCommands(cmd);

        this.runTraversalVulkan(viewport);
        this.device.graphicsQueue().waitIdle();

        cmd = this.beginCommands();
        vkSec.setActiveCommandBuffer(cmd);
        vkSec.buildDrawCalls(vkViewport, cmd);

        if (packDraws) {
            this.submitCommands(cmd);
            vkSec.setActiveCommandBuffer(null);
            return;
        }

        this.beginTerrainRendering(cmd, viewport, this.colourTex, false);
        vkSec.renderTemporal(vkViewport, cmd);
        this.endTerrainRendering(cmd);

        this.runSSAO(cmd, viewport);
        VkVoxyTexture finalColour = this.colourSSAOTex;

        if (!this.deferTranslucency) {
            this.beginTerrainRendering(cmd, viewport, finalColour, false);
            vkSec.renderTranslucent(vkViewport, cmd);
            this.endTerrainRendering(cmd);
        }

        finalColour.transitionLayout(cmd, VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        this.depthTex.transitionLayout(cmd, VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        this.submitCommands(cmd);
        vkSec.setActiveCommandBuffer(null);
    }

    private void runComposite(PassInfo passInfo, Viewport<?> viewport) {
        if (viewport.fogParameters.environmentalEnd() < VoxyRenderSystem.getVanillaRenderDistance()) {
            return;
        }
        this.writeBlitUniforms(viewport);

        VkCommandBuffer cmd = passInfo.cmd;
        VkVoxyPipeline composite = this.getCompositePipeline(passInfo.colorFormat, passInfo.depthFormat);
        composite.bind(cmd);
        this.setViewportAndScissor(cmd, passInfo.width, passInfo.height);

        VkVoxyTexture finalColour = this.colourSSAOTex;
        composite.pushUniformBuffer(cmd, 0, this.blitUbo.vkBuffer(), 0, 176);
        composite.pushImage(cmd, 1, this.depthSampleView(), this.nearestSampler, VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        composite.pushImage(cmd, 2, finalColour.vkImageView(), this.nearestSampler, VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        composite.draw(cmd, 3);
    }

    public void captureSceneDepth(RenderTarget main) {
        if (this.overlayViewport == null || this.sceneDepthCaptured || main == null) {
            return;
        }
        if (this.packGbuffer != null && this.packGbuffer.drewOpaque()) {
            return;
        }
        GpuTexture source = main.getDepthTexture();
        if (source == null) {
            return;
        }
        int width = source.getWidth(0);
        int height = source.getHeight(0);
        GpuDevice device = RenderSystem.getDevice();
        if (this.sceneDepth == null || this.sceneDepth.getWidth(0) != width || this.sceneDepth.getHeight(0) != height || this.sceneDepth.getFormat() != source.getFormat()) {
            this.freeSceneDepth();
            this.sceneDepth = device.createTexture("Voxy scene depth", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_RENDER_ATTACHMENT, source.getFormat(), width, height, 1, 1);
            this.sceneDepthView = device.createTextureView(this.sceneDepth);
        }
        device.createCommandEncoder().copyTextureToTexture(source, this.sceneDepth, 0, 0, 0, 0, 0, width, height);
        this.sceneDepthCaptured = true;
    }

    public void drawPackOpaque() {
        if (this.packGbuffer == null || this.packGbufferBroken || !(this.sectionRenderer instanceof VkMDICSectionRenderer vkSec)) {
            return;
        }
        try {
            this.packGbuffer.drawOpaque(vkSec);
        } catch (Throwable e) {
            this.packGbufferBroken = true;
            Logger.error("Drawing voxy lods into the shader pack failed, lods are composited over the pack from now on", e);
        }
    }

    public void drawPackTranslucent() {
        if (this.packGbuffer == null || this.packGbufferBroken) {
            return;
        }
        try {
            this.packGbuffer.drawPendingTranslucent();
        } catch (Throwable e) {
            this.packGbufferBroken = true;
            Logger.error("Drawing translucent voxy lods into the shader pack failed, lods are composited over the pack from now on", e);
        }
    }

    public void endPackFrame() {
        if (this.packGbuffer != null) {
            this.packGbuffer.endFrame();
        }
    }

    public void compositeOverShaderPack(RenderTarget main) {
        Viewport<?> viewport = this.overlayViewport;
        if (viewport == null || main == null) {
            return;
        }
        if (this.packGbuffer != null && this.packGbuffer.drewOpaque()) {
            this.overlayViewport = null;
            this.sceneDepthCaptured = false;
            return;
        }
        this.captureSceneDepth(main);
        this.overlayViewport = null;
        this.sceneDepthCaptured = false;

        if (!(main.getColorTextureView() instanceof VulkanGpuTextureView colorView) || !(this.sceneDepthView instanceof VulkanGpuTextureView depthView)) {
            return;
        }

        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Voxy lods over shader pack", colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
            PassInfo passInfo = getPassInfo(pass, colorView, depthView);
            if (passInfo != null) {
                this.runComposite(passInfo, viewport);
            }
        }
    }

    private void freeSceneDepth() {
        if (this.sceneDepthView != null) {
            this.sceneDepthView.close();
            this.sceneDepthView = null;
        }
        if (this.sceneDepth != null) {
            this.sceneDepth.close();
            this.sceneDepth = null;
        }
    }

    private record PassInfo(VkCommandBuffer cmd, int colorFormat, int depthFormat, int width, int height) {}

    static VkCommandBuffer commandBuffer(RenderPass pass) {
        pass = unwrapPass(pass);
        if (pass instanceof com.mojang.renderpearl.frontend.FrontendRenderPass frontendPass) {
            if (((AccessorFrontendRenderPass) frontendPass).voxy$getBackend() instanceof com.mojang.renderpearl.backend.vulkan.VulkanRenderPass vkPass) {
                return ((AccessorVulkanRenderPass) vkPass).voxy$getCommandBuffer();
            }
            return null;
        }
        if (pass instanceof com.mojang.renderpearl.backend.vulkan.VulkanRenderPass vkPass) {
            return ((AccessorVulkanRenderPass) vkPass).voxy$getCommandBuffer();
        }
        return null;
    }

    private static PassInfo getPassInfo(RenderPass pass, VulkanGpuTextureView colorView, VulkanGpuTextureView depthView) {
        if (depthView == null || colorView == null) {
            return null;
        }
        pass = unwrapPass(pass);
        VkCommandBuffer cmd = null;
        int colorFormat = VulkanConst.toVk(colorView.texture().getFormat());
        if (pass instanceof com.mojang.renderpearl.frontend.FrontendRenderPass frontendPass) {
            var accessor = (AccessorFrontendRenderPass) frontendPass;
            List<?> attachments = accessor.voxy$getColorAttachments();
            if (attachments == null || attachments.size() != 1 || attachments.get(0) == null) {
                return null;
            }
            var attachment = (com.mojang.renderpearl.api.commands.RenderPassDescriptor.Attachment<?>) attachments.get(0);
            colorFormat = VulkanConst.toVk(attachment.textureView().texture().getFormat());
            if (accessor.voxy$getBackend() instanceof com.mojang.renderpearl.backend.vulkan.VulkanRenderPass vkPass) {
                cmd = ((AccessorVulkanRenderPass) vkPass).voxy$getCommandBuffer();
            }
        } else if (pass instanceof com.mojang.renderpearl.backend.vulkan.VulkanRenderPass vkPass) {
            cmd = ((AccessorVulkanRenderPass) vkPass).voxy$getCommandBuffer();
        }
        if (cmd == null) {
            return null;
        }
        int depthFormat = VulkanConst.toVk(depthView.texture().getFormat());
        return new PassInfo(cmd, colorFormat, depthFormat, colorView.getWidth(0), colorView.getHeight(0));
    }

    private static final java.util.Map<Class<?>, java.lang.reflect.Member> PASS_UNWRAPPERS = new java.util.concurrent.ConcurrentHashMap<>();

    private static RenderPass unwrapPass(RenderPass pass) {
        for (int i = 0; i < 4 && pass != null; i++) {
            if (pass instanceof com.mojang.renderpearl.frontend.FrontendRenderPass || pass instanceof com.mojang.renderpearl.backend.vulkan.VulkanRenderPass) {
                return pass;
            }
            RenderPass inner = innerPass(pass);
            if (inner == null || inner == pass) {
                return pass;
            }
            pass = inner;
        }
        return pass;
    }

    private static RenderPass innerPass(RenderPass pass) {
        var member = PASS_UNWRAPPERS.computeIfAbsent(pass.getClass(), VulkanRenderPipeline::findPassUnwrapper);
        if (member == NO_UNWRAPPER) {
            return null;
        }
        try {
            if (member instanceof java.lang.reflect.Method method) {
                return (RenderPass) method.invoke(pass);
            } else if (member instanceof java.lang.reflect.Field field) {
                return (RenderPass) field.get(pass);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Logger.error("Failed to unwrap render pass " + pass.getClass().getName(), e);
            PASS_UNWRAPPERS.put(pass.getClass(), NO_UNWRAPPER);
        }
        return null;
    }

    private static final java.lang.reflect.Member NO_UNWRAPPER;
    static {
        try {
            NO_UNWRAPPER = Object.class.getDeclaredMethod("hashCode");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static java.lang.reflect.Member findPassUnwrapper(Class<?> clz) {
        try {
            var method = clz.getDeclaredMethod("real");
            if (RenderPass.class.isAssignableFrom(method.getReturnType())) {
                method.setAccessible(true);
                Logger.info("Unwrapping render pass " + clz.getName() + " through " + method.getName() + "()");
                return method;
            }
        } catch (NoSuchMethodException ignored) {
        } catch (RuntimeException e) {
            Logger.warn("Cannot access the inner pass of " + clz.getName() + ": " + e);
        }
        for (var field : clz.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || !RenderPass.class.isAssignableFrom(field.getType()) || field.getType().isAssignableFrom(clz)) {
                continue;
            }
            try {
                field.setAccessible(true);
                Logger.info("Unwrapping render pass " + clz.getName() + " through field " + field.getName());
                return field;
            } catch (RuntimeException e) {
                Logger.warn("Cannot access the inner pass of " + clz.getName() + ": " + e);
            }
        }
        return NO_UNWRAPPER;
    }

    private static String describePass(RenderPass pass) {
        if (pass != null && unwrapPass(pass) != pass) {
            return pass.getClass().getName() + " wrapping " + describePass(unwrapPass(pass));
        }
        if (pass instanceof com.mojang.renderpearl.frontend.FrontendRenderPass frontendPass) {
            var accessor = (AccessorFrontendRenderPass) frontendPass;
            var attachments = accessor.voxy$getColorAttachments();
            return "colour attachments: " + (attachments == null ? "none" : attachments.size()) + ", backend: " + accessor.voxy$getBackend().getClass().getSimpleName();
        }
        return "pass type: " + (pass == null ? "null" : pass.getClass().getName());
    }

    private VkCommandBuffer beginCommands() {
        VkCommandBuffer cmd = this.device.allocateCommandBuffer();
        this.device.beginSingleTimeCommands(cmd);
        return cmd;
    }

    private void submitCommands(VkCommandBuffer cmd) {
        this.device.endAndSubmitSingleTimeCommands(cmd, this.device.graphicsQueue());
        this.device.freeCommandBuffer(cmd);
    }

    private void setViewportAndScissor(VkCommandBuffer cmd, int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkViewport.Buffer vp = VkViewport.calloc(1, stack);
            vp.x(0).y(0).width(width).height(height).minDepth(0f).maxDepth(1f);
            VK12.vkCmdSetViewport(cmd, 0, vp);

            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.offset().set(0, 0);
            scissor.extent().set(width, height);
            VK12.vkCmdSetScissor(cmd, 0, scissor);
        }
    }

    private void beginTerrainRendering(VkCommandBuffer cmd, Viewport<?> viewport, VkVoxyTexture colorTarget, boolean clear) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            colorTarget.transitionLayout(cmd, VK12.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            int depthLayout = this.hasStencil ? VK12.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL : VK12.VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL;
            this.depthTex.transitionLayout(cmd, depthLayout);

            VkRenderingAttachmentInfo.Buffer colorAtt = VkRenderingAttachmentInfo.calloc(1, stack);
            colorAtt.get(0).sType$Default();
            colorAtt.get(0).imageView(colorTarget.vkImageView());
            colorAtt.get(0).imageLayout(VK12.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            colorAtt.get(0).loadOp(clear ? VK12.VK_ATTACHMENT_LOAD_OP_CLEAR : VK12.VK_ATTACHMENT_LOAD_OP_LOAD);
            colorAtt.get(0).storeOp(VK12.VK_ATTACHMENT_STORE_OP_STORE);
            if (clear) {
                colorAtt.get(0).clearValue().color().float32(0, 0.0f).float32(1, 0.0f).float32(2, 0.0f).float32(3, 0.0f);
            }

            VkRenderingAttachmentInfo depthAtt = VkRenderingAttachmentInfo.calloc(stack);
            depthAtt.sType$Default();
            depthAtt.imageView(this.depthTex.vkImageView());
            depthAtt.imageLayout(depthLayout);
            depthAtt.loadOp(clear ? VK12.VK_ATTACHMENT_LOAD_OP_CLEAR : VK12.VK_ATTACHMENT_LOAD_OP_LOAD);
            depthAtt.storeOp(VK12.VK_ATTACHMENT_STORE_OP_STORE);
            if (clear) {
                depthAtt.clearValue().depthStencil().depth(this.properties.clearDepth()).stencil(1);
            }

            VkRenderingInfo renderingInfo = VkRenderingInfo.calloc(stack);
            renderingInfo.sType$Default();
            renderingInfo.renderArea().extent().set(viewport.width, viewport.height);
            renderingInfo.layerCount(1);
            renderingInfo.pColorAttachments(colorAtt);
            renderingInfo.pDepthAttachment(depthAtt);

            if (this.hasStencil) {
                renderingInfo.pStencilAttachment(depthAtt);
            }

            VkVoxyDevice.cmdBeginRendering(cmd, renderingInfo);
            this.setViewportAndScissor(cmd, viewport.width, viewport.height);

            if (this.hasStencil) {
                VK12.vkCmdSetStencilReference(cmd, VK12.VK_STENCIL_FACE_FRONT_AND_BACK, 1);
            }
        }
    }

    private void endTerrainRendering(VkCommandBuffer cmd) {
        VkVoxyDevice.cmdEndRendering(cmd);
    }

    @Override
    protected int setup(Viewport<?> viewport, int sourceDepthTexture, int srcWidth, int srcHeight) {
        this.resizeIfNeeded(viewport.width, viewport.height);
        return 0;
    }

    @Override
    protected void postOpaquePreTranslucent(Viewport<?> viewport, int sourceDepthTexture) {
    }

    @Override
    protected void finish(Viewport<?> viewport, int sourceDepthTexture, int outputFramebuffer, int srcWidth, int srcHeight) {
    }

    @Override
    public void setupExtraModelBakeryData(ModelBakerySubsystem modelService) {
        if (VitrailUtil.VITRAIL_INSTALLED) {
            modelService.factory.setCustomBlockStateMapping(me.cortex.voxy.client.vitrail.VitrailBlockIds.mapping());
        }
    }

    @Override
    public void setupAndBindOpaque(Viewport<?> viewport) {
    }

    @Override
    public void setupAndBindTranslucent(Viewport<?> viewport) {
    }

    public VkVoxyTexture getDepthTex() {
        return this.depthTex;
    }

    public long getBoundDepthView() {
        return this.boundRenderer.depthView();
    }

    public int getDepthFormat() {
        return this.depthFormat;
    }

    public boolean hasStencil() {
        return this.hasStencil;
    }

    @Override
    public void addDebug(List<String> debug) {
        super.addDebug(debug);
        debug.add("VulkanRenderPipeline: depthFormat=" + this.depthFormat + ", stencil=" + this.hasStencil + ", ssao=" + this.enableSSAO + ", depthBounds=" + this.device.depthBoundsSupported());
    }

    @Override
    public void free() {
        if (this.colourTex != null) {
            this.colourTex.free();
            this.colourTex = null;
        }
        if (this.colourSSAOTex != null) {
            this.colourSSAOTex.free();
            this.colourSSAOTex = null;
        }
        if (this.depthTex != null) {
            this.depthTex.free();
            this.depthTex = null;
        }
        if (this.ssaoComputePipeline != null) {
            this.ssaoComputePipeline.free();
            this.ssaoComputePipeline = null;
        }
        for (var pipeline : this.compositePipelines.values()) {
            pipeline.free();
        }
        this.compositePipelines.clear();
        this.freeSceneDepth();
        if (this.packGbuffer != null) {
            this.packGbuffer.free();
        }
        this.boundRenderer.free();
        this.blitUbo.free();
        this.ssaoUbo.free();
        VK12.vkDestroySampler(this.device.vkDevice(), this.nearestSampler, null);
        super.free0();
    }
}
