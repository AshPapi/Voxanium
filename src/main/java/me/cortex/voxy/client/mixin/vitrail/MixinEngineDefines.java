package me.cortex.voxy.client.mixin.vitrail;

import dev.vitrail.pack.option.EngineDefines;
import me.cortex.voxy.client.vitrail.VitrailVoxy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

@Pseudo
@Mixin(value = EngineDefines.class, remap = false)
public class MixinEngineDefines {
    @Inject(method = "table(Ldev/vitrail/pack/option/EngineDefines$Environment;)Ljava/util/Map;", at = @At("RETURN"), require = 0)
    private static void voxy$injectVoxyDefine(EngineDefines.Environment environment, CallbackInfoReturnable<Map<String, String>> cir) {
        VitrailVoxy.addEngineDefines(cir.getReturnValue());
    }
}
