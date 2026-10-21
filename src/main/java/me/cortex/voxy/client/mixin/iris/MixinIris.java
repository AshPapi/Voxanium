package me.cortex.voxy.client.mixin.iris;

import me.cortex.voxy.client.iris.ShaderLoadError;
import me.cortex.voxy.common.Logger;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Iris.class, remap = false)
public class MixinIris {
    @Inject(method = "duringRenderSystemInit", at = @At("HEAD"), cancellable = true)
    private static void voxy$guardIrisDuringRenderSystemInit(CallbackInfo ci) {
        if (me.cortex.voxy.client.core.backend.vk.VkVoxyDevice.isVulkanActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "onRenderSystemInit", at = @At("HEAD"), cancellable = true)
    private static void voxy$guardIrisOnRenderSystemInit(CallbackInfo ci) {
        if (me.cortex.voxy.client.core.backend.vk.VkVoxyDevice.isVulkanActive()) {
            ci.cancel();
        }
    }

    @Redirect(method = "createPipeline", at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/shaderpack/ShaderPack;getProgramSet(Lnet/irisshaders/iris/shaderpack/materialmap/NamespacedId;)Lnet/irisshaders/iris/shaderpack/programs/ProgramSet;"))
    private static ProgramSet voxy$redirectProgramSet(ShaderPack shaderPack, NamespacedId dim) {
        try {
            return shaderPack.getProgramSet(dim);
        } catch (ShaderLoadError e) {
            Logger.error(e);
            return null;
        }
    }
}
