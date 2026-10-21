package me.cortex.voxy.client.vitrail;

import dev.vitrail.render.BlockStateIds;
import it.unimi.dsi.fastutil.objects.AbstractObject2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import net.minecraft.world.level.block.state.BlockState;

public final class VitrailBlockIds extends AbstractObject2IntMap<BlockState> {
    public static final VitrailBlockIds INSTANCE = new VitrailBlockIds();

    public static Object2IntMap<BlockState> mapping() {
        return INSTANCE;
    }

    private VitrailBlockIds() {
        this.defRetValue = -1;
    }

    private static int id(Object key) {
        if (!(key instanceof BlockState state)) {
            return -1;
        }
        try {
            return BlockStateIds.id(state);
        } catch (Throwable e) {
            return -1;
        }
    }

    @Override
    public int getInt(Object key) {
        return id(key);
    }

    @Override
    public boolean containsKey(Object key) {
        return id(key) >= 0;
    }

    @Override
    public int size() {
        return 0;
    }

    @Override
    public ObjectSet<Object2IntMap.Entry<BlockState>> object2IntEntrySet() {
        return ObjectSets.emptySet();
    }
}
