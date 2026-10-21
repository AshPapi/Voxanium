package me.cortex.voxy.client.core.rendering.util;

import net.minecraft.client.Camera;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;

public final class SpecialFog {
    private SpecialFog() {}

    public static boolean isActive(Camera camera) {
        if (camera == null) {
            return false;
        }
        FogType fluid = camera.getFluidInCamera();
        if (fluid == FogType.WATER || fluid == FogType.LAVA || fluid == FogType.POWDER_SNOW) {
            return true;
        }
        return camera.entity() instanceof LivingEntity living
                && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS));
    }
}
