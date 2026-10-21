package me.cortex.voxy.client.mixin.vitrail;

import dev.vitrail.uniform.UniformCatalog;
import dev.vitrail.uniform.values.DhValues;
import me.cortex.voxy.client.vitrail.VitrailVoxyUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(value = DhValues.class, remap = false)
public class MixinDhValues {
    @Inject(method = "register", at = @At("TAIL"), require = 0)
    private static void voxy$registerVoxyUniforms(UniformCatalog.Builder builder, CallbackInfo ci) {
        VitrailVoxyUniforms.register(builder);
    }
}
