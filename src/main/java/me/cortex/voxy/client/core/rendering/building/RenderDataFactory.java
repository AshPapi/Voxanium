package me.cortex.voxy.client.core.rendering.building;

import me.cortex.voxy.client.core.model.IdNotYetComputedException;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.client.core.model.ModelQueries;
import me.cortex.voxy.client.core.util.ScanMesher2D;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.UnsafeUtil;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.VoxyCommon;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;


public class RenderDataFactory {
    private static final boolean BUILD_OCCUPANCY_SET = false;

    private static final boolean CHECK_NEIGHBOR_FACE_OCCLUSION = true;
    private static final boolean DISABLE_CULL_SAME_OCCLUDES = false;

    private static final boolean VERIFY_MESHING = VoxyCommon.isVerificationFlagOn("verifyMeshing");

    private final WorldEngine world;
    private final ModelFactory modelMan;

    private final long[] sectionData = new long[32*32*32*2];
    private final long[] neighboringFaces = new long[32*32*6];
    private final int[] neighborOpaqueMasks = new int[32*6];
    private final int[] neighborFluidMasks = new int[32*6];
    private final int[] skirtMasks = new int[32*6];
    private int skirtMasksReady = 0;
    private boolean skirtsAllowed = false;

    private final int[] opaqueMasks = new int[32*32];
    private final int[] nonOpaqueMasks = new int[32*32];
    private final int[] fluidMasks = new int[32*32];


    private final MemoryBuffer quadBuffer = new MemoryBuffer(8*(8*(1<<16)));
    private final long quadBufferPtr = this.quadBuffer.address;
    private final int[] quadCounters = new int[8];


    private int minX;
    private int minY;
    private int minZ;
    private int maxX;
    private int maxY;
    private int maxZ;

    private int quadCount = 0;

    private final OccupancySet occupancy;

    private final class Mesher extends ScanMesher2D {
        public int auxiliaryPosition = 0;
        public boolean doAuxiliaryFaceOffset = true;
        public int axis = 0;

        @Override
        protected void emitQuad(int x, int z, int length, int width, long data) {
            if (VERIFY_MESHING) {
                if (length<1||length>16) {
                    throw new IllegalStateException("length out of bounds: " + length);
                }
                if (width<1||width>16) {
                    throw new IllegalStateException("width out of bounds: " + width);
                }
                if (x<0||x>31) {
                    throw new IllegalStateException("x out of bounds: " + x);
                }
                if (z<0||z>31) {
                    throw new IllegalStateException("z out of bounds: " + z);
                }
                if (x-(length-1)<0 || z-(width-1)<0) {
                    throw new IllegalStateException("dim out of bounds: " + (x-(length-1))+", " + (z-(width-1)));
                }
            }

            RenderDataFactory.this.quadCount++;

            x -= length-1;
            z -= width-1;

            if (this.axis == 2) {
                int tmp = x;
                x = z;
                z = tmp;

                tmp = length;
                length = width;
                width = tmp;
            }

            int auxData = (int) (data&((1<<26)-1));
            data &= ~((1L<<26)-1);

            int axisSide = auxData&1;
            int type = (auxData>>1)&3;

            if (VERIFY_MESHING) {
                if (type == 3) {
                    throw new IllegalStateException();
                }
            }

            int auxPos = this.auxiliaryPosition;
            auxPos += 1-(this.doAuxiliaryFaceOffset?axisSide:1);

            if (VERIFY_MESHING) {
                if (auxPos > 31) {
                    throw new IllegalStateException("OOB face: " + auxPos + ", " + axisSide);
                }
            }

            final int axis = this.axis;
            int face = (axis<<1)|axisSide;

            int encodedPosition = face;
            encodedPosition |= ((width - 1) << 7) | ((length - 1) << 3);
            encodedPosition |= x << (axis==2?16:21);
            encodedPosition |= z << (axis==1?16:11);
            int shiftAmount = axis==0?16:(axis==1?11:21);
            encodedPosition |= auxPos << (shiftAmount);

            long quad = data | Integer.toUnsignedLong(encodedPosition);


            int bufferIdx = type+(type==2?face:0);
            long bufferOffset = (RenderDataFactory.this.quadCounters[bufferIdx]++)*8L + bufferIdx*8L*(1<<16);
            MemoryUtil.memPutLong(RenderDataFactory.this.quadBufferPtr + bufferOffset, quad);


            if (axis == 0) {
                RenderDataFactory.this.minY = Math.min(RenderDataFactory.this.minY, auxPos);
                RenderDataFactory.this.maxY = Math.max(RenderDataFactory.this.maxY, auxPos);

                RenderDataFactory.this.minX = Math.min(RenderDataFactory.this.minX, x);
                RenderDataFactory.this.maxX = Math.max(RenderDataFactory.this.maxX, x + length);

                RenderDataFactory.this.minZ = Math.min(RenderDataFactory.this.minZ, z);
                RenderDataFactory.this.maxZ = Math.max(RenderDataFactory.this.maxZ, z + width);
            } else if (axis == 1) {
                RenderDataFactory.this.minZ = Math.min(RenderDataFactory.this.minZ, auxPos);
                RenderDataFactory.this.maxZ = Math.max(RenderDataFactory.this.maxZ, auxPos);

                RenderDataFactory.this.minX = Math.min(RenderDataFactory.this.minX, x);
                RenderDataFactory.this.maxX = Math.max(RenderDataFactory.this.maxX, x + length);

                RenderDataFactory.this.minY = Math.min(RenderDataFactory.this.minY, z);
                RenderDataFactory.this.maxY = Math.max(RenderDataFactory.this.maxY, z + width);
            } else {
                RenderDataFactory.this.minX = Math.min(RenderDataFactory.this.minX, auxPos);
                RenderDataFactory.this.maxX = Math.max(RenderDataFactory.this.maxX, auxPos);

                RenderDataFactory.this.minY = Math.min(RenderDataFactory.this.minY, x);
                RenderDataFactory.this.maxY = Math.max(RenderDataFactory.this.maxY, x + length);

                RenderDataFactory.this.minZ = Math.min(RenderDataFactory.this.minZ, z);
                RenderDataFactory.this.maxZ = Math.max(RenderDataFactory.this.maxZ, z + width);
            }
        }
    }

    private final Mesher blockMesher = new Mesher();
    private final Mesher seondaryblockMesher = new Mesher();

    public RenderDataFactory(WorldEngine world, ModelFactory modelManager, boolean emitMeshlets) {
        this(world, modelManager, emitMeshlets, false);
    }
    public RenderDataFactory(WorldEngine world, ModelFactory modelManager, boolean emitMeshlets, boolean generateOccupancy) {
        this.world = world;
        this.modelMan = modelManager;
        if (generateOccupancy && BUILD_OCCUPANCY_SET) {
            this.occupancy = new OccupancySet();
        } else {
            this.occupancy = null;
        }
    }

    private static long getQuadTyping(long metadata) {
        return 0b111L&(0b000_000_010_100L>>(ModelQueries._isTranslucent(metadata)*6+ModelQueries._isDoubleSided(metadata)*3));
    }

    private static long packPartialQuadData(int modelId, long state, long metadata) {
        long lightAndBiome =  (state&((0x1FFL<<47)|(0xFFL<<56)))>>>1;
        lightAndBiome &= ~(ModelQueries._notIsBiomeColoured(metadata) * (0x1FFL << 46));
        lightAndBiome &= ~(ModelQueries._isFullyOpaque(metadata)*(0xFFL << 55));

        long quadData = lightAndBiome;
        quadData |= Integer.toUnsignedLong(modelId)<<26;
        quadData |= getQuadTyping(metadata);
        return quadData;
    }

