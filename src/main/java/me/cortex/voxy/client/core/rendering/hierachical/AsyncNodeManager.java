package me.cortex.voxy.client.core.rendering.hierachical;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntConsumer;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.client.TimingStatistics;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.GeometryCache;
import me.cortex.voxy.client.core.rendering.SectionUpdateRouter;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicAsyncGeometryManager;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.AllocationArena;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.UnsafeUtil;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.commonImpl.VoxyCommon;
import org.lwjgl.system.MemoryUtil;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.StampedLock;

import static org.lwjgl.opengl.ARBUniformBufferObject.glBindBufferBase;
import static org.lwjgl.opengl.GL30C.glUniform1ui;
import static org.lwjgl.opengl.GL42C.GL_UNIFORM_BARRIER_BIT;
import static org.lwjgl.opengl.GL42C.glMemoryBarrier;
import static org.lwjgl.opengl.GL43C.*;

public class AsyncNodeManager {
    private static final boolean VERIFY_NODE_MANAGER = VoxyCommon.isVerificationFlagOn("verifyNodeManager");
    private static final VarHandle RESULT_HANDLE;
    private static final VarHandle RESULT_CACHE_1_HANDLE;
    private static final VarHandle RESULT_CACHE_2_HANDLE;
    static {
        try {
            RESULT_HANDLE = MethodHandles.lookup().findVarHandle(AsyncNodeManager.class, "results", SyncResults.class);
            RESULT_CACHE_1_HANDLE = MethodHandles.lookup().findVarHandle(AsyncNodeManager.class, "resultCache1", SyncResults.class);
            RESULT_CACHE_2_HANDLE = MethodHandles.lookup().findVarHandle(AsyncNodeManager.class, "resultCache2", SyncResults.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private final Thread thread;
    public final int maxNodeCount;
    private final long geometryCapacity;
    private volatile boolean running = true;
    private volatile Throwable uncaughtException;

    private final NodeManager manager;
    private final BasicAsyncGeometryManager geometryManager;
    private final IGeometryData geometryData;
    private final SectionUpdateRouter router;

    private final GeometryCache geometryCache = new GeometryCache(1L<<32);

    private final AtomicInteger workCounter = new AtomicInteger();

    @SuppressWarnings("FieldMayBeFinal")
    private volatile SyncResults results = null, resultCache1 = new SyncResults(), resultCache2 = new SyncResults();


    private final IntOpenHashSet tlnIdChange = new IntOpenHashSet();
    private final IntOpenHashSet cleanerIdResetClear = new IntOpenHashSet();

    private boolean needsWaitForSync = false;

    public AsyncNodeManager(int maxNodeCount, IGeometryData geometryData, RenderGenerationService renderService) {
        this.geometryData = geometryData;
        this.geometryCapacity = ((BasicSectionGeometryData)geometryData).getGeometryCapacityBytes();

        this.maxNodeCount = maxNodeCount;

        this.thread = new Thread(()->{
            try {
                while (this.running) {
                    this.run();
                }
            } catch (Exception e) {
                Logger.error("Critical error occurred in async processor, things will be broken", e);
                throw e;
            }
        });
        this.thread.setUncaughtExceptionHandler((t,e)->{
            if (e == null) {
                e = new RuntimeException("null throwable");
            }
            this.uncaughtException = e;
            this.running = false;
        });
        this.thread.setName("Async Node Manager");

        this.geometryManager = new BasicAsyncGeometryManager(((BasicSectionGeometryData)geometryData).getMaxSectionCount(), this.geometryCapacity);

        this.router = new SectionUpdateRouter();
        this.router.setCallbacks(pos->{
            var cachedGeometry = this.geometryCache.remove(pos);
            if (cachedGeometry != null) {
                this.submitGeometryResult(cachedGeometry);
            } else {
                renderService.enqueueTask(pos);
            }
        }, renderService::enqueueTask, this::submitChildChange);
        renderService.setResultConsumer(this::submitGeometryResult);

        this.manager = new NodeManager(maxNodeCount, this.geometryManager, this.router);

        this.manager.setClear(new NodeManager.ICleaner() {
            @Override
            public void alloc(int id) {
                AsyncNodeManager.this.cleanerIdResetClear.remove(id);
                AsyncNodeManager.this.cleanerIdResetClear.add(id|(1<<31));
            }

            @Override
            public void move(int from, int to) {
            }

            @Override
            public void free(int id) {
                AsyncNodeManager.this.cleanerIdResetClear.remove(id|(1<<31));
                AsyncNodeManager.this.cleanerIdResetClear.add(id);
            }
        });
        this.manager.setTLNCallbacks(id->{
            if (!this.tlnIdChange.remove(id)) {
                if (!this.tlnIdChange.add(id|(1<<31))) {
                    throw new IllegalStateException();
                }
            }
        }, id -> {
            if (!this.tlnIdChange.remove(id|(1<<31))) {
                if (!this.tlnIdChange.add(id)) {
                    throw new IllegalStateException();
                }
            }
        });
    }

    private SyncResults getMakeResultObject() {
        SyncResults resultSet = (SyncResults)RESULT_CACHE_1_HANDLE.getAndSet(this, null);
        if (resultSet == null) {
            resultSet = (SyncResults)RESULT_CACHE_2_HANDLE.getAndSet(this, null);
        }
        if (resultSet == null) {
            throw new IllegalStateException("There should always be an object in the result set cache pair");
        }
        resultSet.reset();
        return resultSet;
    }

    private final Shader scatterWrite = Shader.make()
            .define("INPUT_BUFFER_BINDING", 0)
            .define("OUTPUT_BUFFER1_BINDING", 1)
            .define("OUTPUT_BUFFER2_BINDING", 2)
            .add(ShaderType.COMPUTE, "voxy:util/scatter.comp")
            .compile();

    private final Shader multiMemcpy = Shader.make()
            .define("INPUT_HEADER_BUFFER_BINDING", 0)
            .define("INPUT_DATA_BUFFER_BINDING", 1)
            .define("OUTPUT_BUFFER_BINDING", 2)
            .add(ShaderType.COMPUTE, "voxy:util/memcpy.comp")
            .compile();

    private void run() {
        if (this.workCounter.get() <= 0) {
            LockSupport.park();
            if (this.workCounter.get() <= 0 || !this.running) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        if (!this.running) {
            return;
        }


        int workDone = 0;

        {
            LongOpenHashSet add = null;
            LongOpenHashSet rem = null;
            long stamp = this.tlnLock.writeLock();

            if (!this.tlnAdd.isEmpty()) {
                add = new LongOpenHashSet(this.tlnAdd);
                this.tlnAdd.clear();
            }
            if (!this.tlnRem.isEmpty()) {
                rem = new LongOpenHashSet(this.tlnRem);
                this.tlnRem.clear();
            }

            this.tlnLock.unlockWrite(stamp);
            int work = 0;
            if (rem != null) {
                var iter = rem.longIterator();
                while (iter.hasNext()) {
                    this.manager.removeTopLevelNode(iter.nextLong());
                    work++;
                }
            }

            if (add != null) {
                var iter = add.longIterator();
                while (iter.hasNext()) {
                    this.manager.insertTopLevelNode(iter.nextLong());
                    work++;
                }
            }

            workDone += work;
        }

        do {
            var job = this.childUpdateQueue.poll();
            if (job == null)
                break;
            workDone++;
            this.manager.processChildChange(job.key, job.getNonEmptyChildren());
            job.release();
        } while (true);


        long estimatedGeometryUploadAmount = 0;
        for (int limit = 0; limit < 300 && ((this.geometryCapacity-this.geometryManager.getGeometryUsedBytes())>50_000_000L) && estimatedGeometryUploadAmount<1_000L<<10; limit++) {
            var job = this.geometryUpdateQueue.poll();
            if (job == null)
                break;
            workDone++;
            this.manager.processGeometryResult(job);
            if (job.geometryBuffer!=null) {
                estimatedGeometryUploadAmount += job.geometryBuffer.size;
            }
        }

        while (true) {
            var job = this.requestBatchQueue.poll();
            if (job == null)
                break;
            workDone++;
            long ptr = job.address;
            int count = MemoryUtil.memGetInt(ptr);
            ptr += 8;
            if (job.size < count * 8L + 8) {
                throw new IllegalStateException();
            }
            for (int i = 0; i < count; i++) {
                long pos = ((long) MemoryUtil.memGetInt(ptr)) << 32; ptr += 4;
                pos |= Integer.toUnsignedLong(MemoryUtil.memGetInt(ptr)); ptr += 4;
                this.manager.processRequest(pos);
            }
            job.free();
        }


        do {
            var job = this.removeBatchQueue.poll();
            if (job == null)
                break;
            workDone++;
            long ptr = job.address;
            int zeroCount = 0;
            for (int i = 0; i < NodeCleaner.OUTPUT_COUNT; i++) {
                long pos = ((long) MemoryUtil.memGetInt(ptr)) << 32; ptr += 4;
                pos |= Integer.toUnsignedLong(MemoryUtil.memGetInt(ptr)); ptr += 4;

                if (pos == -1) {
                    continue;
                }

                if (pos == 0 && zeroCount++>0) {
                    Logger.error("Remove node pos is 0 " + zeroCount + " times, this is really bad, please report" );
                    continue;
                }

                this.manager.removeNodeGeometry(pos);
            }
            job.free();
        } while (true);

        if (this.workCounter.addAndGet(-workDone) < 0) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            if (this.workCounter.get() < 0) {
                Logger.error("Work counter less than zero, hope it fixes itself...");
            }
        }

        if (workDone == 0) {
            return;
        }

        if (this.needsWaitForSync) {
            while (RESULT_HANDLE.get(this) != null && this.running) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }


        var prev = (SyncResults) RESULT_HANDLE.getAndSet(this, null);
        SyncResults results = null;
        if (prev == null) {
            this.needsWaitForSync = false;
            results = this.getMakeResultObject();
            results.tlnDelta.addAll(this.tlnIdChange);
            this.tlnIdChange.clear();

            if (!this.geometryManager.getUploads().isEmpty()){
                var iter = this.geometryManager.getUploads().int2ObjectEntrySet().fastIterator();
                while (iter.hasNext()) {
                    var val = iter.next();
                    results.geometryUpload.upload(val.getIntKey(), val.getValue());
                    val.getValue().free();
                }
                this.geometryManager.getUploads().clear();
            }

            this.geometryManager.getHeapRemovals().clear();
            results.cleanerOperations.addAll(this.cleanerIdResetClear); this.cleanerIdResetClear.clear();
        } else {
            results = prev;

            if (!this.tlnIdChange.isEmpty()) {
                var iter = this.tlnIdChange.intIterator();
                while (iter.hasNext()) {
                    int val = iter.nextInt();
                    if (!results.tlnDelta.remove(val ^ (1 << 31))) {
                        results.tlnDelta.add(val);
                    }
                }
                this.tlnIdChange.clear();
            }

            if (!this.cleanerIdResetClear.isEmpty()) {
                var iter = this.cleanerIdResetClear.intIterator();
                while (iter.hasNext()) {
                    int val = iter.nextInt();
                    results.cleanerOperations.remove(val^(1<<31));
                    results.cleanerOperations.add(val);
                }
                this.cleanerIdResetClear.clear();
            }

            if (!this.geometryManager.getHeapRemovals().isEmpty()) {
                var rem = this.geometryManager.getHeapRemovals();
                var iter = rem.intIterator();
                while (iter.hasNext()) {
                    results.geometryUpload.remove(iter.nextInt());
                }
                rem.clear();
            }

            if (!this.geometryManager.getUploads().isEmpty()) {
                var add = this.geometryManager.getUploads();
                var iter = add.int2ObjectEntrySet().fastIterator();
                while (iter.hasNext()) {
                    var val = iter.next();
                    results.geometryUpload.upload(val.getIntKey(), val.getValue());
                    val.getValue().free();
                }
                add.clear();
            }
        }

        {
            if (!this.geometryManager.getUpdateIds().isEmpty()) {
                var ids = this.geometryManager.getUpdateIds();
                var iter = ids.intIterator();
                while (iter.hasNext()) {
                    int val = iter.nextInt();
                    int scatterAddr = (val<<1)|(1<<31);

                    long ptrA = results.getScatterWritePtr(scatterAddr+0, 1);
                    long ptrB = results.getScatterWritePtr(scatterAddr+1, 0);

                    this.geometryManager.writeMetadataSplit(val, ptrA, ptrB);
                }
                ids.clear();
            }

            if (!this.manager.getNodeUpdates().isEmpty()) {
                var ids = this.manager.getNodeUpdates();
                var iter = ids.intIterator();
                while (iter.hasNext()) {
                    int val = iter.nextInt();
                    long ptr = results.getScatterWritePtr(val);
                    this.manager.writeNode(val, ptr);
                }
                ids.clear();
            }
        }

        results.geometrySectionCount = this.geometryManager.getSectionCount();
        results.usedGeometry = this.geometryManager.getGeometryUsedBytes();
        results.currentMaxNodeId = this.manager.getCurrentMaxNodeId();

        this.needsWaitForSync |= results.geometryUpload.currentElemCopyAmount*8L > 2L<<20;
        this.needsWaitForSync |= results.cleanerOperations.size() > 1024;
        this.needsWaitForSync |= results.scatterWriteLocationMap.size() > 4096;
        this.needsWaitForSync |= results.tlnDelta.size() > 10;

        if (!RESULT_HANDLE.compareAndSet(this, null, results)) {
            throw new IllegalArgumentException("Should always have null");
        }

        if (VERIFY_NODE_MANAGER) {
            this.manager.verifyIntegrity();
        }
    }

    private IntConsumer tlnAddCallback; private IntConsumer tlnRemoveCallback;
    public void tick(GlBuffer nodeBuffer, NodeCleaner cleaner) {
        this.tick((me.cortex.voxy.client.core.backend.IBufferHolder) nodeBuffer, cleaner);
    }

    public void tick(me.cortex.voxy.client.core.backend.IBufferHolder nodeBuffer, NodeCleaner cleaner) {
        if (this.uncaughtException != null) {
            throw new RuntimeException(this.uncaughtException);
        }
        var results = (SyncResults)RESULT_HANDLE.getAndSet(this, null);
        if (results == null) {
            return;
        }

        if (!results.tlnDelta.isEmpty()) {
            var iter = results.tlnDelta.intIterator();
            while (iter.hasNext()) {
                int val = iter.nextInt();
                if ((val&(1<<31))!=0) {
                    this.tlnAddCallback.accept(val&(-1>>>1));
                } else {
                    this.tlnRemoveCallback.accept(val);
                }
            }
        }

        {
            var store = (BasicSectionGeometryData)this.geometryData;

            store.setSectionCount(results.geometrySectionCount);

            var upload = results.geometryUpload;
            if (!upload.dataUploadPoints.isEmpty()) {
                ((BasicSectionGeometryData)this.geometryData).ensureAccessable(upload.maxElementAccess);
                TimingStatistics.A.start();

                int copies = upload.dataUploadPoints.size();
                int upCopies = UploadStream.alignUpAlloc(copies*16);
                int scratchSize = (int) upload.arena.getSize() * 8;
                int upScratchSize = UploadStream.alignUpAlloc(scratchSize);
                long ptr = UploadStream.INSTANCE.rawUploadAddress(upScratchSize + upCopies);
                UnsafeUtil.memcpy(upload.scratchHeaderBuffer.address, UploadStream.INSTANCE.getBaseAddress() + ptr, copies * 16L);
                UnsafeUtil.memcpy(upload.scratchDataBuffer.address, UploadStream.INSTANCE.getBaseAddress() + ptr + upCopies, scratchSize);
                UploadStream.INSTANCE.commit();

                if (!me.cortex.voxy.client.core.backend.vk.VkVoxyDevice.isVulkanActive()) {
                    this.multiMemcpy.bind();
                    glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0, UploadStream.INSTANCE.getRawBufferId(), ptr, upCopies);
                    glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 1, UploadStream.INSTANCE.getRawBufferId(), ptr+upCopies, upScratchSize);
                    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, ((BasicSectionGeometryData) this.geometryData).getGeometryBuffer().id());

                    if (copies > 500) {
                        Logger.warn("Large amount of copies, lag will probably happen: " + copies);
                    }

                    glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
                    glDispatchCompute(copies, 1, 1);
                    glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
                } else {
                    var geometryBuffer = ((BasicSectionGeometryData) this.geometryData).getGeometryBuffer();
                    long dataBase = ptr + upCopies;
                    for (int i = 0; i < copies; i++) {
                        long header = upload.scratchHeaderBuffer.address + i * 16L;
                        long src = Integer.toUnsignedLong(MemoryUtil.memGetInt(header)) * 8L;
                        long dst = Integer.toUnsignedLong(MemoryUtil.memGetInt(header + 4)) * 8L;
                        long size = Integer.toUnsignedLong(MemoryUtil.memGetInt(header + 8)) * 8L;
                        if (size != 0) {
                            UploadStream.INSTANCE.copyFromStream(geometryBuffer, dataBase + src, dst, size);
                        }
                    }
                    UploadStream.INSTANCE.commit();
                }

                TimingStatistics.A.stop();
            }
        }

        TimingStatistics.B.start();
        if (!results.scatterWriteLocationMap.isEmpty()) {
            int count = results.scatterWriteLocationMap.size();
            int chunks = (count+3)/4;
            int streamSize = chunks*80;
            long ptr = UploadStream.INSTANCE.rawUploadAddress(streamSize);
            MemoryUtil.memCopy(results.scatterWriteBuffer.address, UploadStream.INSTANCE.getBaseAddress() + ptr, streamSize);
            UploadStream.INSTANCE.commit();

            if (!me.cortex.voxy.client.core.backend.vk.VkVoxyDevice.isVulkanActive()) {
                this.scatterWrite.bind();
                glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0, UploadStream.INSTANCE.getRawBufferId(), ptr, UploadStream.alignUpAlloc(streamSize));
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, nodeBuffer.id());
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, ((BasicSectionGeometryData) this.geometryData).getMetadataBuffer().id());
                glUniform1ui(0, count);
                glMemoryBarrier(GL_UNIFORM_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);
                glDispatchCompute((count+127)/128, 1, 1);
                glMemoryBarrier(GL_UNIFORM_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);
            } else {
                var metadataBuffer = ((BasicSectionGeometryData) this.geometryData).getMetadataBuffer();
                long src = results.scatterWriteBuffer.address;
                for (int i = 0; i < count; i++) {
                    long chunk = (i >> 2) * 80L;
                    int inner = i & 3;
                    int location = MemoryUtil.memGetInt(src + chunk + inner * 4L);
                    var target = (location >>> 31) == 0 ? nodeBuffer : metadataBuffer;
                    long dst = Integer.toUnsignedLong(location & 0x7fffffff) * 16L;
                    UploadStream.INSTANCE.copyFromStream(target, ptr + chunk + 16 + inner * 16L, dst, 16);
                }
                UploadStream.INSTANCE.commit();
            }
        }
        TimingStatistics.B.stop();

        TimingStatistics.C.start();
        if (!results.cleanerOperations.isEmpty()) {
            cleaner.updateIds(results.cleanerOperations);
        }
        TimingStatistics.C.stop();

        this.currentMaxNodeId = results.currentMaxNodeId;
        this.usedGeometryAmount = results.usedGeometry;

        if (!RESULT_CACHE_1_HANDLE.compareAndSet(this, null, results)) {
            if (!RESULT_CACHE_2_HANDLE.compareAndSet(this, null, results)) {
                throw new IllegalStateException("Could not insert result into cache");
            }
        }
    }


    public void setTLNAddRemoveCallbacks(IntConsumer add, IntConsumer remove) {
        this.tlnAddCallback = add;
        this.tlnRemoveCallback = remove;
    }

    private int currentMaxNodeId = 0;
    public int getCurrentMaxNodeId() {
        return this.currentMaxNodeId;
    }

    private long usedGeometryAmount = 0;
    public long getUsedGeometryCapacity() {
        return this.usedGeometryAmount;
    }

    public long getGeometryCapacity() {
        return this.geometryCapacity;
    }


    private final ConcurrentLinkedDeque<MemoryBuffer> requestBatchQueue = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<WorldSection> childUpdateQueue = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<BuiltSection> geometryUpdateQueue = new ConcurrentLinkedDeque<>();

    private final ConcurrentLinkedDeque<MemoryBuffer> removeBatchQueue = new ConcurrentLinkedDeque<>();

    private final StampedLock tlnLock = new StampedLock();
    private final LongOpenHashSet tlnAdd = new LongOpenHashSet();
    private final LongOpenHashSet tlnRem = new LongOpenHashSet();

    private void addWork() {
        if (!this.running) {
            if (this.uncaughtException != null) {
                throw new RuntimeException(this.uncaughtException);
            }
            throw new IllegalStateException("Not running");
        }
        if (this.workCounter.getAndIncrement() == 0) {
            LockSupport.unpark(this.thread);
        }
    }

    public void submitRequestBatch(MemoryBuffer batch) {
        this.requestBatchQueue.add(batch);
        this.addWork();
    }

    private void submitChildChange(WorldSection section) {
        if (!this.running) {
            return;
        }
        section.acquire();
        this.childUpdateQueue.add(section);
        this.addWork();
    }

    private void submitGeometryResult(BuiltSection geometry) {
        if (!this.running) {
            geometry.free();
            return;
        }
        this.geometryUpdateQueue.add(geometry);
        this.addWork();
    }

    public void submitRemoveBatch(MemoryBuffer batch) {
        this.removeBatchQueue.add(batch);
        this.addWork();
    }

    public void addTopLevel(long section) {
        if (!this.running) throw new IllegalStateException("Not running");
        long stamp = this.tlnLock.writeLock();
        int state = 0;
        if (!this.tlnRem.remove(section)) {
            state += this.tlnAdd.add(section)?1:0;
        } else {
            state -= 1;
        }
        if (state != 0) {
            if (this.workCounter.getAndAdd(state) == 0) {
                LockSupport.unpark(this.thread);
            }
        }
        this.tlnLock.unlockWrite(stamp);
    }

    public void removeTopLevel(long section) {
        if (!this.running) throw new IllegalStateException("Not running");
        long stamp = this.tlnLock.writeLock();
        int state = 0;
        if (!this.tlnAdd.remove(section)) {
            state += this.tlnRem.add(section)?1:0;
        } else {
            state -= 1;
        }
        if (state != 0) {
            if (this.workCounter.getAndAdd(state) == 0) {
                LockSupport.unpark(this.thread);
            }
        }
        this.tlnLock.unlockWrite(stamp);
    }

    public void start() {
        this.thread.start();
    }

    public void stop() {
        if (!this.running) {
            throw new IllegalStateException();
        }
        this.running = false;
        LockSupport.unpark(this.thread);
        try {
            while (this.thread.isAlive()) {
                LockSupport.unpark(this.thread);
                this.thread.join(1000);
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        while (true) {
            var buffer = this.requestBatchQueue.poll();
            if (buffer == null) break;
            buffer.free();
        }

        while (true) {
            var buffer = this.removeBatchQueue.poll();
            if (buffer == null) break;
            buffer.free();
        }

        while (true) {
            var buffer = this.geometryUpdateQueue.poll();
            if (buffer == null) break;
            buffer.free();
        }

        while (true) {
            var section = this.childUpdateQueue.poll();
            if (section == null) break;
            section.release();
        }

        if (RESULT_HANDLE.get(this) != null) {
            var result = (SyncResults)RESULT_HANDLE.getAndSet(this, null);
            result.geometryUpload.free();
            result.scatterWriteBuffer.free();
        }

        if (RESULT_CACHE_1_HANDLE.get(this) != null) {
            var result = (SyncResults)RESULT_CACHE_1_HANDLE.getAndSet(this, null);
            result.geometryUpload.free();
            result.scatterWriteBuffer.free();
        }

        if (RESULT_CACHE_2_HANDLE.get(this) != null) {
            var result = (SyncResults)RESULT_CACHE_2_HANDLE.getAndSet(this, null);
            result.geometryUpload.free();
            result.scatterWriteBuffer.free();
        }

        this.scatterWrite.free();
        this.multiMemcpy.free();
        this.geometryCache.free();
    }

    public void addDebug(List<String> debug) {
        debug.add("UC/GC,#N: " + (this.getUsedGeometryCapacity()/(1<<20))+"/"+(this.getGeometryCapacity()/(1<<20)) + "," + (this.geometryData.getSectionCount()));
    }

    public boolean hasWork() {
        return this.workCounter.get()!=0 || RESULT_HANDLE.get(this) != null;
    }

    public void worldEvent(WorldSection section, int flags, int neighborMask) {
        this.geometryCache.clear(section.key);

        this.router.forwardEvent(section, flags);

        if (neighborMask != 0) {
            if ((neighborMask&0b000001)!=0) this.router.triggerRemesh(WorldEngine.getWorldSectionId(section.lvl, section.x, section.y-1, section.z));
            if ((neighborMask&0b000010)!=0) this.router.triggerRemesh(WorldEngine.getWorldSectionId(section.lvl, section.x, section.y+1, section.z));
            if ((neighborMask&0b000100)!=0) this.router.triggerRemesh(WorldEngine.getWorldSectionId(section.lvl, section.x-1, section.y, section.z));
            if ((neighborMask&0b001000)!=0) this.router.triggerRemesh(WorldEngine.getWorldSectionId(section.lvl, section.x+1, section.y, section.z));
            if ((neighborMask&0b010000)!=0) this.router.triggerRemesh(WorldEngine.getWorldSectionId(section.lvl, section.x, section.y, section.z-1));
            if ((neighborMask&0b100000)!=0) this.router.triggerRemesh(WorldEngine.getWorldSectionId(section.lvl, section.x, section.y, section.z+1));
        }
    }

    private static final class SyncResults {

        private int currentMaxNodeId;

        private final IntOpenHashSet tlnDelta = new IntOpenHashSet();

        private int geometrySectionCount;
        private long usedGeometry;
        private final ComputeMemoryCopy geometryUpload = new ComputeMemoryCopy();

        private MemoryBuffer scatterWriteBuffer = new MemoryBuffer(8192*2);
        private final Int2IntOpenHashMap scatterWriteLocationMap = new Int2IntOpenHashMap(1024);
        {this.scatterWriteLocationMap.defaultReturnValue(-1);}

        private final IntOpenHashSet cleanerOperations = new IntOpenHashSet();

        public void reset() {
            this.cleanerOperations.clear();
            this.scatterWriteLocationMap.clear();
            this.currentMaxNodeId = 0;
            this.tlnDelta.clear();
            this.geometrySectionCount = 0;
            this.usedGeometry = 0;
            this.geometryUpload.reset();
        }

        public long getScatterWritePtr(int location) {
            return this.getScatterWritePtr(location, 0);
        }

        public long getScatterWritePtr(int location, int ensureExtra) {
            int loc = this.scatterWriteLocationMap.get(location);
            if (loc == -1) {
                this.ensureScatterBufferCapacity(1+ensureExtra);
                int baseId = this.scatterWriteLocationMap.size();
                int chunkBase = (baseId/4)*5;
                int innerId   = baseId&3;
                MemoryUtil.memPutInt(this.scatterWriteBuffer.address + (chunkBase*16L) + (innerId*4L), location);
                int writeLocation = (chunkBase+1+innerId);
                this.scatterWriteLocationMap.put(location, writeLocation);
                return this.scatterWriteBuffer.address + (writeLocation*16L);
            } else {
                return this.scatterWriteBuffer.address + (16L*loc);
            }
        }

        private void ensureScatterBufferCapacity(int extra) {
            int requiredChunks = ((this.scatterWriteLocationMap.size()+extra)+3)/4;
            long requiredSize = requiredChunks*5L*16L;
            if (this.scatterWriteBuffer.size <= requiredSize) {
                long newSize = (long) ((this.scatterWriteBuffer.size*1.5) + extra*80L);
                newSize = ((newSize+79)/80)*80;

                Logger.info("Expanding scatter update buffer to " + newSize);

                var newBuffer = new MemoryBuffer(newSize);
                this.scatterWriteBuffer.cpyTo(newBuffer.address);
                this.scatterWriteBuffer.free();
                this.scatterWriteBuffer = newBuffer;
            }
        }
    }

    private static class ComputeMemoryCopy {
        public int currentElemCopyAmount;
        public int maxElementAccess;
        private MemoryBuffer scratchHeaderBuffer = new MemoryBuffer(1<<16);
        private MemoryBuffer scratchDataBuffer = new MemoryBuffer(1<<20);

        private final AllocationArena arena = new AllocationArena();
        private final Int2IntOpenHashMap dataUploadPoints = new Int2IntOpenHashMap();
        {this.dataUploadPoints.defaultReturnValue(-1);}


        public void remove(int point) {
            int header = this.dataUploadPoints.remove(point);
            if (header == -1) {
                return;
            }
            int size = MemoryUtil.memGetInt(this.scratchHeaderBuffer.address + header*16L + 8L);
            this.currentElemCopyAmount -= size;
            if (this.arena.free(MemoryUtil.memGetInt(this.scratchHeaderBuffer.address + header*16L)) != size) {
                throw new IllegalStateException("Freed memory not same size as expected");
            }
            if (MemoryUtil.memGetInt(this.scratchHeaderBuffer.address + header*16L + 4L) != point) {
                throw new IllegalStateException("Destination not the same as point");
            }

            if (header == this.dataUploadPoints.size()) {
                long A = this.scratchHeaderBuffer.address + header*16L;
                MemoryUtil.memPutLong(A, 0);
                MemoryUtil.memPutLong(A+8, 0);
                return;
            }

            int endingPoint = MemoryUtil.memGetInt(this.scratchHeaderBuffer.address + this.dataUploadPoints.size()*16L + 4);
            if (this.dataUploadPoints.get(endingPoint) != this.dataUploadPoints.size()) {
                throw new IllegalStateException("ending header not pointing at end point");
            }

            long A = this.scratchHeaderBuffer.address + this.dataUploadPoints.size()*16L;
            long B = this.scratchHeaderBuffer.address + header*16L;
            MemoryUtil.memPutLong(B, MemoryUtil.memGetLong(A)); MemoryUtil.memPutLong(A, 0);
            MemoryUtil.memPutLong(B+8, MemoryUtil.memGetLong(A+8)); MemoryUtil.memPutLong(A+8, 0);

            this.dataUploadPoints.put(endingPoint, header);
        }

        public void upload(int point, MemoryBuffer data) {
            if ((data.size%8)!=0) throw new IllegalStateException("Data must be of size multiple 8");
            int elemSize = (int) (data.size / 8);
            this.maxElementAccess = Math.max(this.maxElementAccess, point + elemSize);
            int header = this.dataUploadPoints.get(point);
            if (header != -1) {
                long headerPtr = this.scratchHeaderBuffer.address + header*16L;
                if (MemoryUtil.memGetInt(headerPtr+4L) != point) {
                    throw new IllegalStateException("Existing destination not the point");
                }
                int pSize = MemoryUtil.memGetInt(headerPtr+8L);
                if (pSize == elemSize) {
                    data.cpyTo(this.scratchDataBuffer.address+MemoryUtil.memGetInt(headerPtr)*8L);
                } else {
                    if (this.arena.free(MemoryUtil.memGetInt(headerPtr)) != pSize) {
                        throw new IllegalStateException("Freed allocation not size as expected");
                    }

                    this.currentElemCopyAmount -= pSize;
                    this.currentElemCopyAmount += elemSize;

                    int alloc = this.allocScratchDataPos(elemSize);
                    data.cpyTo(this.scratchDataBuffer.address+alloc*8L);

                    MemoryUtil.memPutInt(headerPtr, alloc);
                    MemoryUtil.memPutInt(headerPtr+8, elemSize);
                }
            } else {
                header = this.dataUploadPoints.size();
                this.dataUploadPoints.put(point, header);

                if (this.scratchHeaderBuffer.size<=header*16L) {
                    long newSize = Math.max(this.scratchHeaderBuffer.size*2, header*16L);
                    Logger.info("Resizing scratch header buffer to: " + newSize);
                    var newScratch = new MemoryBuffer(newSize);
                    this.scratchHeaderBuffer.cpyTo(newScratch.address);
                    this.scratchHeaderBuffer.free();
                    this.scratchHeaderBuffer = newScratch;
                }

                long headerPtr = this.scratchHeaderBuffer.address + header*16L;

                this.currentElemCopyAmount += elemSize;

                int alloc = this.allocScratchDataPos(elemSize);
                data.cpyTo(this.scratchDataBuffer.address+alloc*8L);

                MemoryUtil.memPutInt(headerPtr, alloc);
                MemoryUtil.memPutInt(headerPtr+4, point);
                MemoryUtil.memPutInt(headerPtr+8, elemSize);
            }
        }

        private int allocScratchDataPos(int size) {
            int pos = (int) this.arena.alloc(size);
            if (this.scratchDataBuffer.size <= (pos+size)*8L) {
                long newSize = Math.max(this.scratchDataBuffer.size*2, (pos+size)*8L);
                Logger.info("Resizing scratch data buffer to: " + newSize);
                var newScratch = new MemoryBuffer(newSize);
                this.scratchDataBuffer.cpyTo(newScratch.address);
                this.scratchDataBuffer.free();
                this.scratchDataBuffer = newScratch;
            }
            return pos;
        }

        public void reset() {
            this.maxElementAccess = 0;
            this.currentElemCopyAmount = 0;
            this.dataUploadPoints.clear();
            this.arena.reset();
        }

        public void free() {
            this.scratchHeaderBuffer.free(); this.scratchHeaderBuffer = null;
            this.scratchDataBuffer.free(); this.scratchDataBuffer = null;
        }
    }
}
