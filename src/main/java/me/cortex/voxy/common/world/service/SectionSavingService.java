package me.cortex.voxy.common.world.service;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;

import java.util.concurrent.ConcurrentLinkedDeque;

public class SectionSavingService {
    private static final int SOFT_MAX_QUEUE_SIZE = 5_000;

    private final Service service;
    private record SaveEntry(WorldEngine engine, WorldSection section) {}
    private final ConcurrentLinkedDeque<SaveEntry> saveQueue = new ConcurrentLinkedDeque<>();

    public SectionSavingService(ServiceManager sm) {
        this.service = sm.createServiceNoCleanup(() -> this::processJob, 100, "Section saving service");
    }

    private void processJob() {
        var task = this.saveQueue.pop();
        var section = task.section;
        section.assertNotFree();
        try {
            if (section.exchangeIsInSaveQueue(false)) {
                section.setNotDirty();
                task.engine.storage.saveSection(section);
            } else {
                section.setNotDirty();
            }
        } catch (Throwable e) {
            Logger.error("Voxy saver had an exception while executing please check logs and report error", e);
        } finally {
            section.release();
        }
    }

    public boolean enqueueSave(WorldEngine in, WorldSection section, boolean nonBlocking, boolean sectionAlreadyAcquired) {
        if (section.exchangeIsInSaveQueue(true)) {
            if (!sectionAlreadyAcquired) {
                section.acquire();
            }

            if ((!nonBlocking) && this.getTaskCount() > SOFT_MAX_QUEUE_SIZE) {
                Thread.yield();
                while (this.getTaskCount() > SOFT_MAX_QUEUE_SIZE && this.service.isLive()) {
                    if (!this.service.steal()) {
                        break;
                    }
                    this.processJob();
                }
            }

            this.saveQueue.add(new SaveEntry(in, section));
            this.service.execute();
            return true;
        }
        return false;
    }

    public void shutdown() {
        if (this.service.numJobs() != 0) {
            Logger.error("Voxy section saving still in progress, estimated " + this.service.numJobs() + " sections remaining.");
            this.service.blockTillEmpty();
        }
        this.service.shutdown();
        while (!this.saveQueue.isEmpty()) {
            this.processJob();
        }
    }

    public int getTaskCount() {
        return this.service.numJobs();
    }
}