    private int prepareSectionData(final long[] rawSectionData) {
        final var sectionData = this.sectionData;
        if (sectionData.length!=32*32*32*2) throw new IllegalStateException();
        if (rawSectionData.length!=32*32*32) throw new IllegalStateException();
        final var rawModelIds = this.modelMan._unsafeRawAccess();
        long opaque = 0;
        long notEmpty = 0;
        long pureFluid = 0;
        long partialFluid = 0;

        int neighborAcquireMskAndFlags = 0;
        int i = 0;
        for (int q = 0; q < 512; q++) {
            for (int j = 0; j < 64; i++, j++) {
                i &= 32*32*32-1;
                long block = rawSectionData[i];
                int i2 = i * 2;
                i2 &= (32*32*32*2-1)^1;
                if (Mapper.isAir(block)) {
                    sectionData[i2] = (block & (0xFFL << 56)) >>> 1;
                    sectionData[i2 + 1] = 0;
                } else {
                    int modelId = rawModelIds[Mapper.getBlockId(block)];
                    if (modelId == -1) {
                        return Mapper.getBlockId(block) | (1 << 31);
                    }
                    if (modelId == 0) {
                        sectionData[i2] = (block & (0xFFL << 56)) >>> 1;
                        sectionData[i2 + 1] = 0;
                    } else {

                        long modelMetadata = this.modelMan.getModelMetadataFromClientId(modelId);

                        sectionData[i2] = packPartialQuadData(modelId, block, modelMetadata);
                        sectionData[i2 + 1] = modelMetadata;

                        notEmpty |= 1L << j;
                        opaque |= ModelQueries._isFullyOpaque(modelMetadata)<<j;
                        pureFluid |= ModelQueries._isFluid(modelMetadata)<<j;
                        partialFluid |= ModelQueries._containsFluid(modelMetadata)<<j;
                    }
                }
            }
            if (notEmpty != 0) {
                neighborAcquireMskAndFlags |= getNeighborMsk(notEmpty, i);
                neighborAcquireMskAndFlags |= opaque!=0?(1<<6):0;
                this.opaqueMasks[(i >> 5) - 2] = (int) opaque;
                this.opaqueMasks[(i >> 5) - 1] = (int) (opaque>>>32);
                long nonOpaque = (notEmpty^opaque)&~pureFluid;
                notEmpty = 0;
                opaque = 0;
                this.nonOpaqueMasks[(i >> 5) - 2] = (int) nonOpaque;
                this.nonOpaqueMasks[(i >> 5) - 1] = (int) (nonOpaque>>>32);
                long fluid = pureFluid|partialFluid;
                pureFluid = 0;
                partialFluid = 0;
                this.fluidMasks[(i >> 5) - 2] = (int) fluid;
                this.fluidMasks[(i >> 5) - 1] = (int) (fluid>>>32);


            }
        }
        return neighborAcquireMskAndFlags;
    }

    private static int getNeighborMsk(long notEmpty, int i) {
        int packedEmpty = (int) ((notEmpty >>>32)| notEmpty);

        int neighborMsk = 0;
        neighborMsk += packedEmpty&1;
        neighborMsk += (packedEmpty>>>30)&0b10;

        neighborMsk += ((((i - 1) >> 10) == 0) ? 0b100 : 0)*(packedEmpty!=0?1:0);
        neighborMsk += ((((i - 1) >> 10) == 31) ? 0b1000 : 0)*(packedEmpty!=0?1:0);
        neighborMsk += (((((i - 33) >> 5) & 0x1F) == 0) ? 0b10000 : 0)*(((int) notEmpty)!=0?1:0);
        neighborMsk += (((((i - 1) >> 5) & 0x1F) == 31) ? 0b100000 : 0)*((notEmpty >>>32)!=0?1:0);
        return neighborMsk;
    }

