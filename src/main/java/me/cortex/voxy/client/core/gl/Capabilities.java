package me.cortex.voxy.client.core.gl;

import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.common.Logger;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.util.Locale;

import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL15.glDeleteBuffers;
import static org.lwjgl.opengl.GL30.GL_DEPTH_STENCIL;
import static org.lwjgl.opengl.GL30C.GL_MAP_READ_BIT;
import static org.lwjgl.opengl.GL32.glGetInteger64;
import static org.lwjgl.opengl.GL43C.GL_MAX_SHADER_STORAGE_BLOCK_SIZE;
import static org.lwjgl.opengl.GL44.GL_DYNAMIC_STORAGE_BIT;
import static org.lwjgl.opengl.GL45.glClearNamedFramebufferfi;
import static org.lwjgl.opengl.GL45C.*;
import static org.lwjgl.opengl.GL45C.GL_FLOAT;
import static org.lwjgl.opengl.GL45C.GL_RED;
import static org.lwjgl.opengl.GL45C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL45C.GL_VENDOR;
import static org.lwjgl.opengl.GL45C.GL_VERSION;
import static org.lwjgl.opengl.GL45C.glDeleteTextures;
import static org.lwjgl.opengl.GL45C.glFinish;
import static org.lwjgl.opengl.GL45C.glGetInteger;
import static org.lwjgl.opengl.GL45C.glGetString;
import static org.lwjgl.opengl.NVXGPUMemoryInfo.*;

public class Capabilities {

    public static final Capabilities INSTANCE = new Capabilities();

    public final boolean repFragTest;
    public final boolean meshShaders;
    public final boolean INT64_t;
    public final long ssboMaxSize;
    public final int ssboBindingAlignment;
    public final boolean isMesa;
    public final boolean canQueryGpuMemory;
    public final long totalDedicatedMemory;
    public final long totalDynamicMemory;
    public final boolean compute;
    public final boolean indirectParameters;
    public final boolean isIntel;
    public final boolean subgroup;
    public final boolean sparseBuffer;
    public final boolean isNvidia;
    public final boolean isAmd;
    public final boolean nvBarryCoords;
    public final boolean hasBrokenDepthSampler;

    public Capabilities() {
        if (me.cortex.voxy.client.core.backend.vk.VkVoxyDevice.isVulkanActive()) {
            var dev = me.cortex.voxy.client.core.backend.vk.VkVoxyDevice.getInstance();
            var info = dev.renderPearlDevice().getDeviceInfo();
            this.sparseBuffer = false;
            this.compute = true;
            this.indirectParameters = true;
            this.repFragTest = false;
            this.meshShaders = false;
            this.canQueryGpuMemory = true;
            this.INT64_t = true;
            this.subgroup = true;
            this.ssboMaxSize = info.limits().maxMemoryAllocationSize();
            this.ssboBindingAlignment = info.limits().minUniformOffsetAlignment();
            this.isMesa = false;
            var vendor = info.vendorName().toLowerCase(java.util.Locale.ROOT);
            this.isIntel = vendor.contains("intel");
            this.isNvidia = vendor.contains("nvidia");
            this.isAmd = vendor.contains("amd") || vendor.contains("radeon");
            this.totalDedicatedMemory = 2L * 1024 * 1024 * 1024;
            this.totalDynamicMemory = 2L * 1024 * 1024 * 1024;
            this.nvBarryCoords = false;
            this.hasBrokenDepthSampler = false;
            return;
        }
        var cap = GL.getCapabilities();
        this.sparseBuffer = cap.GL_ARB_sparse_buffer;
        this.compute = cap.glDispatchComputeIndirect != 0;
        this.indirectParameters = cap.glMultiDrawElementsIndirectCountARB != 0;
        this.repFragTest = cap.GL_NV_representative_fragment_test;
        this.meshShaders = cap.GL_NV_mesh_shader;
        this.canQueryGpuMemory = cap.GL_NVX_gpu_memory_info;
        this.INT64_t = testShaderCompilesOk(ShaderType.COMPUTE, """
                #version 430
                #extension GL_ARB_gpu_shader_int64 : require
                layout(local_size_x=32) in;
                void main() {
                    uint64_t a = 1234;
                }
                """);
        if (cap.GL_KHR_shader_subgroup) {
            this.subgroup = testShaderCompilesOk(ShaderType.COMPUTE, """
                #version 430
                #extension GL_KHR_shader_subgroup_basic : require
                #extension GL_KHR_shader_subgroup_arithmetic : require
                layout(local_size_x=32) in;
                void main() {
                    uint a = subgroupExclusiveAdd(gl_LocalInvocationIndex);
                }
                """);
        } else {
            this.subgroup = false;
        }

        this.ssboMaxSize = glGetInteger64(GL_MAX_SHADER_STORAGE_BLOCK_SIZE);
        this.ssboBindingAlignment = glGetInteger(GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT);

        this.isMesa = glGetString(GL_VERSION).toLowerCase(Locale.ROOT).contains("mesa");
        var vendor = glGetString(GL_VENDOR).toLowerCase(Locale.ROOT);
        this.isIntel = vendor.contains("intel");
        this.isNvidia = vendor.contains("nvidia");
        this.isAmd = vendor.contains("amd")||vendor.contains("radeon");

        if (this.canQueryGpuMemory) {
            this.totalDedicatedMemory = glGetInteger64(GL_GPU_MEMORY_INFO_DEDICATED_VIDMEM_NVX)*1024;
            this.totalDynamicMemory = (glGetInteger64(GL_GPU_MEMORY_INFO_TOTAL_AVAILABLE_MEMORY_NVX)*1024) - this.totalDedicatedMemory;
        } else {
            this.totalDedicatedMemory = -1;
            this.totalDynamicMemory = -1;
        }

        this.nvBarryCoords = cap.GL_NV_fragment_shader_barycentric;

        if (this.compute&&this.isAmd) {
            this.hasBrokenDepthSampler = testDepthSampler();
            if (this.hasBrokenDepthSampler) {
                throw new IllegalStateException("it bork, amd is bork");
            }
        } else {
            this.hasBrokenDepthSampler = false;
        }
    }

