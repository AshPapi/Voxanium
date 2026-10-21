package me.cortex.voxy.client.mixin.vitrail;

import me.cortex.voxy.client.core.util.VitrailUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.vitrail.sodium.ShadowTerrain", remap = false)
public class MixinShadowTerrain {
    @Inject(method = "draw()V", at = @At("HEAD"), require = 0)
    private static void voxy$enterShadowWalk(CallbackInfo ci) {
        VitrailUtil.shadowWalk(true);
    }

    @Inject(method = "draw()V", at = @At("RETURN"), require = 0)
    private static void voxy$leaveShadowWalk(CallbackInfo ci) {
        VitrailUtil.shadowWalk(false);
    }
}