    private void acquireNeighborData(WorldSection section, int msk) {
        if ((msk&1)!=0) {
            var sec = this.world.acquire(section.lvl, section.x - 1, section.y, section.z);
            var raw = sec._unsafeGetRawDataArray();
            for (int i = 0; i < 32*32; i++) {
                this.neighboringFaces[i] = raw[(i<<5)+31];
            }
            sec.release(WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
        }
        if ((msk&2)!=0) {
            var sec = this.world.acquire(section.lvl, section.x + 1, section.y, section.z);
            var raw = sec._unsafeGetRawDataArray();
            for (int i = 0; i < 32*32; i++) {
                this.neighboringFaces[i+32*32] = raw[(i<<5)];
            }
            sec.release(WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
        }

        if ((msk&4)!=0) {
            var sec = this.world.acquire(section.lvl, section.x, section.y - 1, section.z);
            var raw = sec._unsafeGetRawDataArray();
            for (int i = 0; i < 32*32; i++) {
                this.neighboringFaces[i+32*32*2] = raw[i|(0x1F<<10)];
            }
            sec.release(WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
        }
        if ((msk&8)!=0) {
            var sec = this.world.acquire(section.lvl, section.x, section.y + 1, section.z);
            var raw = sec._unsafeGetRawDataArray();
            for (int i = 0; i < 32*32; i++) {
                this.neighboringFaces[i+32*32*3] = raw[i];
            }
            sec.release(WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
        }

        if ((msk&16)!=0) {
            var sec = this.world.acquire(section.lvl, section.x, section.y, section.z - 1);
            var raw = sec._unsafeGetRawDataArray();
            for (int i = 0; i < 32*32; i++) {
                this.neighboringFaces[i+32*32*4] = raw[Integer.expand(i,0b11111_00000_11111)|(0x1F<<5)];
            }
            sec.release(WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
        }
        if ((msk&32)!=0) {
            var sec = this.world.acquire(section.lvl, section.x, section.y, section.z + 1);
            var raw = sec._unsafeGetRawDataArray();
            for (int i = 0; i < 32*32; i++) {
                this.neighboringFaces[i+32*32*5] = raw[Integer.expand(i,0b11111_00000_11111)];
            }
            sec.release(WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
        }
    }

    private static final long LM = (0xFFL<<55);
    private static final long SKIRT_QUAD_FLAG = 1L<<63;

    private void buildSkirtMask(int side) {
        int base = side*32*32;
        int lastBlock = 0;
        boolean lastOpaque = false;
        boolean lastFluid = false;
        for (int row = 0; row < 32; row++) {
            int msk = 0;
            int fluidMsk = 0;
            for (int col = 0; col < 32; col++) {
                int block = Mapper.getBlockId(this.neighboringFaces[base + row*32 + col]);
                if (block == 0) {
                    continue;
                }
                if (block != lastBlock) {
                    lastBlock = block;
                    long meta = this.modelMan.getModelMetadataFromClientId(this.modelMan.getModelId(block));
                    lastOpaque = ModelQueries.isFullyOpaque(meta);
                    lastFluid = ModelQueries.isFluid(meta) || ModelQueries.containsFluid(meta);
                }
                if (lastOpaque) {
                    msk |= 1<<col;
                }
                if (lastFluid) {
                    fluidMsk |= 1<<col;
                }
            }
            this.neighborOpaqueMasks[side*32 + row] = msk;
            this.neighborFluidMasks[side*32 + row] = fluidMsk;
        }
        boolean horizontalPlane = (side>>1) == 1;
        for (int row = 0; row < 32; row++) {
            int open = ~this.neighborOpaqueMasks[side*32 + row];
            if (row != 31) {
                open |= ~this.neighborOpaqueMasks[side*32 + row + 1];
            }
            if (row != 0 && horizontalPlane) {
                open |= ~this.neighborOpaqueMasks[side*32 + row - 1];
            }
            open |= (open<<1)|(open>>>1);
            this.skirtMasks[side*32 + row] = this.neighborOpaqueMasks[side*32 + row] & open;
        }
        this.skirtMasksReady |= 1<<side;
    }

    private boolean isSkirtFace(int side, int idx) {
        if (!this.skirtsAllowed) {
            return false;
        }
        if ((this.skirtMasksReady&(1<<side)) == 0) {
            this.buildSkirtMask(side);
        }
        return ((this.skirtMasks[side*32 + (idx>>5)]>>>(idx&31))&1) != 0;
    }

    private boolean isFluidSkirtFace(int side, int idx) {
        if (!this.skirtsAllowed || (side>>1) == 1) {
            return false;
        }
        int row = idx>>5;
        if (row == 31) {
            return true;
        }
        if ((this.skirtMasksReady&(1<<side)) == 0) {
            this.buildSkirtMask(side);
        }
        int covered = this.neighborOpaqueMasks[side*32 + row + 1] | this.neighborFluidMasks[side*32 + row + 1];
        return ((covered>>>(idx&31))&1) == 0;
    }

    private long fluidSkirtLight(int idx) {
        if ((idx>>10) == 31) {
            return (this.neighboringFaces[3*32*32 + (idx&1023)]&(0xFFL<<56))>>>1;
        }
        return this.sectionData[(idx + 32*32)*2]&LM;
    }

    private long getSkirtLight(int side, int idx) {
        int row = idx>>5;
        int col = idx&31;
        int blockLight = 0;
        int skyLight = 0;
        for (int r = Math.max(row-1, 0); r <= Math.min(row+1, 31); r++) {
            int open = ~this.neighborOpaqueMasks[side*32 + r];
            for (int c = Math.max(col-1, 0); c <= Math.min(col+1, 31); c++) {
                if (((open>>>c)&1) != 0) {
                    int light = Mapper.getLightId(this.neighboringFaces[side*32*32 + r*32 + c]);
                    blockLight = Math.max(blockLight, light&0xF0);
                    skyLight = Math.max(skyLight, light&0x0F);
                }
            }
        }
        return ((long) (blockLight|skyLight))<<55;
    }

    private boolean neighborFaceCovers(int face, long quad, long neighborQuad, long neighborMeta) {
        if (!ModelQueries.faceCoversBounds(neighborMeta, face^1)) return false;
        int self = this.modelMan.getFaceBounds((int) ((quad>>26)&0xFFFF), face);
        int other = this.modelMan.getFaceBounds((int) ((neighborQuad>>26)&0xFFFF), face^1);
        return ModelQueries.boundsContain(other, self);
    }

    private boolean shouldMeshNonOpaqueBlockFace(int face, long quad, long meta, long neighborQuad, long neighborMeta) {
        if (((quad^neighborQuad)&(0xFFFFL<<26))==0 && (DISABLE_CULL_SAME_OCCLUDES || ModelQueries.cullsSame(meta))) return false;
        if (!ModelQueries.faceExists(meta, face)) return false;
        if (ModelQueries.faceCanBeOccluded(meta, face)) {
            if (ModelQueries.faceOccludes(neighborMeta, face^1)) return false;
            if (this.neighborFaceCovers(face, quad, neighborQuad, neighborMeta)) return false;
        }
        return true;
    }

    private void meshNonOpaqueFace(int face, long quad, long meta, long neighborQuad, long neighborMeta, Mesher mesher) {
        if (shouldMeshNonOpaqueBlockFace(face, quad, meta, neighborQuad, neighborMeta)) {
            mesher.putNext(applyQuadLight(
                    (long) (face&1) |
                    (quad&~LM) |
                    ((ModelQueries.faceUsesSelfLighting(meta, face)?quad:neighborQuad) & LM),
                    meta));
        } else {
            mesher.skip(1);
        }
    }

    private static long applyQuadLight(long quad, long selfmeta) {
        final long BLMSK = 0xFL<<(55+4);
        long bl = quad&BLMSK;
        bl = Math.max(bl, ModelQueries.lightEmission(selfmeta)<<(55+4));
        quad &= ~(BLMSK);
        quad |= bl;
        return quad;
    }

    private void generateYZOpaqueInnerGeometry(int axis) {
        for (int layer = 0; layer < 31; layer++) {
            this.blockMesher.auxiliaryPosition = layer;
            int cSkip = 0;
            for (int other = 0; other < 32; other++) {
                int pidx = axis==0 ?(layer*32+other):(other*32+layer);
                int skipAmount = axis==0?32:1;

                int current = this.opaqueMasks[pidx];
                int next = this.opaqueMasks[pidx + skipAmount];

                int msk = current ^ next;
                if (msk == 0) {
                    cSkip += 32;
                    continue;
                }

                this.blockMesher.skip(cSkip);
                cSkip = 0;

                int faceForwardMsk = msk & current;
                int cIdx = -1;
                while (msk != 0) {
                    int index = Integer.numberOfTrailingZeros(msk);
                    int delta = index - cIdx - 1;
                    cIdx = index;
                    if (delta != 0) this.blockMesher.skip(delta);
                    msk &= ~Integer.lowestOneBit(msk);

                    int facingForward = ((faceForwardMsk >> index) & 1);

                    {
                        int idx = index + (pidx*32);
                        int shift = skipAmount * 32 * 2;

                        int iA = idx * 2 + (facingForward == 1 ? 0 : shift);
                        int iB = idx * 2 + (facingForward == 1 ? shift : 0);

                        long selfModel = this.sectionData[iA];
                        long selfMeta  = this.sectionData[iA+1];
                        long nextModel = this.sectionData[iB];

                        if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
                            long neighbor = this.sectionData[iB + 1];
                            boolean culls = false;
                            culls |= ((selfModel^nextModel)&(0xFFFFL<<26))==0&&ModelQueries.cullsSame(neighbor);
                            culls |= ModelQueries.faceOccludes(neighbor, (axis << 1) | (1 - facingForward));
                            if (culls) {
                                this.blockMesher.skip(1);
                                continue;
                            }
                        }

                        this.blockMesher.putNext(
                            applyQuadLight(
                                ((long) facingForward) |
                                (selfModel&~LM) |
                                (nextModel&LM),
                                selfMeta
                            ));
                    }
                }

                this.blockMesher.endRow();
            }
            this.blockMesher.finish();
        }
    }

    private void generateYZOpaqueOuterGeometry(int axis) {
        this.blockMesher.doAuxiliaryFaceOffset = false;
        for (int side = 0; side < 2; side++) {
            int layer = side == 0 ? 0 : 31;
            this.blockMesher.auxiliaryPosition = layer;
            int cSkips = 0;
            for (int other = 0; other < 32; other++) {
                int pidx = axis == 0 ? (layer * 32 + other) : (other * 32 + layer);
                int msk = this.opaqueMasks[pidx];
                if (msk == 0) {
                    cSkips += 32;
                    continue;
                }

                this.blockMesher.skip(cSkips);
                cSkips = 0;

                int cIdx = -1;
                while (msk != 0) {
                    int index = Integer.numberOfTrailingZeros(msk);
                    int delta = index - cIdx - 1;
                    cIdx = index;
                    if (delta != 0) this.blockMesher.skip(delta);
                    msk &= ~Integer.lowestOneBit(msk);

                    {
                        int idx = index + (pidx * 32);


                        int neighborIdx = ((axis+1)*32*32 * 2)+(side)*32*32;
                        long neighborId = this.neighboringFaces[neighborIdx + (other*32) + index];
                        long A = this.sectionData[idx * 2];
                        long selfMeta = this.sectionData[idx * 2 +1];
                        long lightData = (neighborId & (0xFFL << 56)) >>> 1;

                        int nib = Mapper.getBlockId(neighborId);
                        if (nib != 0) {

                            int cid = this.modelMan.getModelId(nib);
                            long meta = this.modelMan.getModelMetadataFromClientId(cid);
                            boolean culls = ModelQueries.isFullyOpaque(meta);

                            if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
                                culls |= cid==((A>>26)&0xFFFF)&&ModelQueries.cullsSame(meta);
                                culls |= ModelQueries.faceOccludes(meta, (axis << 1) | (1 - side));
                            }

                            if (culls) {
                                int skirtSide = (axis+1)*2 + side;
                                int skirtIdx = other*32 + index;
                                if (!this.isSkirtFace(skirtSide, skirtIdx)) {
                                    this.blockMesher.skip(1);
                                    continue;
                                }
                                lightData = this.getSkirtLight(skirtSide, skirtIdx);
                            }
                        }



                        this.blockMesher.putNext(applyQuadLight(
                                ((side == 0) ? 0L : 1L) |
                                (A&~LM) |
                                lightData,
                                selfMeta
                                )
                        );
                    }
                }
                this.blockMesher.endRow();
            }

            this.blockMesher.finish();
        }
        this.blockMesher.doAuxiliaryFaceOffset = true;
    }

    private void generateYZFluidInnerGeometry(int axis) {
        for (int layer = 0; layer < 31; layer++) {
            this.blockMesher.auxiliaryPosition = layer;
            int cSkip = 0;
            for (int other = 0; other < 32; other++) {
                int pidx = axis==0 ?(layer*32+other):(other*32+layer);
                int skipAmount = axis==0?32:1;

                int current = this.fluidMasks[pidx];
                int next = this.fluidMasks[pidx + skipAmount];

                int msk = (current | this.opaqueMasks[pidx]) ^ (next | this.opaqueMasks[pidx + skipAmount]);
                msk &= current|next;
                if (msk == 0) {
                    cSkip += 32;
                    continue;
                }

                this.blockMesher.skip(cSkip);
                cSkip = 0;

                int faceForwardMsk = msk & current;
                int cIdx = -1;
                while (msk != 0) {
                    int index = Integer.numberOfTrailingZeros(msk);
                    int delta = index - cIdx - 1;
                    cIdx = index;
                    if (delta != 0) this.blockMesher.skip(delta);
                    msk &= ~Integer.lowestOneBit(msk);

                    int facingForward = ((faceForwardMsk >> index) & 1);

                    {
                        int idx = index + (pidx*32);

                        int a = idx*2;
                        int b = (idx + skipAmount * 32) * 2;

                        int ai = facingForward == 1 ? a : b;
                        int bi = facingForward == 1 ? b : a;

                        if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
                            if (ModelQueries.faceOccludes(this.sectionData[bi + 1], (axis << 1) | (1 - facingForward))) {
                                this.blockMesher.skip(1);
                                continue;
                            }
                        }

                        long A = this.sectionData[ai];
                        long Am = this.sectionData[ai+1];
                        if (ModelQueries.containsFluid(Am)) {
                            int modelId = (int) ((A>>26)&0xFFFF);
                            A &= ~(0xFFFFL<<26);
                            int fluidId = this.modelMan.getFluidClientStateId(modelId);
                            A |= Integer.toUnsignedLong(fluidId)<<26;
                            Am = this.modelMan.getModelMetadataFromClientId(fluidId);

                            A &= ~0b110L; A |= getQuadTyping(Am);
                        }

                        long lighter = this.sectionData[bi];

                        this.blockMesher.putNext(applyQuadLight(
                                ((long) facingForward) |
                                (A&~LM) |
                                (lighter&LM),
                                Am)
                        );
                    }
                }

                this.blockMesher.endRow();
            }
            this.blockMesher.finish();
        }
    }

    private void generateYZFluidOuterGeometry(int axis) {
        this.blockMesher.doAuxiliaryFaceOffset = false;
        for (int side = 0; side < 2; side++) {
            int layer = side == 0 ? 0 : 31;
            this.blockMesher.auxiliaryPosition = layer;
            int cSkips = 0;
            for (int other = 0; other < 32; other++) {
                int pidx = axis == 0 ? (layer * 32 + other) : (other * 32 + layer);
                int msk = this.fluidMasks[pidx];
                if (msk == 0) {
                    cSkips += 32;
                    continue;
                }

                this.blockMesher.skip(cSkips);
                cSkips = 0;

                int cIdx = -1;
                while (msk != 0) {
                    int index = Integer.numberOfTrailingZeros(msk);
                    int delta = index - cIdx - 1;
                    cIdx = index;
                    if (delta != 0) this.blockMesher.skip(delta);
                    msk &= ~Integer.lowestOneBit(msk);

                    {
                        int idx = index + (pidx * 32);


                        int neighborIdx = ((axis+1)*32*32 * 2)+(side)*32*32;
                        long neighborId = this.neighboringFaces[neighborIdx + (other*32) + index];

                        long A = this.sectionData[idx * 2];
                        long Am = this.sectionData[idx * 2 + 1];

                        if (ModelQueries.containsFluid(Am)) {
                            int modelId = (int) ((A>>26)&0xFFFF);
                            A &= ~(0xFFFFL<<26);
                            int fluidId = this.modelMan.getFluidClientStateId(modelId);
                            A |= Integer.toUnsignedLong(fluidId)<<26;
                            Am = this.modelMan.getModelMetadataFromClientId(fluidId);

                            A &= ~0b110L; A |= getQuadTyping(Am);
                        }

                        long skirtFlag = 0;
                        long lightData = (neighborId&(0xFFL<<56))>>>1;
                        if (Mapper.getBlockId(neighborId) != 0) {
                            int modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));
                            long meta = this.modelMan.getModelMetadataFromClientId(modelId);
                            if (ModelQueries.containsFluid(meta)) {
                                modelId = this.modelMan.getFluidClientStateId(modelId);
                            }
                            if (ModelQueries.cullsSame(Am)) {
                                if (modelId == ((A>>26)&0xFFFF)) {
                                    if ((A&0b110L) != 0 || !this.isFluidSkirtFace((axis+1)*2 + side, other*32 + index)) {
                                        this.blockMesher.skip(1);
                                        continue;
                                    }
                                    skirtFlag = SKIRT_QUAD_FLAG;
                                    lightData = this.fluidSkirtLight(idx);
                                }
                            }

                            if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
                                if (ModelQueries.faceOccludes(meta, (axis << 1) | (1 - side))) {
                                    this.blockMesher.skip(1);
                                    continue;
                                }
                            }
                        }

                        this.blockMesher.putNext(applyQuadLight(
                                (side == 0 ? 0L : 1L) |
                                (A&~LM) |
                                lightData |
                                skirtFlag,
                                Am)
                        );
                    }
                }
                this.blockMesher.endRow();
            }
            this.blockMesher.finish();
        }
        this.blockMesher.doAuxiliaryFaceOffset = true;
    }

