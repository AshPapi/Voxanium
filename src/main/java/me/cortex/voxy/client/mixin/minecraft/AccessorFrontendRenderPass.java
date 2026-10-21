package me.cortex.voxy.client.mixin.minecraft;

import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(value = FrontendRenderPass.class, remap = false)
public interface AccessorFrontendRenderPass {
    @Accessor("backend")
    RenderPassBackend voxy$getBackend();

    @Accessor("colorAttachments")
    List<RenderPassDescriptor.Attachment<?>> voxy$getColorAttachments();
}
