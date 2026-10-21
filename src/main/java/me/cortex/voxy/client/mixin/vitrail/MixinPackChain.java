package me.cortex.voxy.client.mixin.vitrail;

import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.backend.vk.VkPackGbufferPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.vitrail.render.PackChain", remap = false)
public class MixinPackChain {
    @Inject(method = "drawBeforeTranslucents()V", at = @At("HEAD"), require = 0)
    private static void voxy$drawTranslucentLodsAfterHand(CallbackInfo ci) {
        VkPackGbufferPass.afterHand();
        var renderer = IVoxyRenderSystemHolder.getNullable();
        if (renderer != null) {
            renderer.drawPackTranslucentVulkan();
        }
    }
}
