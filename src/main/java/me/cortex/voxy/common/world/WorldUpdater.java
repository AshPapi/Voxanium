package me.cortex.voxy.common.world;

import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.VoxyCommon;

import static me.cortex.voxy.common.world.WorldEngine.*;

public class WorldUpdater {

    public static void insertUpdate(WorldEngine into, VoxelizedSection section) {

        if (VoxyCommon.IS_MINE_IN_ABYSS) {
            int sector = (section.x+512)>>10;
            section.setPosition(section.x-(sector<<10), section.y+16+(256-32-sector*30), section.z);
        }

        if (!into.isLive) throw new IllegalStateException("World is not live");
        boolean shouldCheckEmptiness = false;
        WorldSection previousSection = null;
        for (int lvl = 0; lvl <= MAX_LOD_LAYER; lvl++) {
            var worldSection = into.acquire(lvl, section.x >> (lvl + 1), section.y >> (lvl + 1), section.z >> (lvl + 1));

            int emptinessStateChange = 0;
            if (lvl != 0 && shouldCheckEmptiness) {
                emptinessStateChange = worldSection.updateEmptyChildState(previousSection);
                previousSection.release();
                previousSection = null;
            }

            long status = insertSectionLvlIntoWorld(section, worldSection);
            boolean didStateChange = (status&1)==1;
            int nonAirCount = (int) ((status>>1)&0x1FFF);


            if (lvl == 0) {
                int nonAirCountDelta = section.lvl0NonAirCount-nonAirCount;
                if (nonAirCountDelta != 0) {
                    worldSection.addNonEmptyBlockCount(nonAirCountDelta);
                    emptinessStateChange = worldSection.updateLvl0State() ? 2 : 0;
                }
            }

            if (didStateChange||(emptinessStateChange!=0)) {
                int neighbors = 0;
                if (didStateChange) {
                    neighbors |= ((section.y^(section.y-1))>>(lvl+1))==0?0:1<<0;
                    neighbors |= ((section.y^(section.y+1))>>(lvl+1))==0?0:1<<1;
                    neighbors |= ((section.x^(section.x-1))>>(lvl+1))==0?0:1<<2;
                    neighbors |= ((section.x^(section.x+1))>>(lvl+1))==0?0:1<<3;
                    neighbors |= ((section.z^(section.z-1))>>(lvl+1))==0?0:1<<4;
                    neighbors |= ((section.z^(section.z+1))>>(lvl+1))==0?0:1<<5;
                }

                into.markDirty(worldSection, (didStateChange?UPDATE_TYPE_BLOCK_BIT:0)|(emptinessStateChange!=0?UPDATE_TYPE_CHILD_EXISTENCE_BIT:0), neighbors);
            }

            if (didStateChange||(emptinessStateChange==2)) {
                if (emptinessStateChange==2) {
                    shouldCheckEmptiness = true;
                    previousSection = worldSection;
                } else {
                    shouldCheckEmptiness = false;
                    previousSection = null;
                    worldSection.release();
                }
            } else {
                worldSection.release();
                break;
            }
        }

        if (previousSection != null) {
            previousSection.release();
        }
    }


    public static long insertSectionLvlIntoWorld(VoxelizedSection section, WorldSection worldSection) {
        final long[] vdat = section.section;
        final int lvl = worldSection.lvl;

        final int msk = (1<<(lvl+1))-1;
        final int bx = (section.x&msk)<<(4-lvl);
        final int by = (section.y&msk)<<(4-lvl);
        final int bz = (section.z&msk)<<(4-lvl);

        int nonAirCount = 0;
        boolean didStateChange = false;


        {
            var secD = worldSection.data;
            int baseSec = bx | (bz << 5) | (by << 10);
            if (lvl == 0) {
                final int secMsk = 0b1100|(0xf << 5) | (0xf << 10);
                final int iSecMsk1 = (~secMsk) + 1;

                int secIdx = 0;

                for (int i = 0; i <= 0xFFF; i+=4) {
                    int cSecIdx = secIdx + baseSec;
                    secIdx = (secIdx + iSecMsk1) & secMsk;

                    long oldId0 = secD[cSecIdx+0]; secD[cSecIdx+0] = vdat[i+0];
                    long oldId1 = secD[cSecIdx+1]; secD[cSecIdx+1] = vdat[i+1];
                    long oldId2 = secD[cSecIdx+2]; secD[cSecIdx+2] = vdat[i+2];
                    long oldId3 = secD[cSecIdx+3]; secD[cSecIdx+3] = vdat[i+3];

                    nonAirCount += Mapper.isNotAirInt(oldId0); didStateChange |= vdat[i+0] != oldId0;
                    nonAirCount += Mapper.isNotAirInt(oldId1); didStateChange |= vdat[i+1] != oldId1;
                    nonAirCount += Mapper.isNotAirInt(oldId2); didStateChange |= vdat[i+2] != oldId2;
                    nonAirCount += Mapper.isNotAirInt(oldId3); didStateChange |= vdat[i+3] != oldId3;
                }
            } else {
                int baseVIdx = VoxelizedSection.getBaseIndexForLevel(lvl);

                int secMsk = 0xF >> lvl;
                secMsk |= (secMsk << 5) | (secMsk << 10);
                int iSecMsk1 = (~secMsk) + 1;

                int secIdx = 0;
                for (int i = baseVIdx; i <= (0xFFF >> (lvl * 3)) + baseVIdx; i++) {
                    int cSecIdx = secIdx + baseSec;
                    secIdx = (secIdx + iSecMsk1) & secMsk;
                    long newId = vdat[i];
                    long oldId = secD[cSecIdx];
                    didStateChange |= newId != oldId;
                    secD[cSecIdx] = newId;
                }
            }
        }

        long status = 0;
        status |= didStateChange?1:0;
        status |= Integer.toUnsignedLong(nonAirCount)<<1;
        return status;
    }
}
