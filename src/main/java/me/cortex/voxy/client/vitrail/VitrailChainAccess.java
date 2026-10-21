package me.cortex.voxy.client.vitrail;

import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.vitrail.glsl.PackProgram;
import dev.vitrail.pack.option.OptionValue;
import dev.vitrail.pack.target.TargetSchedule;
import dev.vitrail.render.PackValues;
import me.cortex.voxy.common.Logger;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

public final class VitrailChainAccess {
    private static final boolean READY;
    private static MethodHandle ACTIVE;
    private static MethodHandle CHAIN;
    private static MethodHandle VALUES;
    private static MethodHandle PACK_PATH;
    private static MethodHandle CHOSEN;
    private static MethodHandle PROFILE;
    private static MethodHandle TARGETS;
    private static MethodHandle TARGET_VIEW;
    private static MethodHandle TARGET_SCHEDULE;
    private static MethodHandle TARGET_NOISE;
    private static MethodHandle TARGET_WHITE;
    private static MethodHandle TARGET_BLACK;
    private static MethodHandle TARGET_SHADOW;
    private static MethodHandle TARGET_DEPTH;
    private static MethodHandle TARGET_TAKE_CLEAR;
    private static MethodHandle TARGET_FLUSH_PENDING;
    private static MethodHandle SHADOW_DEPTH;
    private static MethodHandle SHADOW_DEPTH_WITHOUT_TRANSLUCENTS;
    private static MethodHandle SHADOW_COLOUR;
    private static MethodHandle DEPTH_OPAQUE;
    private static MethodHandle TARGET_COVERAGE;

    private static volatile boolean broken;

    static {
        boolean ready = false;
        try {
            Class<?> chain = Class.forName("dev.vitrail.render.PackChain");
            Class<?> targets = Class.forName("dev.vitrail.render.ColorTargets");
            Class<?> shadow = Class.forName("dev.vitrail.render.ShadowTargets");
            Class<?> depth = Class.forName("dev.vitrail.render.PackDepth");
            MethodHandles.Lookup chainLookup = MethodHandles.privateLookupIn(chain, MethodHandles.lookup());
            MethodHandles.Lookup targetLookup = MethodHandles.privateLookupIn(targets, MethodHandles.lookup());
            MethodHandles.Lookup shadowLookup = MethodHandles.privateLookupIn(shadow, MethodHandles.lookup());
            MethodHandles.Lookup depthLookup = MethodHandles.privateLookupIn(depth, MethodHandles.lookup());

            ACTIVE = chainLookup.findStaticGetter(chain, "active", chain);
            CHAIN = chainLookup.findGetter(chain, "chain", PackProgram.Chain.class);
            VALUES = chainLookup.findGetter(chain, "values", PackValues.class);
            PACK_PATH = chainLookup.findGetter(chain, "packPath", Path.class);
            CHOSEN = chainLookup.findGetter(chain, "chosen", Map.class);
            PROFILE = chainLookup.findGetter(chain, "profile", String.class);
            TARGETS = chainLookup.findGetter(chain, "targets", targets);

            TARGET_VIEW = targetLookup.findVirtual(targets, "view", MethodType.methodType(GpuTextureView.class, int.class, TargetSchedule.Side.class));
            TARGET_SCHEDULE = targetLookup.findVirtual(targets, "schedule", MethodType.methodType(TargetSchedule.class));
            TARGET_NOISE = targetLookup.findVirtual(targets, "noise", MethodType.methodType(GpuTextureView.class));
            TARGET_WHITE = targetLookup.findVirtual(targets, "white", MethodType.methodType(GpuTextureView.class));
            TARGET_BLACK = targetLookup.findVirtual(targets, "black", MethodType.methodType(GpuTextureView.class));
            TARGET_SHADOW = targetLookup.findVirtual(targets, "shadow", MethodType.methodType(shadow));
            TARGET_DEPTH = targetLookup.findVirtual(targets, "depth", MethodType.methodType(depth));
            TARGET_TAKE_CLEAR = targetLookup.findVirtual(targets, "takeClear", MethodType.methodType(Optional.class, GpuTextureView.class));
            TARGET_FLUSH_PENDING = targetLookup.findVirtual(targets, "flushPending", MethodType.methodType(void.class, CommandEncoder.class));

            SHADOW_DEPTH = shadowLookup.findVirtual(shadow, "depth", MethodType.methodType(GpuTextureView.class));
            SHADOW_DEPTH_WITHOUT_TRANSLUCENTS = shadowLookup.findVirtual(shadow, "depthWithoutTranslucents", MethodType.methodType(GpuTextureView.class));
            SHADOW_COLOUR = shadowLookup.findVirtual(shadow, "colour", MethodType.methodType(GpuTextureView.class, int.class));

            DEPTH_OPAQUE = depthLookup.findVirtual(depth, "opaque", MethodType.methodType(GpuTextureView.class));
            ready = true;
            try {
                TARGET_COVERAGE = targetLookup.findVirtual(targets, "coverage", MethodType.methodType(GpuTextureView.class));
            } catch (Throwable e) {
                Logger.warn("Vitrail has no terrain coverage to mark, its scene seed may paint over voxy lods: " + e);
            }
        } catch (Throwable e) {
            Logger.warn("Vitrail internals could not be reached, shader packs will not shade voxy lods: " + e);
        }
        READY = ready;
    }

