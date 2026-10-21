package me.cortex.voxy.client.core.backend.vk;

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;

import java.util.Set;

public final class VkVoxyFeatures {
    public static final FeatureSet DEPTH_BOUNDS = new FeatureSet(
            "Voxy Depth Bounds",
            Set.of(),
            Set.of(new VulkanFeature(VulkanFeatureSets.VK10_FEATURES_STRUCT, "depthBounds"))
    );

    private VkVoxyFeatures() {}
}
