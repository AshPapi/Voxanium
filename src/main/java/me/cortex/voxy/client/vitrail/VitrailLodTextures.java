package me.cortex.voxy.client.vitrail;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import me.cortex.voxy.common.Logger;

import java.util.HashSet;
import java.util.Set;

public final class VitrailLodTextures {
    public static final String OPAQUE = "vxDepthTexOpaque";
    public static final String TRANSLUCENT = "vxDepthTexTrans";
    private static final String PREFIX = "vxDepthTex";

    private static GpuTextureView opaque;
    private static GpuTextureView translucent;
    private static GpuTextureView water;
    private static boolean waterAnnounced;
    private static final Set<String> SERVED = new HashSet<>();

    private VitrailLodTextures() {
    }

    public static void opaque(GpuTextureView view) {
        opaque = view;
    }

    public static void translucent(GpuTextureView view) {
        translucent = view;
    }

    public static void water(GpuTextureView view) {
        water = view;
    }

    public static void endFrame() {
        opaque = null;
        translucent = null;
        water = null;
    }

    public static GpuTextureView waterFor(GpuTextureView served) {
        GpuTextureView view = water;
        if (view == null || served == null || view.isClosed()) {
            return null;
        }
        Object chain = VitrailChainAccess.active();
        Object targets = chain == null ? null : VitrailChainAccess.targets(chain);
        if (targets == null || served != VitrailChainAccess.opaqueDepth(targets)) {
            return null;
        }
        if (!waterAnnounced) {
            waterAnnounced = true;
            Logger.info("Shader pack programs drawn after the lods read a depth where the lods fill what the world left empty");
        }
        return view;
    }

    public static GpuTextureView forSampler(String name) {
        if (name == null || !name.startsWith(PREFIX)) {
            return null;
        }
        GpuTextureView view = null;
        if (name.equals(OPAQUE)) {
            view = opaque;
        } else if (name.equals(TRANSLUCENT)) {
            view = translucent != null ? translucent : opaque;
        }
        if (view != null && !view.isClosed()) {
            if (SERVED.add(name)) {
                Logger.info("Voxy lod depth is bound to the shader pack sampler " + name);
            }
            return view;
        }
        return VitrailChainAccess.white();
    }
}