    private VitrailChainAccess() {
    }

    public static boolean ready() {
        return READY && !broken;
    }

    private static void fail(Throwable e) {
        if (!broken) {
            broken = true;
            Logger.error("Reading Vitrail internals failed, shader packs will not shade voxy lods", e);
        }
    }

    public static Object active() {
        if (!ready()) {
            return null;
        }
        try {
            return ACTIVE.invoke();
        } catch (Throwable e) {
            fail(e);
            return null;
        }
    }

    public static PackProgram.Chain chain(Object chain) {
        try {
            return (PackProgram.Chain) CHAIN.invoke(chain);
        } catch (Throwable e) {
            fail(e);
            return null;
        }
    }

    public static PackValues values(Object chain) {
        try {
            return (PackValues) VALUES.invoke(chain);
        } catch (Throwable e) {
            fail(e);
            return null;
        }
    }

    public static Path packPath(Object chain) {
        try {
            return (Path) PACK_PATH.invoke(chain);
        } catch (Throwable e) {
            fail(e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, OptionValue> chosen(Object chain) {
        try {
            return (Map<String, OptionValue>) CHOSEN.invoke(chain);
        } catch (Throwable e) {
            fail(e);
            return null;
        }
    }

    public static String profile(Object chain) {
        try {
            return (String) PROFILE.invoke(chain);
        } catch (Throwable e) {
            fail(e);
            return null;
        }
    }

    public static Object targets(Object chain) {
        try {
            return TARGETS.invoke(chain);
        } catch (Throwable e) {
            fail(e);
            return null;
        }
    }

    public static GpuTextureView view(Object targets, int index, TargetSchedule.Side side) {
        try {
            return (GpuTextureView) TARGET_VIEW.invoke(targets, index, side);
        } catch (Throwable e) {
            fail(e);
            return null;
        }
    }

    public static TargetSchedule schedule(Object targets) {
        try {
            return (TargetSchedule) TARGET_SCHEDULE.invoke(targets);
        } catch (Throwable e) {
            fail(e);
            return null;
        }
    }

    public static GpuTextureView noise(Object targets) {
        return constant(TARGET_NOISE, targets);
    }

    public static GpuTextureView white(Object targets) {
        return constant(TARGET_WHITE, targets);
    }

    public static GpuTextureView black(Object targets) {
        return constant(TARGET_BLACK, targets);
    }

    public static GpuTextureView white() {
        Object chain = active();
        if (chain == null) {
            return null;
        }
        Object targets = targets(chain);
        return targets == null ? null : white(targets);
    }

    private static GpuTextureView constant(MethodHandle handle, Object targets) {
        try {
            return (GpuTextureView) handle.invoke(targets);
        } catch (Throwable e) {
            fail(e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static Optional<org.joml.Vector4fc> takeClear(Object targets, GpuTextureView view) {
        try {
            return (Optional<org.joml.Vector4fc>) TARGET_TAKE_CLEAR.invoke(targets, view);
        } catch (Throwable e) {
            fail(e);
            return Optional.empty();
        }
    }

    public static void flushPending(Object targets, CommandEncoder encoder) {
        try {
            TARGET_FLUSH_PENDING.invoke(targets, encoder);
        } catch (Throwable e) {
            fail(e);
        }
    }

    public static GpuTextureView shadowDepth(Object targets, boolean withoutTranslucents) {
        try {
            Object shadow = TARGET_SHADOW.invoke(targets);
            if (shadow == null) {
                return null;
            }
            return (GpuTextureView) (withoutTranslucents ? SHADOW_DEPTH_WITHOUT_TRANSLUCENTS : SHADOW_DEPTH).invoke(shadow);
        } catch (Throwable e) {
            fail(e);
            return null;
        }
    }

    public static GpuTextureView shadowColour(Object targets, int index) {
        try {
            Object shadow = TARGET_SHADOW.invoke(targets);
            if (shadow == null) {
                return null;
            }
            return (GpuTextureView) SHADOW_COLOUR.invoke(shadow, index);
        } catch (Throwable e) {
            fail(e);
            return null;
        }
    }

    public static GpuTextureView coverage(Object targets) {
        if (TARGET_COVERAGE == null) {
            return null;
        }
        try {
            return (GpuTextureView) TARGET_COVERAGE.invoke(targets);
        } catch (Throwable e) {
            return null;
        }
    }

    public static GpuTextureView opaqueDepth(Object targets) {
        try {
            Object depth = TARGET_DEPTH.invoke(targets);
            if (depth == null) {
                return null;
            }
            return (GpuTextureView) DEPTH_OPAQUE.invoke(depth);
        } catch (Throwable e) {
            fail(e);
            return null;
        }
    }
}
