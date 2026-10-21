package me.cortex.voxy.client.core.backend.vk;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import dev.vitrail.glsl.PackProgram;
import dev.vitrail.pack.model.TargetName;
import dev.vitrail.pack.option.EngineDefines;
import dev.vitrail.pack.target.SamplerPlan;
import dev.vitrail.pack.target.TargetSchedule;
import dev.vitrail.render.GraphicsApi;
import dev.vitrail.render.PackValues;
import dev.vitrail.uniform.UniformCatalog;
import dev.vitrail.uniform.UniformShape;
import dev.vitrail.uniform.UniformSource;
import dev.vitrail.uniform.Val;
import dev.vitrail.uniform.WorldState;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.VkMDICSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.VkMDICViewport;
import me.cortex.voxy.client.core.util.VitrailUtil;
import me.cortex.voxy.client.vitrail.VitrailChainAccess;
import me.cortex.voxy.client.vitrail.VitrailLodTextures;
import me.cortex.voxy.client.vitrail.VitrailPackPatch;
import me.cortex.voxy.client.vitrail.VitrailShaderSource;
import me.cortex.voxy.client.vitrail.VitrailVoxy;
import me.cortex.voxy.client.vitrail.VitrailVoxyUniforms;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import org.joml.Matrix3fc;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkViewport;

import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;

public class VkPackGbufferPass {
    private static final int RING = 4;
    private static final int SCENE_SLOT = 256;
    private static final int DEPTH_OUTPUT_FORMAT = VK12.VK_FORMAT_R32_SFLOAT;

    private static final String CONVERT_VERT = """
            #version 450
            void main() {
                vec2 uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
                gl_Position = vec4(uv * 2.0f - 1.0f, 0.0f, 1.0f);
            }
            """;

    private static final String CONVERT_FRAG = """
            #version 450
            layout(binding = 0) uniform sampler2D sceneDepth;
            layout(location = 0) out float outDepth;
            void main() {
                float depth = texelFetch(sceneDepth, ivec2(gl_FragCoord.xy), 0).r;
            #ifdef USE_REVERSE_Z
                outDepth = 1.0f - depth;
            #else
                outDepth = depth;
            #endif
            }
            """;

    private static final String MERGE_FRAG = """
            #version 450
            layout(binding = 0) uniform sampler2D sceneDepth;
            layout(binding = 1) uniform sampler2D lodDepth;
            layout(push_constant) uniform Planes {
                vec4 lodInverse;
                vec4 gameProjection;
            } planes;
            layout(location = 0) out float outDepth;
            void main() {
                ivec2 texel = ivec2(gl_FragCoord.xy);
                float depth = texelFetch(sceneDepth, texel, 0).r;
                float lod = texelFetch(lodDepth, texel, 0).r;
                if (depth >= 1.0f && lod < 1.0f) {
                    vec2 view = (lod * 2.0f - 1.0f) * planes.lodInverse.xy + planes.lodInverse.zw;
                    float viewZ = view.x / view.y;
                    vec2 clip = viewZ * planes.gameProjection.xy + planes.gameProjection.zw;
                    depth = clamp(clip.x / clip.y * 0.5f + 0.5f, 0.0f, 0.99999994f);
                }
                outDepth = depth;
            }
            """;

    private final VkVoxyDevice device;
    private final RenderProperties properties;
    private final int lodDepthFormat;
    private final GpuFormat lodDepthGpuFormat;

    private final long nearestSampler;
    private final long linearSampler;
    private final long repeatSampler;
    private final long shadowSampler;
    private final VkVoxyBuffer sceneRing;
    private VkVoxyBuffer packRing;
    private int packSlot;
    private int ring;
    private long sceneOffset;
    private long packOffset;

    private Object chain;
    private boolean chainFailed;
    private VitrailPackPatch patch;
    private VitrailShaderSource source;
    private final Map<String, VkVoxyPipeline> pipelines = new HashMap<>();
    private final Set<String> brokenPipelines = new HashSet<>();

    private long sceneDepthView;
    private long sceneDepthImage;
    private GpuTexture lodDepth;
    private GpuTextureView lodDepthView;
    private GpuTexture opaqueDepth;
    private GpuTextureView opaqueDepthView;
    private GpuTexture translucentDepth;
    private GpuTextureView translucentDepthView;
    private final VkVoxyPipeline depthConvertPipeline;
    private final VkVoxyPipeline depthMergePipeline;
    private GpuTexture waterDepth;
    private GpuTextureView waterDepthView;
    private GpuTexture packDepth;
    private GpuTextureView packDepthView;
    private GpuTextureView frameSceneDepth;

    private VkMDICViewport viewport;
    private boolean drewOpaque;
    private boolean drewLastFrame;
    private PendingTranslucent pendingTranslucent;
    private static volatile boolean afterHandHook;

    private record PendingTranslucent(VkMDICSectionRenderer renderer, VkMDICViewport viewport, Object targets, TargetSchedule.Bound step,
                                      int width, int height, long sceneDepth) {
    }
    private final Set<String> said = new HashSet<>();
    private final Val value = new Val();

