package me.cortex.voxy.common.util;

import it.unimi.dsi.fastutil.longs.LongRBTreeSet;

public class AllocationArena {
    public static final long SIZE_LIMIT = -1;

    private static final int ADDR_BITS = 34;
    private static final int SIZE_BITS = 64 - ADDR_BITS;
    private static final long SIZE_MSK = (1L<<SIZE_BITS)-1;
    private static final long ADDR_MSK = (1L<<ADDR_BITS)-1;
    private final LongRBTreeSet FREE = new LongRBTreeSet(Long::compareUnsigned);
    private final LongRBTreeSet TAKEN = new LongRBTreeSet(Long::compareUnsigned);

    private long sizeLimit = Long.MAX_VALUE;
    private long totalSize;
    private boolean resized;

    public void reset() {
        this.FREE.clear();
        this.TAKEN.clear();
        this.sizeLimit = Long.MAX_VALUE;
        this.totalSize = 0;
        this.resized = false;
    }

    public boolean getResetResized() {
        boolean ret = this.resized;
        this.resized = false;
        return ret;
    }

    public long getSize() {
        return this.totalSize;
    }


    public int numFreeBlocks() {
        return this.FREE.size();
    }

    public int getLargestFreeBlockSize(int index) {
        var iter = this.FREE.tailSet(-1).iterator();
        for (;index>0&&iter.hasPrevious();index--){iter.previousLong();}
        long slot = iter.previousLong();
        return (int) (slot>>ADDR_BITS);
    }

    public long alloc(int size) {
        if (size == 0) throw new IllegalArgumentException();
        var iter = this.FREE.iterator(((long) size << ADDR_BITS)-1);
        if (!iter.hasNext()) {
            this.resized = true;
            long addr = this.totalSize;
            if (this.totalSize+size>this.sizeLimit) {
                return SIZE_LIMIT;
            }
            this.totalSize += size;
            this.TAKEN.add((addr<<SIZE_BITS)|((long) size));
            return addr;
        } else {
            long slot = iter.nextLong();
            iter.remove();
            if ((slot >>> ADDR_BITS) == size) {
                this.TAKEN.add((slot<<SIZE_BITS)|(slot >>> ADDR_BITS));
            } else {
                this.TAKEN.add(((slot&ADDR_MSK)<<SIZE_BITS)|size);
                this.FREE.add((((slot >>> ADDR_BITS)-size)<<ADDR_BITS)|((slot&ADDR_MSK)+size));
            }
            return slot&ADDR_MSK;
        }
    }

    public int free(long addr) {
        addr &= ADDR_MSK;
        var iter = this.TAKEN.iterator(addr<<SIZE_BITS);
        long slot = iter.nextLong();
        if (slot>>SIZE_BITS != addr) {
            throw new IllegalStateException();
        }
        long size = slot&SIZE_MSK;
        iter.remove();

        if (iter.hasPrevious()) {
            long prevSlot = iter.previousLong();
            long endAddr = (prevSlot>>>SIZE_BITS) + (prevSlot&SIZE_MSK);
            if (endAddr != addr) {
                long delta = (addr - endAddr);
                this.FREE.remove((delta<<ADDR_BITS)|endAddr);
                slot = (endAddr<<SIZE_BITS) | ((slot&SIZE_MSK) + delta);
            }
            iter.nextLong();
        }
        else if (!this.FREE.isEmpty()) {
            if (this.FREE.remove(addr<<ADDR_BITS)) {
                slot = addr + size;
            }
        }

        if (iter.hasNext()) {
            long nextSlot = iter.nextLong();
            long endAddr = (slot>>>SIZE_BITS) + (slot&SIZE_MSK);
            if (endAddr != nextSlot>>>SIZE_BITS) {
                long delta = ((nextSlot>>>SIZE_BITS) - endAddr);
                this.FREE.remove((delta<<ADDR_BITS)|endAddr);
                slot = (slot&(ADDR_MSK<<SIZE_BITS)) | ((slot&SIZE_MSK) + delta);
            }
        }
        else {
            this.resized = true;
            this.totalSize -= (slot&SIZE_MSK);
            return (int) size;
        }

        slot = (slot>>>SIZE_BITS) | (slot<<ADDR_BITS);
        this.FREE.add(slot);
        return (int) size;
    }



    public boolean expand(long addr, int extra) {
        addr &= ADDR_MSK;
        var iter = this.TAKEN.iterator(addr<<SIZE_BITS);
        if (!iter.hasNext()) {
            return false;
        }
        long slot = iter.nextLong();
        if (slot>>SIZE_BITS != addr) {
            throw new IllegalStateException();
        }
        long updatedSlot = (slot & (ADDR_MSK << SIZE_BITS)) | ((slot & SIZE_MSK) + extra);
        if (iter.hasNext()) {
            long next = iter.nextLong();
            long endAddr = (slot>>>SIZE_BITS)+(slot&SIZE_MSK);
            long delta = (next>>>SIZE_BITS) - endAddr;
            if (extra <= delta) {
                this.FREE.remove((delta<<ADDR_BITS)|endAddr);
                iter.previousLong();
                iter.previousLong();
                iter.remove();
                this.TAKEN.add(updatedSlot);
                if (extra != delta) {
                    this.FREE.add(((delta-extra)<<ADDR_BITS)|(endAddr+extra));
                }
                return true;
            } else {
                return false;
            }
        } else {
            if (this.totalSize+extra>this.sizeLimit)
                return false;
            iter.remove();
            this.TAKEN.add(updatedSlot);
            this.totalSize += extra;
            return true;
        }
    }

    public long getSize(long addr) {
        addr &= ADDR_MSK;
        var iter = this.TAKEN.iterator(addr << SIZE_BITS);
        if (!iter.hasNext())
            throw new IllegalArgumentException();
        long slot = iter.nextLong();
        if (slot>>SIZE_BITS != addr) {
            throw new IllegalStateException();
        }
        return slot&SIZE_MSK;
    }
    
    public void setLimit(long size) {
        this.sizeLimit = size;
        if (this.sizeLimit < this.totalSize) {
            throw new IllegalStateException("Size set smaller than current size");
        }
    }

    public long getLimit() {
        return this.sizeLimit;
    }
}
