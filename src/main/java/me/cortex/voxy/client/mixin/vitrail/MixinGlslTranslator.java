package me.cortex.voxy.client.mixin.vitrail;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.cortex.voxy.common.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.Set;

@Pseudo
@Mixin(targets = "dev.vitrail.glsl.GlslTranslator", remap = false)
public class MixinGlslTranslator {
    @Unique
    private static volatile boolean voxy$announced;

    @Shadow
    @Final
    private Set<String> declaredNames;

    @WrapOperation(method = "rewriteIdentifiers", at = @At(value = "INVOKE", target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"),
            slice = @Slice(
                    from = @At(value = "FIELD", target = "Ldev/vitrail/glsl/LegacyGlsl;RESERVED_NAMES:Ljava/util/Map;", ordinal = 0),
                    to = @At(value = "FIELD", target = "Ldev/vitrail/glsl/LegacyGlsl;RESERVED_NAMES:Ljava/util/Map;", ordinal = 1)),
            require = 0)
    private Object voxy$keepBuiltinInDefine(Map<Object, Object> names, Object name, Operation<Object> original) {
        Object renamed = original.call(names, name);
        if (renamed != null && !this.declaredNames.contains(name)) {
            if (!voxy$announced) {
                voxy$announced = true;
                Logger.info("A shader pack #define names the builtin " + name + " that the pack never declares, so Vitrail keeps it as the builtin");
            }
            return null;
        }
        return renamed;
    }

    @Inject(method = "emissionSwitches", at = @At("RETURN"), cancellable = true, require = 0)
    private static void voxy$markTranslation(CallbackInfoReturnable<String> cir) {
        cir.setReturnValue(cir.getReturnValue() + " voxy-define-builtins-v1");
    }
}