    public VkPackGbufferPass(VkVoxyDevice device, RenderProperties properties, int lodDepthFormat) {
        this.device = device;
        this.properties = properties;
        this.lodDepthGpuFormat = switch (lodDepthFormat) {
            case VK12.VK_FORMAT_D24_UNORM_S8_UINT -> GpuFormat.D24_UNORM_S8_UINT;
            case VK12.VK_FORMAT_D32_SFLOAT_S8_UINT -> GpuFormat.D32_FLOAT_S8_UINT;
            default -> GpuFormat.D32_FLOAT;
        };
        this.lodDepthFormat = VulkanConst.toVk(this.lodDepthGpuFormat);
        this.nearestSampler = this.createSampler(VK12.VK_FILTER_NEAREST, VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, false);
        this.linearSampler = this.createSampler(VK12.VK_FILTER_LINEAR, VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, false);
        this.repeatSampler = this.createSampler(VK12.VK_FILTER_LINEAR, VK12.VK_SAMPLER_ADDRESS_MODE_REPEAT, false);
        this.shadowSampler = this.createSampler(VK12.VK_FILTER_LINEAR, VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, true);
        this.sceneRing = VkVoxyBuffer.createHostMapped(device, (long) SCENE_SLOT * RING, VK12.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
        this.depthConvertPipeline = this.createDepthConvertPipeline();
        this.depthMergePipeline = this.createDepthMergePipeline();
    }

    private long createSampler(int filter, int addressMode, boolean compare) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack).sType$Default();
            info.magFilter(filter);
            info.minFilter(filter);
            info.mipmapMode(VK12.VK_SAMPLER_MIPMAP_MODE_NEAREST);
            info.addressModeU(addressMode);
            info.addressModeV(addressMode);
            info.addressModeW(addressMode);
            info.minLod(0);
            info.maxLod(0.25f);
            if (compare) {
                info.compareEnable(true);
                info.compareOp(VK12.VK_COMPARE_OP_LESS_OR_EQUAL);
            }
            LongBuffer pSampler = stack.callocLong(1);
            int res = VK12.vkCreateSampler(this.device.vkDevice(), info, null, pSampler);
            if (res != VK12.VK_SUCCESS) {
                throw new RuntimeException("vkCreateSampler for the shader pack lod pass failed: " + res);
            }
            return pSampler.get(0);
        }
    }

    private void sayOnce(String key, String message) {
        if (this.said.add(key)) {
            Logger.warn(message);
        }
    }

    public void prepareFrame(Viewport<?> viewport) {
        this.viewport = viewport instanceof VkMDICViewport vkViewport ? vkViewport : null;
        VitrailVoxyUniforms.update(viewport);
    }

    public boolean drewOpaque() {
        return this.drewOpaque;
    }

    public boolean drewLastFrame() {
        return this.drewLastFrame;
    }

    public static void afterHand() {
        afterHandHook = true;
    }

    public void drawPendingTranslucent() {
        PendingTranslucent pending = this.pendingTranslucent;
        this.pendingTranslucent = null;
        if (pending == null) {
            return;
        }
        GraphicsApi.suspendLevelPass();
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        this.drawTranslucent(pending.renderer(), pending.viewport(), pending.targets(), pending.step(), pending.width(), pending.height(), pending.sceneDepth(), encoder);
    }

    public void endFrame() {
        this.pendingTranslucent = null;
        this.viewport = null;
        this.drewLastFrame = this.drewOpaque;
        this.drewOpaque = false;
        this.frameSceneDepth = null;
        VitrailLodTextures.endFrame();
    }

    private static String glslType(UniformShape shape) {
        if (shape == null) {
            return null;
        }
        return switch (shape) {
            case FLOAT -> "float";
            case INT -> "int";
            case VEC2 -> "vec2";
            case VEC3 -> "vec3";
            case VEC4 -> "vec4";
            case IVEC2 -> "ivec2";
            case IVEC3 -> "ivec3";
            case IVEC4 -> "ivec4";
            case MAT3 -> "mat3";
            case MAT4 -> "mat4";
            default -> null;
        };
    }

    private boolean ensureLoaded(Object chain) {
        if (chain == this.chain) {
            return !this.chainFailed;
        }
        this.releasePipelines();
        this.chain = chain;
        this.chainFailed = true;
        this.patch = null;
        this.source = null;
        if (!VitrailVoxy.shadingEnabled()) {
            return false;
        }
        PackProgram.Chain program = VitrailChainAccess.chain(chain);
        Path packPath = VitrailChainAccess.packPath(chain);
        if (program == null || packPath == null) {
            return false;
        }
        String place = program.place();
        try {
            this.patch = VitrailPackPatch.load(packPath, VitrailChainAccess.chosen(chain), VitrailChainAccess.profile(chain), place);
        } catch (Throwable e) {
            Logger.error("Failed to read the voxy patch of shader pack " + packPath.getFileName() + ", lods are composited over the pack instead", e);
            return false;
        }
        if (this.patch == null) {
            Logger.info("Shader pack " + packPath.getFileName() + " has no voxy.json in " + (place.isEmpty() ? "its root" : place) + ", lods are composited over the pack");
            return false;
        }
        for (String note : this.patch.notes) {
            Logger.warn("voxy.json of " + packPath.getFileName() + ": " + note);
        }
        PackValues values = VitrailChainAccess.values(chain);
        UniformCatalog catalog = values != null ? values.catalog() : UniformCatalog.engine();
        this.source = VitrailShaderSource.build(this.patch, name -> glslType(catalog.natural(name)), this.properties.isReverseZ());
        for (String note : this.source.notes()) {
            Logger.warn("Voxy patch of " + packPath.getFileName() + ": " + note);
        }
        this.packSlot = Math.max(256, (this.source.blockSize() + 255) / 256 * 256);
        if (this.packRing == null || this.packRing.size() < (long) this.packSlot * RING) {
            if (this.packRing != null) {
                this.packRing.free();
            }
            this.packRing = VkVoxyBuffer.createHostMapped(this.device, (long) this.packSlot * RING, VK12.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
        }
        this.chainFailed = false;
        Logger.info("Shader pack " + packPath.getFileName() + " shades voxy lods: " + this.source.members().size() + " uniforms, " + this.source.samplers().size() + " samplers, opaque targets " + Arrays.toString(this.patch.opaqueDrawBuffers) + ", translucent targets " + Arrays.toString(this.patch.translucentDrawBuffers));
        return true;
    }

    private void releasePipelines() {
        if (this.pipelines.isEmpty()) {
            return;
        }
        VK12.vkDeviceWaitIdle(this.device.vkDevice());
        for (VkVoxyPipeline pipeline : this.pipelines.values()) {
            pipeline.free();
        }
        this.pipelines.clear();
        this.brokenPipelines.clear();
    }

    private VkVoxyPipeline pipeline(VkMDICSectionRenderer renderer, boolean translucent, boolean skirt, int[] formats) {
        return this.pipeline(renderer, translucent, skirt, formats, false);
    }

    private VkVoxyPipeline pipeline(VkMDICSectionRenderer renderer, boolean translucent, boolean skirt, int[] formats, boolean coverage) {
        String key = (translucent ? (skirt ? "skirt" : "translucent") : "opaque") + Arrays.toString(formats);
        VkVoxyPipeline existing = this.pipelines.get(key);
        if (existing != null || this.brokenPipelines.contains(key)) {
            return existing;
        }
        VkVoxyPipeline.AttachmentBlend[] blends = new VkVoxyPipeline.AttachmentBlend[formats.length];
        Arrays.fill(blends, VkVoxyPipeline.AttachmentBlend.OFF);
        if (translucent) {
            for (int i = 0; i < formats.length - 1; i++) {
                VitrailPackPatch.Blend blend = this.patch.translucentBlend(i);
                blends[i] = new VkVoxyPipeline.AttachmentBlend(blend.enabled(), blend.srcColour(), blend.dstColour(), blend.srcAlpha(), blend.dstAlpha());
            }
        }
        Map<String, String> engine = EngineDefines.table(EngineDefines.machine());
        String fragment = this.source.fragment(renderer.patchedFragmentSource(), translucent, engine, this.properties.isReverseZ());
        String vertexBase = renderer.patchedVertexSource();
        Map<String, String> defines = renderer.patchedDefines(translucent, skirt);
        defines.put("PATCHED_LOD_DEPTH_OUTPUT", Integer.toString(formats.length - (coverage ? 2 : 1)));
        if (coverage) {
            defines.put("PATCHED_COVERAGE_OUTPUT", Integer.toString(formats.length - 1));
        }
        defines.put("PATCHED_LOD_DEPTH_VALUE", this.properties.isReverseZ() ? "(1.0-gl_FragCoord.z)" : "gl_FragCoord.z");
        defines.put("PATCHED_SCENE_DEPTH_TEST", "1");
        defines.put("NO_BOUNDING_DEPTH_TEST", "1");
        defines.put("PATCHED_SCENE_DEPTH_CLEAR", Float.toString(this.properties.clearDepth()));
        int samplerCount = this.source.samplerBindings();
        String name = "voxy_pack_" + (translucent ? (skirt ? "skirt" : "translucent") : "opaque");
        VkVoxyPipeline created = null;
        if (this.patch.taa != null) {
            Map<String, String> taaDefines = new HashMap<>(defines);
            taaDefines.put("TAA_PATCH", "1");
            try {
                created = renderer.createPatchedPipeline(name, this.source.vertex(vertexBase, this.patch.taa, this.patch.jsonDefines), fragment, taaDefines,
                        formats, blends, this.lodDepthFormat, skirt, VitrailShaderSource.PACK_UNIFORM_BINDING, VitrailShaderSource.FIRST_SAMPLER_BINDING, samplerCount);
            } catch (RuntimeException e) {
                Logger.warn("The taa offset of the shader pack does not compile for voxy lods, they are drawn without it: " + e.getMessage());
            }
        }
        if (created == null) {
            try {
                created = renderer.createPatchedPipeline(name, vertexBase, fragment, defines,
                        formats, blends, this.lodDepthFormat, skirt, VitrailShaderSource.PACK_UNIFORM_BINDING, VitrailShaderSource.FIRST_SAMPLER_BINDING, samplerCount);
            } catch (RuntimeException e) {
                Logger.error("The voxy patch of the shader pack does not compile, lods are composited over the pack instead", e);
                this.brokenPipelines.add(key);
                return null;
            }
        }
        this.pipelines.put(key, created);
        return created;
    }

    private void ensureTextures(int width, int height) {
        if (this.lodDepth != null && this.lodDepth.getWidth(0) == width && this.lodDepth.getHeight(0) == height) {
            return;
        }
        this.freeTextures();
        GpuDevice gpu = RenderSystem.getDevice();
        this.lodDepth = gpu.createTexture("Voxy shader pack lod depth", GpuTexture.USAGE_RENDER_ATTACHMENT, this.lodDepthGpuFormat, width, height, 1, 1);
        this.lodDepthView = gpu.createTextureView(this.lodDepth);
        this.opaqueDepth = gpu.createTexture("Voxy vxDepthTexOpaque", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC, GpuFormat.R32_FLOAT, width, height, 1, 1);
        this.opaqueDepthView = gpu.createTextureView(this.opaqueDepth);
        this.translucentDepth = gpu.createTexture("Voxy vxDepthTexTrans", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.R32_FLOAT, width, height, 1, 1);
        this.translucentDepthView = gpu.createTextureView(this.translucentDepth);
    }

    private VkVoxyPipeline createDepthConvertPipeline() {
        Map<String, String> defines = new HashMap<>();
        if (this.properties.isReverseZ()) {
            defines.put("USE_REVERSE_Z", "1");
        }
        long vertModule = this.device.compiler().compileShaderModule(this.device.vkDevice(),
                org.lwjgl.util.shaderc.Shaderc.shaderc_vertex_shader, "voxy_pack_depth.vert", CONVERT_VERT, defines);
        long fragModule = this.device.compiler().compileShaderModule(this.device.vkDevice(),
                org.lwjgl.util.shaderc.Shaderc.shaderc_fragment_shader, "voxy_pack_depth.frag", CONVERT_FRAG, defines);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(1, stack);
            bindings.get(0).binding(0).descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_FRAGMENT_BIT);
            return VkVoxyPipeline.createGraphics(this.device, vertModule, fragModule, bindings,
                    DEPTH_OUTPUT_FORMAT, 0, false, false, false, VK12.VK_COMPARE_OP_ALWAYS, false, VK12.VK_CULL_MODE_NONE);
        } finally {
            VK12.vkDestroyShaderModule(this.device.vkDevice(), vertModule, null);
            VK12.vkDestroyShaderModule(this.device.vkDevice(), fragModule, null);
        }
    }

    private VkVoxyPipeline createDepthMergePipeline() {
        long vertModule = this.device.compiler().compileShaderModule(this.device.vkDevice(),
                org.lwjgl.util.shaderc.Shaderc.shaderc_vertex_shader, "voxy_pack_depth.vert", CONVERT_VERT, new HashMap<>());
        long fragModule = this.device.compiler().compileShaderModule(this.device.vkDevice(),
                org.lwjgl.util.shaderc.Shaderc.shaderc_fragment_shader, "voxy_pack_water_depth.frag", MERGE_FRAG, new HashMap<>());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
            bindings.get(0).binding(0).descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_FRAGMENT_BIT);
            bindings.get(1).binding(1).descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_FRAGMENT_BIT);
            return VkVoxyPipeline.createGraphics(this.device, vertModule, fragModule, bindings,
                    DEPTH_OUTPUT_FORMAT, 0, false, false, false, VK12.VK_COMPARE_OP_ALWAYS, false, VK12.VK_CULL_MODE_NONE,
                    32, VK12.VK_SHADER_STAGE_FRAGMENT_BIT);
        } finally {
            VK12.vkDestroyShaderModule(this.device.vkDevice(), vertModule, null);
            VK12.vkDestroyShaderModule(this.device.vkDevice(), fragModule, null);
        }
    }

    private GpuTextureView mergeWaterDepth(CommandEncoder encoder) {
        if (!(this.frameSceneDepth instanceof VulkanGpuTextureView scene) || !(this.opaqueDepthView instanceof VulkanGpuTextureView lod)) {
            return null;
        }
        int width = this.frameSceneDepth.getWidth(0);
        int height = this.frameSceneDepth.getHeight(0);
        if (this.opaqueDepthView.getWidth(0) != width || this.opaqueDepthView.getHeight(0) != height) {
            this.sayOnce("waterDepthSize", "The shader pack draws at another size than the game, its water does not see the lod depth");
            return null;
        }
        if (this.waterDepth == null || this.waterDepth.getWidth(0) != width || this.waterDepth.getHeight(0) != height) {
            this.freeWaterDepth();
            GpuDevice gpu = RenderSystem.getDevice();
            this.waterDepth = gpu.createTexture("Voxy shader pack water depth", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.R32_FLOAT, width, height, 1, 1);
            this.waterDepthView = gpu.createTextureView(this.waterDepth);
        }
        Matrix4f lodInverse = VitrailVoxyUniforms.projectionInverse();
        Matrix4f game = VitrailVoxyUniforms.toGl(this.properties, new Matrix4f(Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState.projectionMatrix));
        boolean drawn = false;
        try (RenderPass pass = encoder.createRenderPass(() -> "Voxy water depth for shader pack", this.waterDepthView, Optional.empty());
             MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cmd = VulkanRenderPipeline.commandBuffer(pass);
            if (cmd != null) {
                setViewportAndScissor(cmd, width, height);
                this.depthMergePipeline.bind(cmd);
                this.depthMergePipeline.pushImage(cmd, 0, scene.vkImageView(), this.nearestSampler, VK12.VK_IMAGE_LAYOUT_GENERAL);
                this.depthMergePipeline.pushImage(cmd, 1, lod.vkImageView(), this.nearestSampler, VK12.VK_IMAGE_LAYOUT_GENERAL);
                java.nio.ByteBuffer planes = stack.malloc(32);
                planes.putFloat(0, lodInverse.m22()).putFloat(4, lodInverse.m23()).putFloat(8, lodInverse.m32()).putFloat(12, lodInverse.m33());
                planes.putFloat(16, game.m22()).putFloat(20, game.m23()).putFloat(24, game.m32()).putFloat(28, game.m33());
                this.depthMergePipeline.pushConstants(cmd, VK12.VK_SHADER_STAGE_FRAGMENT_BIT, 0, planes);
                this.depthMergePipeline.draw(cmd, 3);
                drawn = true;
            }
        }
        return drawn ? this.waterDepthView : null;
    }

    private void freeWaterDepth() {
        if (this.waterDepthView != null) this.waterDepthView.close();
        if (this.waterDepth != null) this.waterDepth.close();
        this.waterDepthView = null;
        this.waterDepth = null;
    }

    private GpuTextureView convertSceneDepth(CommandEncoder encoder, GpuTexture scene, long sceneDepth) {
        int width = scene.getWidth(0);
        int height = scene.getHeight(0);
        if (this.packDepth == null || this.packDepth.getWidth(0) != width || this.packDepth.getHeight(0) != height) {
            this.freePackDepth();
            GpuDevice gpu = RenderSystem.getDevice();
            this.packDepth = gpu.createTexture("Voxy shader pack scene depth", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.R32_FLOAT, width, height, 1, 1);
            this.packDepthView = gpu.createTextureView(this.packDepth);
        }
        boolean drawn = false;
        try (RenderPass pass = encoder.createRenderPass(() -> "Voxy scene depth for shader pack", this.packDepthView, Optional.empty())) {
            VkCommandBuffer cmd = VulkanRenderPipeline.commandBuffer(pass);
            if (cmd != null) {
                setViewportAndScissor(cmd, width, height);
                this.depthConvertPipeline.bind(cmd);
                this.depthConvertPipeline.pushImage(cmd, 0, sceneDepth, this.nearestSampler, VK12.VK_IMAGE_LAYOUT_GENERAL);
                this.depthConvertPipeline.draw(cmd, 3);
                drawn = true;
            }
        }
        return drawn ? this.packDepthView : null;
    }

    private void freePackDepth() {
        if (this.packDepthView != null) this.packDepthView.close();
        if (this.packDepth != null) this.packDepth.close();
        this.packDepthView = null;
        this.packDepth = null;
        this.frameSceneDepth = null;
    }

    private void freeTextures() {
        VitrailLodTextures.endFrame();
        if (this.lodDepthView != null) this.lodDepthView.close();
        if (this.lodDepth != null) this.lodDepth.close();
        if (this.opaqueDepthView != null) this.opaqueDepthView.close();
        if (this.opaqueDepth != null) this.opaqueDepth.close();
        if (this.translucentDepthView != null) this.translucentDepthView.close();
        if (this.translucentDepth != null) this.translucentDepth.close();
        this.lodDepthView = null;
        this.lodDepth = null;
        this.opaqueDepthView = null;
        this.opaqueDepth = null;
        this.translucentDepthView = null;
        this.translucentDepth = null;
    }

    private static TargetSchedule.Bound geometryStep(TargetSchedule schedule, boolean afterDeferred) {
        if (schedule == null) {
            return null;
        }
        String preferred = afterDeferred ? "gbuffers_water" : "gbuffers_terrain";
        Optional<TargetSchedule.Bound> step = schedule.step(preferred).filter(one -> !one.fullscreen());
        if (step.isEmpty()) {
            step = schedule.steps().stream().filter(one -> !one.fullscreen()).findFirst();
        }
        if (step.isEmpty()) {
            return null;
        }
        if (!afterDeferred) {
            return step.get();
        }
        return schedule.stepAfterDeferred(step.get().program()).orElse(step.get());
    }

    private static TargetSchedule.Side side(TargetSchedule.Bound step, int index) {
        return step == null ? TargetSchedule.Side.MAIN : step.read(index);
    }

    private GpuTextureView[] targetViews(Object targets, int[] indices, TargetSchedule.Bound step) {
        GpuTextureView[] views = new GpuTextureView[indices.length];
        for (int i = 0; i < indices.length; i++) {
            views[i] = VitrailChainAccess.view(targets, indices[i], side(step, indices[i]));
            if (!(views[i] instanceof VulkanGpuTextureView)) {
                this.sayOnce("target" + indices[i], "The shader pack has no colortex" + indices[i] + " for voxy lods to draw into, lods are composited over the pack instead");
                return null;
            }
        }
        return views;
    }

    private static int[] formats(GpuTextureView[] views) {
        int[] formats = new int[views.length + 1];
        for (int i = 0; i < views.length; i++) {
            formats[i] = VulkanConst.toVk(views[i].texture().getFormat());
        }
        formats[views.length] = DEPTH_OUTPUT_FORMAT;
        return formats;
    }

    private long sceneDepthView(GpuTexture depth) {
        if (!(depth instanceof VulkanGpuTexture vkDepth)) {
            return 0;
        }
        long image = vkDepth.vkImage();
        if (image == this.sceneDepthImage && this.sceneDepthView != 0) {
            return this.sceneDepthView;
        }
        this.freeSceneDepthView();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack).sType$Default();
            info.image(image);
            info.viewType(VK12.VK_IMAGE_VIEW_TYPE_2D);
            info.format(VulkanConst.toVk(depth.getFormat()));
            info.subresourceRange().aspectMask(VK12.VK_IMAGE_ASPECT_DEPTH_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            LongBuffer pView = stack.callocLong(1);
            int res = VK12.vkCreateImageView(this.device.vkDevice(), info, null, pView);
            if (res != VK12.VK_SUCCESS) {
                this.sayOnce("sceneDepthView", "Could not make a depth view of the scene for voxy lods: " + res);
                return 0;
            }
            this.sceneDepthView = pView.get(0);
            this.sceneDepthImage = image;
            return this.sceneDepthView;
        }
    }

    private void freeSceneDepthView() {
        if (this.sceneDepthView != 0) {
            VK12.vkDeviceWaitIdle(this.device.vkDevice());
            VK12.vkDestroyImageView(this.device.vkDevice(), this.sceneDepthView, null);
            this.sceneDepthView = 0;
            this.sceneDepthImage = 0;
        }
    }

    private void writeUniforms(VkMDICSectionRenderer renderer, VkMDICViewport viewport, int targetWidth, int targetHeight, GpuTexture sceneTexture) {
        this.ring = (this.ring + 1) % RING;
        this.sceneOffset = (long) this.ring * SCENE_SLOT;
        this.packOffset = (long) this.ring * this.packSlot;
        long scene = this.sceneRing.mappedAddress() + this.sceneOffset;
        MemoryUtil.memSet(scene, 0, SCENE_SLOT);
        renderer.writeSceneUniform(viewport, scene);
        this.sceneRing.flush(this.sceneOffset, SCENE_SLOT);

        long pack = this.packRing.mappedAddress() + this.packOffset;
        MemoryUtil.memSet(pack, 0, this.packSlot);
        PackValues values = VitrailChainAccess.values(this.chain);
        if (values != null) {
            UniformCatalog catalog = values.catalog();
            WorldState world = values.world();
            for (VitrailShaderSource.Member member : this.source.members()) {
                UniformSource uniform = catalog.source(member.name());
                if (uniform == null) {
                    continue;
                }
                try {
                    uniform.read(world, this.value);
                } catch (Throwable e) {
                    this.sayOnce("uniform" + member.name(), "Uniform " + member.name() + " of the shader pack could not be read for voxy lods: " + e);
                    continue;
                }
                write(member.type(), this.value, pack + member.offset());
            }
        }
        VitrailShaderSource.Member scale = this.source.member(VitrailShaderSource.SCENE_DEPTH_SCALE);
        if (scale != null && sceneTexture != null) {
            MemoryUtil.memPutFloat(pack + scale.offset(), sceneTexture.getWidth(0) / (float) targetWidth);
            MemoryUtil.memPutFloat(pack + scale.offset() + 4, sceneTexture.getHeight(0) / (float) targetHeight);
        }
        if (this.said.add("uniformValues")) {
            Logger.info("Shader pack uniforms for voxy lods in the first frame: " + this.describeScalars(pack));
        }
        this.packRing.flush(this.packOffset, this.packSlot);
    }

    private String describeScalars(long pack) {
        StringBuilder out = new StringBuilder();
        for (VitrailShaderSource.Member member : this.source.members()) {
            int components = VitrailShaderSource.components(member.type());
            if (components == 0 || components > 4) {
                continue;
            }
            boolean integral = VitrailShaderSource.integral(member.type());
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(member.name()).append('=');
            if (components > 1) {
                out.append('(');
            }
            for (int i = 0; i < components; i++) {
                if (i > 0) {
                    out.append(' ');
                }
                long at = pack + member.offset() + i * 4L;
                if (integral) {
                    out.append(MemoryUtil.memGetInt(at));
                } else {
                    out.append(String.format(java.util.Locale.ROOT, "%.4g", MemoryUtil.memGetFloat(at)));
                }
            }
            if (components > 1) {
                out.append(')');
            }
        }
        return out.toString();
    }

    private static void write(String type, Val value, long ptr) {
        if (type.equals("mat4")) {
            if (value.rank() == 16) {
                value.mat4().getToAddress(ptr);
            }
            return;
        }
        if (type.equals("mat3")) {
            if (value.rank() == 9) {
                Matrix3fc matrix = value.mat3();
                for (int column = 0; column < 3; column++) {
                    for (int row = 0; row < 3; row++) {
                        MemoryUtil.memPutFloat(ptr + column * 16L + row * 4L, matrix.get(column, row));
                    }
                }
            }
            return;
        }
        int components = VitrailShaderSource.components(type);
        boolean integral = VitrailShaderSource.integral(type);
        boolean bool = type.startsWith("b");
        for (int i = 0; i < components; i++) {
            if (bool) {
                MemoryUtil.memPutInt(ptr + i * 4L, value.f(i) != 0.0f ? 1 : 0);
            } else if (integral) {
                MemoryUtil.memPutInt(ptr + i * 4L, value.i(i));
            } else {
                MemoryUtil.memPutFloat(ptr + i * 4L, value.f(i));
            }
        }
    }

    private GpuTextureView sampled(VitrailShaderSource.Sampler sampler, Object targets, TargetSchedule.Bound step,
                                   boolean translucent, int[] attached) {
        String name = sampler.name();
        GpuTextureView white = VitrailChainAccess.white(targets);
        if (name.equals(VitrailLodTextures.OPAQUE)) {
            return translucent ? this.opaqueDepthView : white;
        }
        if (name.equals(VitrailLodTextures.TRANSLUCENT)) {
            return white;
        }
        SamplerPlan.Kind kind = SamplerPlan.classify(name);
        GpuTextureView view = switch (kind) {
            case COLORTEX -> {
                OptionalInt index = TargetName.index(name);
                if (index.isEmpty()) {
                    yield null;
                }
                for (int one : attached) {
                    if (one == index.getAsInt()) {
                        yield VitrailChainAccess.black(targets);
                    }
                }
                yield VitrailChainAccess.view(targets, index.getAsInt(), side(step, index.getAsInt()));
            }
            case DEPTH -> {
                if (this.frameSceneDepth != null) {
                    yield this.frameSceneDepth;
                }
                if (!SamplerPlan.depthCopy(name)) {
                    yield white;
                }
                GpuTextureView opaque = VitrailChainAccess.opaqueDepth(targets);
                yield opaque != null ? opaque : white;
            }
            case SHADOW_DEPTH -> VitrailChainAccess.shadowDepth(targets, name.equals("shadowtex1") || name.equals("watershadow"));
            case SHADOW_COLOUR -> {
                String digits = name.substring("shadowcolor".length());
                int index = 0;
                try {
                    index = digits.isEmpty() ? 0 : Integer.parseInt(digits);
                } catch (NumberFormatException ignored) {
                }
                yield VitrailChainAccess.shadowColour(targets, index);
            }
            case NOISE -> VitrailChainAccess.noise(targets);
            case DISTANT_DEPTH -> white;
            default -> {
                this.sayOnce("sampler" + name, "Sampler " + name + " of the voxy patch has nothing to read here, it reads black");
                yield VitrailChainAccess.black(targets);
            }
        };
        if (view == null || view.isClosed()) {
            view = kind == SamplerPlan.Kind.COLORTEX || kind == SamplerPlan.Kind.NOISE ? VitrailChainAccess.black(targets) : white;
        }
        return view;
    }

    private long samplerHandle(VitrailShaderSource.Sampler sampler) {
        if (sampler.type().contains("Shadow")) {
            return this.shadowSampler;
        }
        return switch (SamplerPlan.classify(sampler.name())) {
            case NOISE -> this.repeatSampler;
            case SHADOW_DEPTH, SHADOW_COLOUR -> this.linearSampler;
            default -> this.nearestSampler;
        };
    }

    private boolean bindPack(VkCommandBuffer cmd, VkVoxyPipeline pipe, Object targets, TargetSchedule.Bound step,
                             boolean translucent, int[] attached, long sceneDepth) {
        if (!this.source.members().isEmpty()) {
            pipe.pushUniformBuffer(cmd, VitrailShaderSource.PACK_UNIFORM_BINDING, this.packRing.vkBuffer(), this.packOffset, this.source.blockSize());
        }
        pipe.pushImage(cmd, this.source.sceneDepthBinding(), sceneDepth, this.nearestSampler, VK12.VK_IMAGE_LAYOUT_GENERAL);
        for (VitrailShaderSource.Sampler sampler : this.source.samplers()) {
            GpuTextureView view = this.sampled(sampler, targets, step, translucent, attached);
            if (!(view instanceof VulkanGpuTextureView vkView)) {
                this.sayOnce("unbound" + sampler.name(), "Sampler " + sampler.name() + " of the voxy patch could not be bound, lods are not drawn into the pack this frame");
                return false;
            }
            pipe.pushImage(cmd, sampler.binding(), vkView.vkImageView(), this.samplerHandle(sampler), VK12.VK_IMAGE_LAYOUT_GENERAL);
        }
        return true;
    }

    private static void setViewportAndScissor(VkCommandBuffer cmd, int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkViewport.Buffer vp = VkViewport.calloc(1, stack);
            vp.x(0).y(0).width(width).height(height).minDepth(0f).maxDepth(1f);
            VK12.vkCmdSetViewport(cmd, 0, vp);
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.offset().set(0, 0);
            scissor.extent().set(width, height);
            VK12.vkCmdSetScissor(cmd, 0, scissor);
            VK12.vkCmdSetStencilReference(cmd, VK12.VK_STENCIL_FACE_FRONT_AND_BACK, 0);
        }
    }

    public boolean drawOpaque(VkMDICSectionRenderer renderer) {
        this.drewOpaque = false;
        VkMDICViewport viewport = this.viewport;
        if (viewport == null || !renderer.hasGeometry() || !VitrailUtil.packActive()) {
            return false;
        }
        Object chain = VitrailChainAccess.active();
        if (chain == null || !this.ensureLoaded(chain)) {
            return false;
        }
        Object targets = VitrailChainAccess.targets(chain);
        if (targets == null) {
            return false;
        }
        TargetSchedule.Bound step = geometryStep(VitrailChainAccess.schedule(targets), false);
        GpuTextureView[] colours = this.targetViews(targets, this.patch.opaqueDrawBuffers, step);
        if (colours == null || colours.length == 0) {
            return false;
        }
        int width = colours[0].getWidth(0);
        int height = colours[0].getHeight(0);
        GpuTexture scene = Minecraft.getInstance().gameRenderer.mainRenderTarget().getDepthTexture();
        long sceneDepth = scene == null ? 0 : this.sceneDepthView(scene);
        if (sceneDepth == 0) {
            return false;
        }
        this.ensureTextures(width, height);
        GpuTextureView coverage = VitrailChainAccess.coverage(targets);
        if (coverage != null && (coverage.getWidth(0) != width || coverage.getHeight(0) != height)) {
            coverage = null;
        }
        int[] opaqueFormats = formats(colours);
        if (coverage != null) {
            opaqueFormats = Arrays.copyOf(opaqueFormats, opaqueFormats.length + 1);
            opaqueFormats[opaqueFormats.length - 1] = VulkanConst.toVk(coverage.texture().getFormat());
        }
        VkVoxyPipeline pipe = this.pipeline(renderer, false, false, opaqueFormats, coverage != null);
        if (pipe == null) {
            return false;
        }
        this.writeUniforms(renderer, viewport, width, height, scene);

        GraphicsApi.suspendLevelPass();
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        RenderPassDescriptor.Builder descriptor = RenderPassDescriptor.builder(() -> "Voxy lods into shader pack");
        for (GpuTextureView colour : colours) {
            descriptor.withColorAttachment(colour, VitrailChainAccess.takeClear(targets, colour));
        }
        Optional<org.joml.Vector4fc> coverageClear = coverage != null ? VitrailChainAccess.takeClear(targets, coverage) : Optional.empty();
        VitrailChainAccess.flushPending(targets, encoder);
        this.frameSceneDepth = this.convertSceneDepth(encoder, scene, sceneDepth);
        descriptor.withColorAttachment(this.opaqueDepthView, Optional.of(new Vector4f(1.0f, 1.0f, 1.0f, 1.0f)));
        if (coverage != null) {
            descriptor.withColorAttachment(coverage, coverageClear);
        }
        descriptor.withDepthAttachment(this.lodDepthView, OptionalDouble.of(this.properties.clearDepth()));
        boolean drawn = false;
        try (RenderPass pass = encoder.createRenderPass(descriptor.build())) {
            VkCommandBuffer cmd = VulkanRenderPipeline.commandBuffer(pass);
            if (cmd != null) {
                setViewportAndScissor(cmd, width, height);
                renderer.bindPatched(viewport, cmd, pipe, this.sceneRing.vkBuffer(), this.sceneOffset, SCENE_SLOT);
                if (this.bindPack(cmd, pipe, targets, step, false, this.patch.opaqueDrawBuffers, sceneDepth)) {
                    renderer.drawPatchedOpaque(viewport, cmd, pipe);
                    drawn = true;
                }
            }
        }
        if (!drawn) {
            return false;
        }
        VitrailLodTextures.opaque(this.opaqueDepthView);
        VitrailLodTextures.water(this.mergeWaterDepth(encoder));
        this.drewOpaque = true;
        if (!this.said.contains("drawn")) {
            this.said.add("drawn");
            Logger.info("Voxy lods are drawn into the shader pack targets " + Arrays.toString(this.patch.opaqueDrawBuffers) + " at " + width + "x" + height);
        }
        if (afterHandHook) {
            this.pendingTranslucent = new PendingTranslucent(renderer, viewport, targets, step, width, height, sceneDepth);
        } else {
            this.drawTranslucent(renderer, viewport, targets, step, width, height, sceneDepth, encoder);
        }
        return true;
    }

    private void drawTranslucent(VkMDICSectionRenderer renderer, VkMDICViewport viewport, Object targets, TargetSchedule.Bound step,
                                 int width, int height, long sceneDepth, CommandEncoder encoder) {
        if (!this.source.hasTranslucent() || this.patch.translucentDrawBuffers.length == 0) {
            return;
        }
        GpuTextureView[] colours = this.targetViews(targets, this.patch.translucentDrawBuffers, step);
        if (colours == null || colours.length == 0) {
            return;
        }
        if (colours[0].getWidth(0) != width || colours[0].getHeight(0) != height) {
            this.sayOnce("translucentSize", "The translucent targets of the shader pack are not the size of its opaque ones, translucent lods are not drawn into it");
            return;
        }
        int[] formats = formats(colours);
        VkVoxyPipeline pipe = this.pipeline(renderer, true, false, formats);
        VkVoxyPipeline skirt = this.pipeline(renderer, true, true, formats);
        if (pipe == null) {
            return;
        }

        encoder.copyTextureToTexture(this.opaqueDepth, this.translucentDepth, 0, 0, 0, 0, 0, width, height);
        RenderPassDescriptor.Builder descriptor = RenderPassDescriptor.builder(() -> "Voxy translucent lods into shader pack");
        for (GpuTextureView colour : colours) {
            descriptor.withColorAttachment(colour, VitrailChainAccess.takeClear(targets, colour));
        }
        VitrailChainAccess.flushPending(targets, encoder);
        descriptor.withColorAttachment(this.translucentDepthView);
        descriptor.withDepthAttachment(this.lodDepthView);
        boolean drawn = false;
        try (RenderPass pass = encoder.createRenderPass(descriptor.build())) {
            VkCommandBuffer cmd = VulkanRenderPipeline.commandBuffer(pass);
            if (cmd != null) {
                setViewportAndScissor(cmd, width, height);
                renderer.bindPatched(viewport, cmd, pipe, this.sceneRing.vkBuffer(), this.sceneOffset, SCENE_SLOT);
                if (this.bindPack(cmd, pipe, targets, step, true, this.patch.translucentDrawBuffers, sceneDepth)) {
                    renderer.drawPatchedTranslucent(viewport, cmd, pipe);
                    drawn = true;
                    if (skirt != null) {
                        renderer.bindPatched(viewport, cmd, skirt, this.sceneRing.vkBuffer(), this.sceneOffset, SCENE_SLOT);
                        if (this.bindPack(cmd, skirt, targets, step, true, this.patch.translucentDrawBuffers, sceneDepth)) {
                            renderer.drawPatchedTranslucent(viewport, cmd, skirt);
                        }
                    }
                }
            }
        }
        if (drawn) {
            VitrailLodTextures.translucent(this.translucentDepthView);
            if (!this.said.contains("drawnTranslucent")) {
                this.said.add("drawnTranslucent");
                Logger.info("Translucent voxy lods are drawn into the shader pack targets " + Arrays.toString(this.patch.translucentDrawBuffers) + " before its deferred stage");
            }
        }
    }

    public void free() {
        this.releasePipelines();
        this.freeTextures();
        this.freePackDepth();
        this.freeWaterDepth();
        this.freeSceneDepthView();
        this.depthConvertPipeline.free();
        this.depthMergePipeline.free();
        this.sceneRing.free();
        if (this.packRing != null) {
            this.packRing.free();
            this.packRing = null;
        }
        VK12.vkDestroySampler(this.device.vkDevice(), this.nearestSampler, null);
        VK12.vkDestroySampler(this.device.vkDevice(), this.linearSampler, null);
        VK12.vkDestroySampler(this.device.vkDevice(), this.repeatSampler, null);
        VK12.vkDestroySampler(this.device.vkDevice(), this.shadowSampler, null);
    }
}
