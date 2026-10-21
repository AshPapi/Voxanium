package me.cortex.voxy.client.mixin.vitrail;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.vitrail.render.GraphicsApi;
import me.cortex.voxy.client.vitrail.VitrailLodTextures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(value = GraphicsApi.class, remap = false)
public class MixinGraphicsApi {
    @Inject(method = "bindTexture", at = @At("HEAD"), cancellable = true, require = 0)
    private static void voxy$bindLodDepth(RenderPass pass, String name, GpuTextureView view, GpuSampler sampler, CallbackInfo ci) {
        GpuTextureView lod = VitrailLodTextures.forSampler(name);
        if (lod != null) {
            pass.setUniform(name, lod, sampler);
            ci.cancel();
        }
    }
}
