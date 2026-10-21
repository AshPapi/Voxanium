package me.cortex.voxy.client.vitrail;

import dev.vitrail.uniform.UniformCatalog;
import dev.vitrail.uniform.UniformShape;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.rendering.Viewport;
import org.joml.Matrix4f;
import org.joml.Vector4f;

public final class VitrailVoxyUniforms {
    private static final int MODEL_VIEW = 0;
    private static final int PROJECTION = 1;
    private static final int VIEW_PROJECTION = 2;
    private static final int COUNT = 3;

    private static final Matrix4f[] CURRENT = create(COUNT);
    private static final Matrix4f[] INVERSE = create(COUNT);
    private static final Matrix4f[] PREVIOUS = create(COUNT);

    private static float nearPlane = 16.0f;
    private static float farPlane = 3000.0f;
    private static boolean updated;

    private VitrailVoxyUniforms() {
    }

    private static Matrix4f[] create(int count) {
        Matrix4f[] out = new Matrix4f[count];
        for (int i = 0; i < count; i++) {
            out[i] = new Matrix4f();
        }
        return out;
    }

    public static void register(UniformCatalog.Builder builder) {
        builder.add("vxRenderDistance", UniformShape.INT, (world, out) -> out.set(renderDistance()));
        builder.add("vxNearPlane", UniformShape.FLOAT, (world, out) -> out.set(nearPlane));
        builder.add("vxFarPlane", UniformShape.FLOAT, (world, out) -> out.set(farPlane));
        matrices(builder, "vxModelView", MODEL_VIEW);
        matrices(builder, "vxProj", PROJECTION);
        matrices(builder, "vxViewProj", VIEW_PROJECTION);
    }

    private static void matrices(UniformCatalog.Builder builder, String name, int index) {
        builder.add(name, UniformShape.MAT4, (world, out) -> out.set(CURRENT[index]));
        builder.add(name + "Inv", UniformShape.MAT4, (world, out) -> out.set(INVERSE[index]));
        builder.add(name + "Prev", UniformShape.MAT4, (world, out) -> out.set(PREVIOUS[index]));
    }

    public static Matrix4f projectionInverse() {
        return INVERSE[PROJECTION];
    }

    public static int renderDistance() {
        try {
            return Math.round(VoxyConfig.CONFIG.sectionRenderDistance * 32);
        } catch (Throwable e) {
            return 0;
        }
    }

    public static void update(Viewport<?> viewport) {
        for (int i = 0; i < COUNT; i++) {
            PREVIOUS[i].set(CURRENT[i]);
        }
        CURRENT[MODEL_VIEW].set(viewport.modelView);
        toGl(viewport.properties, CURRENT[PROJECTION].set(viewport.projection));
        toGl(viewport.properties, CURRENT[VIEW_PROJECTION].set(viewport.MVP));
        for (int i = 0; i < COUNT; i++) {
            CURRENT[i].invert(INVERSE[i]);
        }
        if (!updated) {
            updated = true;
            for (int i = 0; i < COUNT; i++) {
                PREVIOUS[i].set(CURRENT[i]);
            }
        }
        nearPlane = viewport.nearPlane;
        farPlane = viewport.farPlane;
    }

    public static Matrix4f toGl(RenderProperties properties, Matrix4f matrix) {
        Vector4f row = matrix.getRow(2, new Vector4f());
        if (properties.isZero2One()) {
            Vector4f w = matrix.getRow(3, new Vector4f());
            if (properties.isReverseZ()) {
                row.mul(-2.0f).add(w);
            } else {
                row.mul(2.0f).sub(w);
            }
            matrix.setRow(2, row);
        } else if (properties.isReverseZ()) {
            matrix.setRow(2, row.mul(-1.0f));
        }
        return matrix;
    }
}
