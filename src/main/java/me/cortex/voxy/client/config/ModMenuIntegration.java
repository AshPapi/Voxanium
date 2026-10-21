package me.cortex.voxy.client.config;

import me.cortex.voxy.commonImpl.VoxyCommon;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.config.structure.OptionPage;
import net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen;
import net.minecraft.client.gui.screens.Screen;

public class ModMenuIntegration {
    public static Screen createScreen(Screen parent) {
        if (VoxyCommon.isAvailable()) {
            var opt = ConfigManager.CONFIG.getModOptions().stream().filter(a->a.configId().equals("voxy")).findFirst().orElse(null);
            if (opt != null && !opt.pages().isEmpty()) {
                var page = (OptionPage) opt.pages().get(0);
                return VideoSettingsScreen.createScreen(parent, page);
            }
        }
        return null;
    }
}
