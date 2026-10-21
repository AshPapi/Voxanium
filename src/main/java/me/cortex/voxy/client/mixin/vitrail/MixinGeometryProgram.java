package me.cortex.voxy.client.mixin.vitrail;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import me.cortex.voxy.client.vitrail.VitrailLodTextures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "dev.vitrail.render.GeometryProgram", remap = false)
public class MixinGeometryProgram {
    @Inject(method = "depth(Ljava/lang/String;)Lcom/mojang/renderpearl/api/textures/GpuTextureView;", at = @At("RETURN"), cancellable = true, require = 0)
    private void voxy$depthWithLods(String sampler, CallbackInfoReturnable<GpuTextureView> cir) {
        GpuTextureView water = VitrailLodTextures.waterFor(cir.getReturnValue());
        if (water != null) {
            cir.setReturnValue(water);
        }
    }
}