    private void generateYZNonOpaqueInnerGeometry(int axis) {
        this.seondaryblockMesher.doAuxiliaryFaceOffset = false;
        this.blockMesher.axis = axis;
        this.seondaryblockMesher.axis = axis;
        for (int layer = 1; layer < 31; layer++) {
            this.blockMesher.auxiliaryPosition = layer;
            this.seondaryblockMesher.auxiliaryPosition = layer;
            int cSkip = 0;
            for (int other = 0; other < 32; other++) {
                int pidx = axis == 0 ? (layer * 32 + other) : (other * 32 + layer);
                int skipAmount = axis==0?32*32:32;

                int msk = this.nonOpaqueMasks[pidx];

                if (msk == 0) {
                    cSkip += 32;
                    continue;
                }

                this.blockMesher.skip(cSkip);
                this.seondaryblockMesher.skip(cSkip);
                cSkip = 0;

                int cIdx = -1;
                while (msk != 0) {
                    int index = Integer.numberOfTrailingZeros(msk);
                    int delta = index - cIdx - 1;
                    cIdx = index;
                    if (delta != 0) {
                        this.blockMesher.skip(delta);
                        this.seondaryblockMesher.skip(delta);
                    }
                    msk &= ~Integer.lowestOneBit(msk);

                    {
                        int idx = index + (pidx * 32);

                        long A = this.sectionData[idx * 2];
                        long B = this.sectionData[idx * 2+1];

                        meshNonOpaqueFace((axis<<1)|0, A, B, this.sectionData[(idx-skipAmount)*2], this.sectionData[(idx-skipAmount)*2+1], this.seondaryblockMesher);
                        meshNonOpaqueFace((axis<<1)|1, A, B, this.sectionData[(idx+skipAmount)*2], this.sectionData[(idx+skipAmount)*2+1], this.blockMesher);
                    }
                }
                this.blockMesher.endRow();
                this.seondaryblockMesher.endRow();
            }
            this.blockMesher.finish();
            this.seondaryblockMesher.finish();
        }
    }

