package me.cortex.voxy.client.mixin.minecraft;

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

@Mixin(value = VulkanFeatureSets.class, remap = false)
public class MixinVulkanFeatureSets {
    @Unique
    private static final FeatureSet VOXY_FEATURES = new FeatureSet(
            "Voxy Required Features",
            Set.of(org.lwjgl.vulkan.EXTIndexTypeUint8.VK_EXT_INDEX_TYPE_UINT8_EXTENSION_NAME),
            Set.of(
                    new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "drawIndirectCount"),
                    new VulkanFeature(VulkanFeatureSets.VK10_FEATURES_STRUCT, "shaderInt64")
            )
    );

    @Inject(method = "optionalFeatureSets", at = @At("RETURN"), cancellable = true, remap = false)
    private static void voxy$injectVoxyFeatures(CallbackInfoReturnable<Set<FeatureSet>> cir) {
        Set<FeatureSet> set = new ObjectOpenHashSet<>(cir.getReturnValue());
        set.add(VOXY_FEATURES);
        set.add(me.cortex.voxy.client.core.backend.vk.VkVoxyFeatures.DEPTH_BOUNDS);
        cir.setReturnValue(set);
    }
}
