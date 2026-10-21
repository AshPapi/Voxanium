package me.cortex.voxy.client.vitrail;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.commonImpl.VoxyCommon;

import java.util.Map;

public final class VitrailVoxy {
    public static final String DEFINE_VERSION = "3";

    private VitrailVoxy() {
    }

    public static boolean shadingEnabled() {
        try {
            if (!VoxyCommon.isAvailable()) {
                return false;
            }
            return VoxyConfig.CONFIG != null && VoxyConfig.CONFIG.isRenderingEnabled();
        } catch (Throwable e) {
            return false;
        }
    }

    public static void addEngineDefines(Map<String, String> defines) {
        if (defines == null || !shadingEnabled()) {
            return;
        }
        try {
            defines.put("VOXY", DEFINE_VERSION);
        } catch (UnsupportedOperationException ignored) {
        }
    }
}
