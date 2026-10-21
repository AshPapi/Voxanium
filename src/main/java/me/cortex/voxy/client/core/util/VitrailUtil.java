package me.cortex.voxy.client.core.util;

import me.cortex.voxy.common.Logger;
import net.neoforged.fml.ModList;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

public class VitrailUtil {
    public static final boolean VITRAIL_INSTALLED = (ModList.get() != null && ModList.get().isLoaded("vitrail"));

    private static final MethodHandle DRAWING_PACK = find("dev.vitrail.render.PackChain", "drawingPack");
    private static final MethodHandle DRAWING_SHADOW = find("dev.vitrail.render.TerrainDraw", "drawingShadow");

    private static volatile boolean broken;
    private static volatile boolean shadowWalk;

    private static MethodHandle find(String owner, String name) {
        if (!VITRAIL_INSTALLED) {
            return null;
        }
        try {
            Class<?> clz = Class.forName(owner, false, VitrailUtil.class.getClassLoader());
            return MethodHandles.publicLookup().findStatic(clz, name, MethodType.methodType(boolean.class));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Logger.warn("Vitrail is installed but " + owner + "." + name + "() cannot be reached, lods will not be shown with its shader packs: " + e);
            return null;
        }
    }

    private static boolean call(MethodHandle handle) {
        if (handle == null || broken) {
            return false;
        }
        try {
            return (boolean) handle.invokeExact();
        } catch (Throwable e) {
            broken = true;
            Logger.error("Failed to read the state of Vitrail, shader pack support is turned off", e);
            return false;
        }
    }

    public static boolean packActive() {
        return call(DRAWING_PACK);
    }

    public static boolean shadowPassActive() {
        return call(DRAWING_SHADOW);
    }

    public static void shadowWalk(boolean active) {
        shadowWalk = active;
    }

    public static boolean shadowWalkActive() {
        return shadowWalk;
    }
}