    public static void init() {
    }

    private static boolean testDepthSampler() {
        String src = """
                #version 460 core
                layout(local_size_x=16,local_size_y=16) in;
                
                layout(binding = 0) uniform sampler2D depthSampler;
                layout(binding = 1) buffer OutData {
                    float[] outData;
                };
                
                layout(location = 2) uniform int dynamicSampleThing;
                layout(location = 3) uniform float sampleData;
                
                void main() {
                    if (abs(texelFetch(depthSampler, ivec2(gl_GlobalInvocationID.xy), dynamicSampleThing).r-sampleData)>0.000001f) {
                        outData[0] = 1.0;
                    }
                }
                """;
        int program = GL20C.glCreateProgram();
        {
            int shader = GL20C.glCreateShader(ShaderType.COMPUTE.gl);
            GL20C.glShaderSource(shader, src);
            GL20C.glCompileShader(shader);
            if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) != 1) {
                GL20C.glDeleteShader(shader);
                throw new IllegalStateException("Shader compile fail");
            }
            GL20C.glAttachShader(program, shader);
            GL20C.glLinkProgram(program);
            glDeleteShader(shader);
        }

        int buffer = glCreateBuffers();
        glNamedBufferStorage(buffer, 4096, GL_DYNAMIC_STORAGE_BIT|GL_MAP_READ_BIT);

        int tex = glCreateTextures(GL_TEXTURE_2D);
        glTextureStorage2D(tex, 2, GL_DEPTH24_STENCIL8, 256, 256);
        glTextureParameteri(tex, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTextureParameteri(tex, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

        int fb = glCreateFramebuffers();
        boolean isCorrect = true;
        for (int lvl = 0; lvl <= 1; lvl++) {
            glNamedFramebufferTexture(fb, GL_DEPTH_STENCIL_ATTACHMENT, tex, lvl);

            for (int i = 0; i <= 10; i++) {
                float value = (float) (i / 10.0);

                nglClearNamedBufferSubData(buffer, GL_R32F, 0, 4096, GL_RED, GL_FLOAT, 0);
                glClearNamedFramebufferfi(fb, GL_DEPTH_STENCIL, 0, value, 1);

                glUseProgram(program);
                glUniform1i(2, lvl);
                glUniform1f(3, value);
                glBindTextureUnit(0, tex);
                GL30.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, buffer);

                glDispatchCompute(256>>(lvl+4), 256>>(lvl+4), 1);
                glFinish();

                long ptr = nglMapNamedBuffer(buffer, GL_READ_ONLY);
                float gottenValue = MemoryUtil.memGetFloat(ptr);
                glUnmapNamedBuffer(buffer);

                glUseProgram(0);
                glBindTextureUnit(0, 0);
                glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

                boolean localCorrect = gottenValue==0.0f;
                if (!localCorrect) {
                    Logger.error("Depth read test failed at value: " + value);
                }
                isCorrect &= localCorrect;
            }
        }

        glDeleteFramebuffers(fb);
        glDeleteTextures(tex);
        glDeleteBuffers(buffer);
        glDeleteProgram(program);
        return !isCorrect;
    }

    private static boolean testShaderCompilesOk(ShaderType type, String src) {
        int shader = GL20C.glCreateShader(type.gl);
        GL20C.glShaderSource(shader, src);
        GL20C.glCompileShader(shader);
        int result = GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS);
        GL20C.glDeleteShader(shader);

        return result == GL20C.GL_TRUE;
    }

    public long getFreeDedicatedGpuMemory() {
        if (me.cortex.voxy.client.core.backend.vk.VkVoxyDevice.isVulkanActive()) {
            return this.totalDedicatedMemory;
        }
        if (!this.canQueryGpuMemory) {
            throw new IllegalStateException("Cannot query gpu memory, missing extension");
        }
        return glGetInteger64(GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX)*1024;
    }

}
