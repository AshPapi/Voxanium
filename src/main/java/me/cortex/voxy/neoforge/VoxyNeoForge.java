package me.cortex.voxy.neoforge;

import me.cortex.voxy.client.DebugEntries;
import me.cortex.voxy.client.VoxyCommands;
import me.cortex.voxy.client.config.ModMenuIntegration;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterDebugEntriesEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

@Mod("voxy")
public class VoxyNeoForge {
    public VoxyNeoForge(IEventBus modEventBus) {
        VoxyNativeLoader.init();
        VoxyCommon.init();

        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            modEventBus.addListener((RegisterDebugEntriesEvent event) -> DebugEntries.init(event::register));
            NeoForge.EVENT_BUS.addListener(this::registerClientCommands);
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, RenderLevelStageEvent.AfterOpaqueFeatures.class, event -> {
                var renderer = IVoxyRenderSystemHolder.getNullable();
                if (renderer != null) {
                    renderer.drawPackOpaqueVulkan();
                }
            });
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, RenderLevelStageEvent.AfterLevel.class, event -> {
                var renderer = IVoxyRenderSystemHolder.getNullable();
                if (renderer != null) {
                    renderer.compositeOverShaderPackVulkan();
                }
            });
            ModLoadingContext.get().registerExtensionPoint(IConfigScreenFactory.class, () -> (mc, parent) -> {
                var screen = ModMenuIntegration.createScreen(parent);
                return screen != null ? screen : parent;
            });
        }
    }

    private void registerClientCommands(RegisterClientCommandsEvent event) {
        if (VoxyCommon.isAvailable()) {
            event.getDispatcher().register(VoxyCommands.register());
        }
    }
}