    private void generateYZNonOpaqueOuterGeometry(int axis) {
        this.seondaryblockMesher.doAuxiliaryFaceOffset = false;
        this.blockMesher.axis = axis;
        this.seondaryblockMesher.axis = axis;
        for (int side = 0; side < 2; side++) {
            int layer = side == 0 ? 0 : 31;
            int skipAmount = (axis==0?32*32:32) * (1-(side*2));
            this.blockMesher.auxiliaryPosition = layer;
            this.seondaryblockMesher.auxiliaryPosition = layer;
            int cSkips = 0;
            for (int other = 0; other < 32; other++) {
                int pidx = axis == 0 ? (layer * 32 + other) : (other * 32 + layer);
                int msk = this.nonOpaqueMasks[pidx];
                if (msk == 0) {
                    cSkips += 32;
                    continue;
                }

                this.blockMesher.skip(cSkips);
                this.seondaryblockMesher.skip(cSkips);
                cSkips = 0;

                int cIdx = -1;
                while (msk != 0) {
                    int index = Integer.numberOfTrailingZeros(msk);
                    int delta = index - cIdx - 1;
                    cIdx = index;
                    if (delta != 0) {
                        this.blockMesher.skip(delta);
                        this.seondaryblockMesher.skip(delta);
                    }
                    msk &= ~Integer.lowestOneBit(msk);

                    {
                        int idx = index + (pidx * 32);


                        int neighborIdx = ((axis+1)*32*32 * 2)+(side)*32*32;
                        long neighborId = this.neighboringFaces[neighborIdx + (other*32) + index];

                        long A = this.sectionData[idx * 2];
                        long Am = this.sectionData[idx * 2 + 1];

                        boolean fail = false;
                        if (Mapper.getBlockId(neighborId) != 0) {
                            int modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));


                            if (ModelQueries.cullsSame(Am) && modelId == ((A>>26)&0xFFFF)) {
                                fail = true;
                            } else {
                                long meta = this.modelMan.getModelMetadataFromClientId(modelId);

                                if (ModelQueries.faceOccludes(meta, (axis << 1) | (1 - side))) {
                                    fail = true;
                                } else if (ModelQueries.faceCanBeOccluded(Am, (axis << 1) | side) && this.neighborFaceCovers((axis << 1) | side, A, ((long) modelId)<<26, meta)) {
                                    fail = true;
                                }
                            }
                        }

                        long nA = this.sectionData[(idx+skipAmount) * 2];
                        long nB = this.sectionData[(idx+skipAmount) * 2 + 1];
                        boolean failB = false;

                        if (ModelQueries.cullsSame(nB) && (nA&(0xFFFFL<<26)) == (A&(0xFFFFL<<26))) {
                            failB = true;
                        } else {
                            if (ModelQueries.faceOccludes(nB, (axis << 1) | (side))) {
                                failB = true;
                            } else if (ModelQueries.faceCanBeOccluded(Am, (axis << 1) | (1 - side)) && this.neighborFaceCovers((axis << 1) | (1 - side), A, nA, nB)) {
                                failB = true;
                            }
                        }


                        if (ModelQueries.faceExists(Am, (axis<<1)|1) && ((side==1&&!fail) || (side==0&&!failB))) {
                            this.blockMesher.putNext(applyQuadLight(
                                    (long) (false ? 0L : 1L) |
                                    A |
                                    0,
                                    Am)
                            );
                        } else {
                            this.blockMesher.skip(1);
                        }

                        if (ModelQueries.faceExists(Am, (axis<<1)|0) && ((side==0&&!fail) || (side==1&&!failB))) {
                            this.seondaryblockMesher.putNext(applyQuadLight(
                                    (long) (true ? 0L : 1L) |
                                    A |
                                    0,
                                    Am)
                            );
                        } else {
                            this.seondaryblockMesher.skip(1);
                        }
                    }
                }
                this.blockMesher.endRow();
                this.seondaryblockMesher.endRow();
            }
            this.blockMesher.finish();
            this.seondaryblockMesher.finish();
        }
    }

    private void generateYZFaces() {
        for (int axis = 0; axis < 2; axis++) {
            this.blockMesher.axis = axis;

            this.generateYZOpaqueInnerGeometry(axis);
            this.generateYZOpaqueOuterGeometry(axis);

            this.generateYZFluidInnerGeometry(axis);
            this.generateYZFluidOuterGeometry(axis);
            if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
                this.generateYZNonOpaqueInnerGeometry(axis);
                this.generateYZNonOpaqueOuterGeometry(axis);
            }
        }
    }


    private final Mesher[] xAxisMeshers = new Mesher[32];
    private final Mesher[] secondaryXAxisMeshers = new Mesher[32];
    {
        for (int i = 0; i < 32; i++) {
            var mesher = new Mesher();
            mesher.auxiliaryPosition = i;
            mesher.axis = 2;
            this.xAxisMeshers[i] = mesher;
        }
        if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
            for (int i = 0; i < 32; i++) {
                var mesher = new Mesher();
                mesher.auxiliaryPosition = i;
                mesher.axis = 2;
                mesher.doAuxiliaryFaceOffset = false;
                this.secondaryXAxisMeshers[i] = mesher;
            }
        }
    }

    private static final long X_I_MSK = 0x4210842108421L;

    private void generateXOpaqueInnerGeometry() {
        for (int y = 0; y < 32; y++) {
            long sumA = 0;
            long sumB = 0;
            long sumC = 0;
            int partialHasCount = -1;
            int msk = 0;
            for (int z = 0; z < 32; z++) {
                int lMsk = this.opaqueMasks[y*32+z];
                msk = (lMsk^(lMsk>>>1));
                msk &= -1>>>1;

                sumA += X_I_MSK;
                sumB += X_I_MSK;
                sumC += X_I_MSK;

                partialHasCount &= ~msk;

                if (z == 30 && partialHasCount != 0) {
                    int cmsk = partialHasCount;
                    while (cmsk!=0) {
                        int index = Integer.numberOfTrailingZeros(cmsk);
                        cmsk &= ~Integer.lowestOneBit(cmsk);
                        this.xAxisMeshers[index].skip(31);
                    }
                    sumA &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount), X_I_MSK)*0x1F);
                    sumB &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount)>>11, X_I_MSK)*0x1F);
                    sumC &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount)>>22, X_I_MSK)*0x1F);
                }

                if (msk == 0) {
                    continue;
                }

                int faceForwardMsk = msk&lMsk;
                int iter = msk;
                while (iter!=0) {
                    int index = Integer.numberOfTrailingZeros(iter);
                    iter &= ~Integer.lowestOneBit(iter);

                    var mesher = this.xAxisMeshers[index];

                    int skipCount;
                    {
                        if (index<11) {
                            skipCount = (int) (sumA>>(index*5));
                            sumA &= ~(0x1FL<<(index*5));
                        } else if (index<22) {
                            skipCount = (int) (sumB>>((index-11)*5));
                            sumB &= ~(0x1FL<<((index-11)*5));
                        } else {
                            skipCount = (int) (sumC>>((index-22)*5));
                            sumC &= ~(0x1FL<<((index-22)*5));
                        }
                        skipCount &= 0x1F;
                        skipCount--;
                    }

                    if (skipCount != 0) {
                        mesher.skip(skipCount);
                    }

                    int facingForward = ((faceForwardMsk>>index)&1);
                    {
                        int idx = index + (z * 32) + (y * 32 * 32);

                        int iA = idx * 2 + (facingForward == 1 ? 0 : 2);
                        int iB = idx * 2 + (facingForward == 1 ? 2 : 0);

                        if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
                            if (ModelQueries.faceOccludes(this.sectionData[iB + 1], (2 << 1) | (1 - facingForward))) {
                                mesher.skip(1);
                                continue;
                            }
                        }

                        long selfModel = this.sectionData[iA];
                        long selfMeta  = this.sectionData[iA+1];
                        long nextModel = this.sectionData[iB];

                        mesher.putNext(applyQuadLight(
                                ((long) facingForward) |
                                (selfModel&~LM) |
                                (nextModel&LM),
                                selfMeta
                                )
                        );
                    }
                }
            }

            {
                msk = ~msk;
                while (msk!=0) {
                    int index = Integer.numberOfTrailingZeros(msk);
                    msk &= ~Integer.lowestOneBit(msk);
                    int skipCount;
                    if (index < 11) {
                        skipCount = (int) (sumA>>(index*5));
                    } else if (index<22) {
                        skipCount = (int) (sumB>>((index-11)*5));
                    } else {
                        skipCount = (int) (sumC>>((index-22)*5));
                    }
                    skipCount &= 0x1F;

                    if (skipCount != 0) {
                        this.xAxisMeshers[index].skip(skipCount);
                    }
                }
            }
        }
    }

    private void generateXOuterOpaqueGeometry() {

        var ma = this.xAxisMeshers[0];
        var mb = this.xAxisMeshers[31];
        ma.finish();
        mb.finish();
        ma.doAuxiliaryFaceOffset = false;
        mb.doAuxiliaryFaceOffset = false;

        for (int y = 0; y < 32; y++) {
            int skipA = 0;
            int skipB = 0;
            for (int z = 0; z < 32; z++) {
                int i = y*32+z;
                int msk = this.opaqueMasks[i];
                if ((msk & 1) != 0) {
                    long neighborId = this.neighboringFaces[i];
                    long lightData = (neighborId&(0xFFL<<56))>>>1;
                    boolean oki = true;
                    if (Mapper.getBlockId(neighborId) != 0) {
                        long meta = this.modelMan.getModelMetadataFromClientId(this.modelMan.getModelId(Mapper.getBlockId(neighborId)));
                        if (ModelQueries.isFullyOpaque(meta)) {
                            oki = false;
                        } else if (CHECK_NEIGHBOR_FACE_OCCLUSION && ModelQueries.faceOccludes(meta, (2 << 1) | (1 - 0))) {
                            oki = false;
                        }
                        if (!oki && this.isSkirtFace(0, i)) {
                            oki = true;
                            lightData = this.getSkirtLight(0, i);
                        }
                    }
                    if (oki) {
                        ma.skip(skipA); skipA = 0;
                        long A = this.sectionData[(i<<5) * 2];
                        long Am = this.sectionData[(i<<5) * 2+1];
                        ma.putNext(applyQuadLight(
                                0L |
                                (A&~LM) |
                                lightData,
                                Am
                                )
                        );
                    } else {skipA++;}
                } else {skipA++;}

                if ((msk & (1<<31)) != 0) {
                    long neighborId = this.neighboringFaces[i+32*32];
                    long lightData = (neighborId&(0xFFL<<56))>>>1;
                    boolean oki = true;
                    if (Mapper.getBlockId(neighborId) != 0) {
                        long meta = this.modelMan.getModelMetadataFromClientId(this.modelMan.getModelId(Mapper.getBlockId(neighborId)));
                        if (ModelQueries.isFullyOpaque(meta)) {
                            oki = false;
                        } else if (CHECK_NEIGHBOR_FACE_OCCLUSION && ModelQueries.faceOccludes(meta, (2 << 1) | (1 - 1))) {
                            oki = false;
                        }
                        if (!oki && this.isSkirtFace(1, i)) {
                            oki = true;
                            lightData = this.getSkirtLight(1, i);
                        }
                    }
                    if (oki) {
                        mb.skip(skipB); skipB = 0;
                        long A = this.sectionData[(i*32+31) * 2];
                        long Am = this.sectionData[(i*32+31) * 2+1];
                        mb.putNext(applyQuadLight(
                                1L |
                                (A&~LM) |
                                lightData,
                                Am
                                )
                        );
                    } else {skipB++;}
                } else {skipB++;}
            }
            ma.skip(skipA);
            mb.skip(skipB);
        }

        ma.finish();
        mb.finish();
        ma.doAuxiliaryFaceOffset = true;
        mb.doAuxiliaryFaceOffset = true;
    }

    private void generateXInnerFluidGeometry() {
        for (int y = 0; y < 32; y++) {
            long sumA = 0;
            long sumB = 0;
            long sumC = 0;
            int partialHasCount = -1;
            int msk = 0;
            for (int z = 0; z < 32; z++) {
                int oMsk = this.opaqueMasks[y*32+z];
                int fMsk = this.fluidMasks[y*32+z];
                int lMsk = oMsk|fMsk;
                msk = (lMsk^(lMsk>>>1));
                msk &= -1>>>1;

                msk &= fMsk|(fMsk>>1);

                sumA += X_I_MSK;
                sumB += X_I_MSK;
                sumC += X_I_MSK;

                partialHasCount &= ~msk;

                if (z == 30 && partialHasCount != 0) {
                    int cmsk = partialHasCount;
                    while (cmsk!=0) {
                        int index = Integer.numberOfTrailingZeros(cmsk);
                        cmsk &= ~Integer.lowestOneBit(cmsk);
                        this.xAxisMeshers[index].skip(31);
                    }
                    sumA &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount), X_I_MSK)*0x1F);
                    sumB &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount)>>11, X_I_MSK)*0x1F);
                    sumC &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount)>>22, X_I_MSK)*0x1F);
                }

                if (msk == 0) {
                    continue;
                }

                int faceForwardMsk = msk&lMsk;
                int iter = msk;
                while (iter!=0) {
                    int index = Integer.numberOfTrailingZeros(iter);
                    iter &= ~Integer.lowestOneBit(iter);

                    var mesher = this.xAxisMeshers[index];

                    int skipCount;
                    {
                        if (index<11) {
                            skipCount = (int) (sumA>>(index*5));
                            sumA &= ~(0x1FL<<(index*5));
                        } else if (index<22) {
                            skipCount = (int) (sumB>>((index-11)*5));
                            sumB &= ~(0x1FL<<((index-11)*5));
                        } else {
                            skipCount = (int) (sumC>>((index-22)*5));
                            sumC &= ~(0x1FL<<((index-22)*5));
                        }
                        skipCount &= 0x1F;
                        skipCount--;
                    }

                    if (skipCount != 0) {
                        mesher.skip(skipCount);
                    }

                    int facingForward = ((faceForwardMsk>>index)&1);
                    {
                        int idx = index + (z * 32) + (y * 32 * 32);

                        int ai = (idx+(1-facingForward))*2;
                        int bi = (idx+facingForward)*2;

                        if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
                            if (ModelQueries.faceOccludes(this.sectionData[bi + 1], (2 << 1) | (1 - facingForward))) {
                                mesher.skip(1);
                                continue;
                            }
                        }

                        long A = this.sectionData[ai];
                        long Am = this.sectionData[ai+1];

                        if (ModelQueries.containsFluid(Am)) {
                            int modelId = (int) ((A>>26)&0xFFFF);
                            A &= ~(0xFFFFL<<26);
                            int fluidId = this.modelMan.getFluidClientStateId(modelId);
                            A |= Integer.toUnsignedLong(fluidId)<<26;
                            Am = this.modelMan.getModelMetadataFromClientId(fluidId);

                            A &= ~0b110L; A |= getQuadTyping(Am);
                        }

                        long lighter = this.sectionData[bi];

                        mesher.putNext(applyQuadLight(
                                ((long) facingForward) |
                                (A&~LM) |
                                (lighter&LM),
                                Am
                                )
                        );
                    }
                }
            }

            {
                msk = ~msk;
                while (msk!=0) {
                    int index = Integer.numberOfTrailingZeros(msk);
                    msk &= ~Integer.lowestOneBit(msk);
                    int skipCount;
                    if (index < 11) {
                        skipCount = (int) (sumA>>(index*5));
                    } else if (index<22) {
                        skipCount = (int) (sumB>>((index-11)*5));
                    } else {
                        skipCount = (int) (sumC>>((index-22)*5));
                    }
                    skipCount &= 0x1F;

                    if (skipCount != 0) {
                        this.xAxisMeshers[index].skip(skipCount);
                    }
                }
            }
        }
    }

    private void generateXOuterFluidGeometry() {

        var ma = this.xAxisMeshers[0];
        var mb = this.xAxisMeshers[31];
        ma.finish();
        mb.finish();
        ma.doAuxiliaryFaceOffset = false;
        mb.doAuxiliaryFaceOffset = false;

        for (int y = 0; y < 32; y++) {
            int skipA = 0;
            int skipB = 0;
            for (int z = 0; z < 32; z++) {
                int i = y*32+z;
                int msk = this.fluidMasks[i];
                if ((msk & 1) != 0) {
                    long neighborId = this.neighboringFaces[i];
                    boolean oki = true;
                    long skirtFlag = 0;
                    long lightData = (neighborId&(0xFFL<<56))>>>1;

                    int sidx = (i<<5) * 2;
                    long A = this.sectionData[sidx];
                    long Am = this.sectionData[sidx + 1];

                    if (ModelQueries.containsFluid(Am)) {
                        int modelId = (int) ((A>>26)&0xFFFF);
                        A &= ~(0xFFFFL<<26);
                        int fluidId = this.modelMan.getFluidClientStateId(modelId);
                        A |= Integer.toUnsignedLong(fluidId)<<26;
                        Am = this.modelMan.getModelMetadataFromClientId(fluidId);

                        A &= ~0b110L; A |= getQuadTyping(Am);
                    }


                    if (Mapper.getBlockId(neighborId) != 0) {

                        int modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));
                        long meta = this.modelMan.getModelMetadataFromClientId(modelId);
                        if (ModelQueries.isFullyOpaque(meta)) {
                            oki = false;
                        }

                        if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
                            if (ModelQueries.faceOccludes(meta, (2 << 1) | (1-0))) {
                                oki = false;
                            }
                        }

                        if (ModelQueries.containsFluid(meta)) {
                            modelId = this.modelMan.getFluidClientStateId(modelId);
                        }

                        if (ModelQueries.cullsSame(Am)) {
                            if (modelId == ((A>>26)&0xFFFF)) {
                                if (oki && (A&0b110L) == 0 && this.isFluidSkirtFace(0, i)) {
                                    skirtFlag = SKIRT_QUAD_FLAG;
                                    lightData = this.fluidSkirtLight(i<<5);
                                } else {
                                    oki = false;
                                }
                            }
                        }
                    }

                    if (oki) {
                        ma.skip(skipA); skipA = 0;

                        ma.putNext(applyQuadLight(
                                0L |
                                (A&~LM) |
                                lightData |
                                skirtFlag,
                                Am
                                )
                        );
                    } else {skipA++;}
                } else {skipA++;}

                if ((msk & (1<<31)) != 0) {
                    long neighborId = this.neighboringFaces[i+32*32];
                    boolean oki = true;
                    long skirtFlag = 0;
                    long lightData = (neighborId&(0xFFL<<56))>>>1;


                    int sidx = (i*32+31) * 2;
                    long A = this.sectionData[sidx];
                    long Am = this.sectionData[sidx + 1];

                    if (ModelQueries.containsFluid(Am)) {
                        int modelId = (int) ((A>>26)&0xFFFF);
                        A &= ~(0xFFFFL<<26);
                        int fluidId = this.modelMan.getFluidClientStateId(modelId);
                        A |= Integer.toUnsignedLong(fluidId)<<26;
                        Am = this.modelMan.getModelMetadataFromClientId(fluidId);

                        A &= ~0b110L; A |= getQuadTyping(Am);
                    }



                    if (Mapper.getBlockId(neighborId) != 0) {
                        int modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));
                        long meta = this.modelMan.getModelMetadataFromClientId(modelId);
                        if (ModelQueries.isFullyOpaque(meta)) {
                            oki = false;
                        }

                        if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
                            if (ModelQueries.faceOccludes(meta, (2 << 1) | (1-1))) {
                                oki = false;
                            }
                        }

                        if (ModelQueries.containsFluid(meta)) {
                            modelId = this.modelMan.getFluidClientStateId(modelId);
                        }

                        if (ModelQueries.cullsSame(Am)) {
                            if (modelId == ((A>>26)&0xFFFF)) {
                                if (oki && (A&0b110L) == 0 && this.isFluidSkirtFace(1, i)) {
                                    skirtFlag = SKIRT_QUAD_FLAG;
                                    lightData = this.fluidSkirtLight(i*32+31);
                                } else {
                                    oki = false;
                                }
                            }
                        }
                    }

                    if (oki) {
                        mb.skip(skipB); skipB = 0;

                        mb.putNext(applyQuadLight(
                                1L |
                                (A&~LM) |
                                lightData |
                                skirtFlag,
                                Am
                                )
                        );
                    } else {skipB++;}
                } else {skipB++;}
            }
            ma.skip(skipA);
            mb.skip(skipB);
        }

        ma.finish();
        mb.finish();
        ma.doAuxiliaryFaceOffset = true;
        mb.doAuxiliaryFaceOffset = true;
    }

    private void generateXNonOpaqueInnerGeometry() {
        for (int y = 0; y < 32; y++) {
            long sumA = 0;
            long sumB = 0;
            long sumC = 0;
            int partialHasCount = -1;
            int msk = 0;
            for (int z = 0; z < 32; z++) {
                msk = this.nonOpaqueMasks[y*32+z]&(~0x80000001);

                sumA += X_I_MSK;
                sumB += X_I_MSK;
                sumC += X_I_MSK;

                partialHasCount &= ~msk;

                if (z == 30 && partialHasCount != 0) {
                    int cmsk = partialHasCount;
                    while (cmsk!=0) {
                        int index = Integer.numberOfTrailingZeros(cmsk);
                        cmsk &= ~Integer.lowestOneBit(cmsk);

                        this.xAxisMeshers[index].skip(31);
                        this.secondaryXAxisMeshers[index].skip(31);
                    }
                    sumA &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount), X_I_MSK)*0x1F);
                    sumB &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount)>>11, X_I_MSK)*0x1F);
                    sumC &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount)>>22, X_I_MSK)*0x1F);
                }

                if (msk == 0) {
                    continue;
                }

                int iter = msk;
                while (iter!=0) {
                    int index = Integer.numberOfTrailingZeros(iter);
                    iter &= ~Integer.lowestOneBit(iter);


                    int skipCount;
                    {
                        if (index<11) {
                            skipCount = (int) (sumA>>(index*5));
                            sumA &= ~(0x1FL<<(index*5));
                        } else if (index<22) {
                            skipCount = (int) (sumB>>((index-11)*5));
                            sumB &= ~(0x1FL<<((index-11)*5));
                        } else {
                            skipCount = (int) (sumC>>((index-22)*5));
                            sumC &= ~(0x1FL<<((index-22)*5));
                        }
                        skipCount &= 0x1F;
                        skipCount--;
                    }

                    var mesherA = this.xAxisMeshers[index];
                    var mesherB = this.secondaryXAxisMeshers[index];
                    if (skipCount != 0) {
                        mesherA.skip(skipCount);
                        mesherB.skip(skipCount);
                    }

                    {
                        int idx = index + (z * 32) + (y * 32 * 32);

                        long A = this.sectionData[idx*2];
                        long Am = this.sectionData[idx*2+1];

                        meshNonOpaqueFace(2<<1, A, Am, this.sectionData[(idx-1)*2], this.sectionData[(idx-1)*2+1], mesherB);
                        meshNonOpaqueFace((2<<1)|1, A, Am, this.sectionData[(idx+1)*2], this.sectionData[(idx+1)*2+1], mesherA);
                    }
                }
            }

            {
                msk = ~msk;
                while (msk!=0) {
                    int index = Integer.numberOfTrailingZeros(msk);
                    msk &= ~Integer.lowestOneBit(msk);
                    int skipCount;
                    if (index < 11) {
                        skipCount = (int) (sumA>>(index*5));
                    } else if (index<22) {
                        skipCount = (int) (sumB>>((index-11)*5));
                    } else {
                        skipCount = (int) (sumC>>((index-22)*5));
                    }
                    skipCount &= 0x1F;

                    if (skipCount != 0) {
                        this.xAxisMeshers[index].skip(skipCount);
                        this.secondaryXAxisMeshers[index].skip(skipCount);
                    }
                }
            }
        }
    }



    private void dualMeshNonOpaqueOuterX(int side, long quad, long meta, int neighborAId, int neighborLight, long neighborAMeta, long neighborBQuad, long neighborBMeta, Mesher ma, Mesher mb) {

        if ((neighborAId==0 && ModelQueries.faceExists(meta, ((2<<1)|0)^side))||(neighborAId!=0&&shouldMeshNonOpaqueBlockFace(((2<<1)|0)^side, quad, meta, ((long)neighborAId)<<26, neighborAMeta))) {
            ma.putNext(applyQuadLight(
                    ((long)side)|
                    (quad&~LM) |
                    (ModelQueries.faceUsesSelfLighting(meta, ((2<<1)|0)^side)?quad:(((long)neighborLight)<<55)),
                    meta
                    )
            );
        } else {
            ma.skip(1);
        }

        if (shouldMeshNonOpaqueBlockFace(((2<<1)|1)^side, quad, meta, neighborBQuad, neighborBMeta)) {
            mb.putNext(applyQuadLight(
                    ((long)(side^1))|
                    (quad&~LM) |
                    ((ModelQueries.faceUsesSelfLighting(meta, ((2<<1)|1)^side)?quad:neighborBQuad)&(0xFFL<<55)),
                    meta
                    )
            );
        } else {
            mb.skip(1);
        }
    }

    private void generateXNonOpaqueOuterGeometry() {
        var npx = this.xAxisMeshers[0]; npx.finish();
        var nnx = this.secondaryXAxisMeshers[0]; nnx.finish();
        var ppx = this.xAxisMeshers[31]; ppx.finish();
        var pnx = this.secondaryXAxisMeshers[31]; pnx.finish();

        for (int y = 0; y < 32; y++) {
            int skipA = 0;
            int skipB = 0;
            for (int z = 0; z < 32; z++) {
                int i = y*32+z;
                int msk = this.nonOpaqueMasks[i];
                if ((msk & 1) != 0) {
                    long neighborId = this.neighboringFaces[i];

                    int sidx = (i<<5) * 2;
                    long A = this.sectionData[sidx];
                    long Am = this.sectionData[sidx + 1];

                    int modelId = 0;
                    long nM = 0;
                    if (Mapper.getBlockId(neighborId) != 0) {
                        modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));
                        nM = this.modelMan.getModelMetadataFromClientId(modelId);
                    }

                    nnx.skip(skipA);
                    npx.skip(skipA); skipA = 0;

                    dualMeshNonOpaqueOuterX(0, A, Am, modelId, Mapper.getLightId(neighborId), nM, this.sectionData[sidx+2], this.sectionData[sidx+3], nnx, npx);
                } else {skipA++;}

                if ((msk & (1<<31)) != 0) {
                    long neighborId = this.neighboringFaces[i+32*32];


                    int sidx = (i*32+31) * 2;
                    long A = this.sectionData[sidx];
                    long Am = this.sectionData[sidx + 1];

                    int modelId = 0;
                    long nM = 0;
                    if (Mapper.getBlockId(neighborId) != 0) {
                        modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));
                        nM = this.modelMan.getModelMetadataFromClientId(modelId);
                    }

                    pnx.skip(skipB);
                    ppx.skip(skipB); skipB = 0;

                    dualMeshNonOpaqueOuterX(1, A, Am, modelId, Mapper.getLightId(neighborId), nM, this.sectionData[sidx-2], this.sectionData[sidx-1], ppx, pnx);
                } else {skipB++;}
            }
            nnx.skip(skipA);
            npx.skip(skipA);
            pnx.skip(skipB);
            ppx.skip(skipB);
        }
    }

    private void generateXFaces() {
        this.generateXOpaqueInnerGeometry();
        this.generateXOuterOpaqueGeometry();

        for (var mesher : this.xAxisMeshers) {
            mesher.finish();
        }

        this.generateXInnerFluidGeometry();
        this.generateXOuterFluidGeometry();

        for (var mesher : this.xAxisMeshers) {
            mesher.finish();
        }
        if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
            this.generateXNonOpaqueInnerGeometry();
            this.generateXNonOpaqueOuterGeometry();

            for (var mesher : this.xAxisMeshers) {
                mesher.finish();
            }
            for (var mesher : this.secondaryXAxisMeshers) {
                mesher.finish();
            }
        }
    }

    private final int occupancyBarrier(int index) {
        int occ = 0;
        int msk = this.opaqueMasks[index];
        occ |= msk^(msk>>1);
        occ |= msk^(msk<<1);
        occ |= index<32*31?msk^this.opaqueMasks[index+32]:0;
        occ |= 31<index   ?msk^this.opaqueMasks[index-32]:0;
        occ |= (index&31)<31?msk^this.opaqueMasks[index+1]:0;
        occ |= 0< (index&31)?msk^this.opaqueMasks[index-1]:0;
        return occ;
    }

    private final void buildOccupancy() {
        for (int i = 0; i < 32*32; i++) {
            int occ = this.occupancyBarrier(i);
            for (;occ!=0;occ&=~Integer.lowestOneBit(occ)) {
                this.occupancy.set(i*32+Integer.numberOfTrailingZeros(occ));
            }
        }
    }

    private final void buildOccupancy16() {
        for (int i = 0; i < 16*16; i++) {
            int x = (i&15)*2;
            int y = (i>>4)*2;
            int A = this.occupancyBarrier(y*32+x); A = (A|(A>>16))&0xFFFF;
            int B = this.occupancyBarrier(y*32+x+1); B = (B|(B>>16))&0xFFFF;
            int C = this.occupancyBarrier((y+1)*32+x); C = (C|(C>>16))&0xFFFF;
            int D = this.occupancyBarrier((y+1)*32+x+1); D = (D|(D>>16))&0xFFFF;
            int occ = A|B|C|D;

            for (;occ!=0;occ&=~Integer.lowestOneBit(occ)) {
                this.occupancy.set(i*16+Integer.numberOfTrailingZeros(occ));
            }
        }
    }

    public BuiltSection generateMesh(WorldSection section) {

        this.quadCount = 0;

        {
            this.blockMesher.reset();
            this.blockMesher.doAuxiliaryFaceOffset = true;
            this.seondaryblockMesher.reset();
            this.seondaryblockMesher.doAuxiliaryFaceOffset = true;
            for (var mesher : this.xAxisMeshers) {
                mesher.reset();
                mesher.doAuxiliaryFaceOffset = true;
            }
            if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
                for (var mesher : this.secondaryXAxisMeshers) {
                    mesher.reset();
                    mesher.doAuxiliaryFaceOffset = false;
                }
            }
        }
        if (this.occupancy != null) {
            this.occupancy.reset();
        }

        this.minX = Integer.MAX_VALUE;
        this.minY = Integer.MAX_VALUE;
        this.minZ = Integer.MAX_VALUE;
        this.maxX = Integer.MIN_VALUE;
        this.maxY = Integer.MIN_VALUE;
        this.maxZ = Integer.MIN_VALUE;

        Arrays.fill(this.quadCounters,0);
        Arrays.fill(this.opaqueMasks, 0);
        Arrays.fill(this.nonOpaqueMasks, 0);
        Arrays.fill(this.fluidMasks, 0);
        this.skirtMasksReady = 0;
        this.skirtsAllowed = section.lvl != 0;

        int neighborMskAndFlags = this.prepareSectionData(section._unsafeGetRawDataArray());
        if ((neighborMskAndFlags&(1<<31))!=0) {
            throw new IdNotYetComputedException(neighborMskAndFlags&((1<<20)-1), true);
        }
        int neighborMsk = neighborMskAndFlags&0b11_11_11;
        int flags = neighborMskAndFlags>>>6;
        if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
            this.acquireNeighborData(section, neighborMsk);
        }

        try {
            this.generateYZFaces();
            this.generateXFaces();
        } catch (IdNotYetComputedException e) {
            e.auxBitMsk = neighborMsk;
            e.auxData = this.neighboringFaces;
            throw e;
        }

        if (this.occupancy != null && section.lvl == 0  && this.quadCount != 0 && (flags&1) != 0) {
            this.buildOccupancy();
        }

        if (this.quadCount == 0) {
            return BuiltSection.emptyWithChildren(section.key, section.getNonEmptyChildren());
        }

        if (this.quadCount >= 1<<16) {
            Logger.warn("Large quad count for section " + WorldEngine.pprintPos(section.key) + " is " + this.quadCount);
        }

        if (this.minX<0 || this.minY<0 || this.minZ<0 || 32<this.maxX || 32<this.maxY || 32<this.maxZ) {
            throw new IllegalStateException();
        }

        int[] offsets = new int[8];
        var buff = new MemoryBuffer(this.quadCount * 8L);
        long ptr = buff.address;
        int coff = 0;
        for (int buffer = 0; buffer < 8; buffer++) {
            offsets[buffer] = coff;
            int size = this.quadCounters[buffer];
            UnsafeUtil.memcpy(this.quadBufferPtr + (buffer*(8*(1<<16))), ptr + coff*8L, (size* 8L));
            coff += size;
        }



        int aabb = 0;
        aabb |= this.minX;
        aabb |= this.minY<<5;
        aabb |= this.minZ<<10;
        aabb |= Math.max(0,this.maxX-this.minX-1)<<15;
        aabb |= Math.max(0,this.maxY-this.minY-1)<<20;
        aabb |= Math.max(0,this.maxZ-this.minZ-1)<<25;

        MemoryBuffer occupancy = null;
        if (this.occupancy != null && !this.occupancy.isEmpty()) {
            occupancy = new MemoryBuffer(this.occupancy.writeSize());
            this.occupancy.write(occupancy.address, false);
        }

        return new BuiltSection(section.key, section.getNonEmptyChildren(), aabb, buff, offsets, occupancy);
    }

    public void free() {
        this.quadBuffer.free();
    }
}