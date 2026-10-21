package me.cortex.voxy.common;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.LoadingModList;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;

import java.util.Set;

public class MixinConfig implements IMixinConfigPlugin {
    private String mixinPackage;
    private final Set<String> fallThrough;

    public MixinConfig() {
        this.fallThrough = Set.of();
    }

    @Override
    public void onLoad(String mixinPackage) {
        this.mixinPackage = mixinPackage + ".";
    }

    private static boolean isModLoaded(String modId) {
        try {
            var loadingList = LoadingModList.get();
            if (loadingList != null && loadingList.getModFileById(modId) != null) {
                return true;
            }
        } catch (Throwable ignored) {}
        try {
            if (ModList.get() != null && ModList.get().isLoaded(modId)) {
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixin) {
        if (mixin.startsWith(this.mixinPackage)) {
            var pth = mixin.substring(this.mixinPackage.length());
            if (!pth.contains(".")) return true;
            var targetMod = pth.substring(0, pth.indexOf('.'));
            if (this.fallThrough.contains(targetMod)) return true;
            if (isModLoaded(targetMod)) return true;
            return false;
        }
        return true;
    }
}
