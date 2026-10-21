package me.cortex.voxy.client.core.model;

import me.cortex.voxy.client.core.RenderResourceReuse;
import me.cortex.voxy.client.core.backend.IBufferHolder;
import me.cortex.voxy.client.core.backend.vk.VkVoxyBuffer;
import me.cortex.voxy.client.core.backend.vk.VkVoxyDevice;
import me.cortex.voxy.client.core.backend.vk.VkVoxyTexture;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.common.util.GlobalCleaner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkImageSubresourceRange;

import java.lang.ref.Cleaner;

import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11C.GL_NEAREST;
import static org.lwjgl.opengl.GL11C.GL_NEAREST_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_MAX_LOD;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_MIN_LOD;
import static org.lwjgl.opengl.GL30.glBindBufferBase;
import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.opengl.GL33C.glSamplerParameteri;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL45.glBindTextureUnit;

public class ModelStore {
    public static final int MODEL_SIZE = 64;
    private Cleaner.Cleanable ref;
    public final IBufferHolder modelBuffer;
    public final IBufferHolder modelColourBuffer;
    public final GlTexture textures;
    public final VkVoxyTexture vkTextures;
    public final int blockSampler;

    public ModelStore() {
        if (VkVoxyDevice.isVulkanActive()) {
            this.modelBuffer = new VkVoxyBuffer(VkVoxyDevice.getInstance(), (long) MODEL_SIZE * (1<<16),
                    VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    Vma.VMA_MEMORY_USAGE_GPU_ONLY);
            this.modelColourBuffer = new VkVoxyBuffer(VkVoxyDevice.getInstance(), 4L * (1<<16),
                    VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    Vma.VMA_MEMORY_USAGE_GPU_ONLY);
            this.textures = null;
            this.ref = null;
            this.blockSampler = 0;
            this.vkTextures = createVkTextureAtlas(VkVoxyDevice.getInstance());
        } else {
            this.modelBuffer = new GlBuffer(MODEL_SIZE * (1<<16)).name("ModelData");
            this.modelColourBuffer = new GlBuffer(4 * (1<<16)).name("ModelColour");
            this.vkTextures = null;
            var tex = this.textures = RenderResourceReuse.getOrCreateModelStoreTextureAtlas();
            this.ref = GlobalCleaner.CLEANER.register(this, ()->RenderResourceReuse.giveBackModelStoreTextureAtlas(tex));
            this.blockSampler = glGenSamplers();

            int mipLvl = ((TextureAtlas) Minecraft.getInstance().getTextureManager()
                    .getTexture(Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png")))
                    .maxMipLevel;

            glSamplerParameteri(this.blockSampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
            glSamplerParameteri(this.blockSampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glSamplerParameteri(this.blockSampler, GL_TEXTURE_MIN_LOD, 0);
            glSamplerParameteri(this.blockSampler, GL_TEXTURE_MAX_LOD, mipLvl);
        }
    }

    private static VkVoxyTexture createVkTextureAtlas(VkVoxyDevice device) {
        var atlas = VkVoxyTexture.createSampledMipmap(device,
                ModelFactory.MODEL_TEXTURE_SIZE*3*256,
                ModelFactory.MODEL_TEXTURE_SIZE*2*256,
                VK12.VK_FORMAT_R8G8B8A8_UNORM,
                ModelFactory.LAYERS);
        var cmd = device.allocateCommandBuffer();
        device.beginSingleTimeCommands(cmd);
        atlas.transitionLayout(cmd, VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        try (var stack = MemoryStack.stackPush()) {
            var range = VkImageSubresourceRange.calloc(stack)
                    .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(ModelFactory.LAYERS)
                    .baseArrayLayer(0).layerCount(1);
            VK12.vkCmdClearColorImage(cmd, atlas.vkImage(), VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VkClearColorValue.calloc(stack), range);
        }
        atlas.transitionLayout(cmd, VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        device.endAndSubmitSingleTimeCommands(cmd, device.graphicsQueue());
        device.freeCommandBuffer(cmd);
        return atlas;
    }

    public IBufferHolder modelBuffer() {
        return this.modelBuffer;
    }

    public IBufferHolder modelColourBuffer() {
        return this.modelColourBuffer;
    }

    public void free() {
        this.modelBuffer.free();
        this.modelColourBuffer.free();
        if (this.vkTextures != null) {
            this.vkTextures.free();
        }
        if (this.ref != null) {
            this.ref.clean();
        }
        if (this.blockSampler != 0) {
            glDeleteSamplers(this.blockSampler);
        }
    }

    public void bind(int modelBindingIndex, int colourBindingIndex, int textureBindingIndex) {
        if (this.modelBuffer instanceof GlBuffer glModel && this.modelColourBuffer instanceof GlBuffer glColour && this.textures != null) {
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, modelBindingIndex, glModel.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, colourBindingIndex, glColour.id);
            glBindTextureUnit(textureBindingIndex, this.textures.id);
            glBindSampler(textureBindingIndex, this.blockSampler);
        }
    }
}
