package com.routiqo.core.moderation.infrastructure;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class TrafficGrantMaintenanceJob {
    private static final Logger LOG = LoggerFactory.getLogger(TrafficGrantMaintenanceJob.class);
    private final JdbcTrafficGrantCleanup cleanup;
    private final AtomicBoolean running = new AtomicBoolean();
    public TrafficGrantMaintenanceJob(JdbcTrafficGrantCleanup cleanup) { this.cleanup = cleanup; }
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void runOnce() {
        if (!running.compareAndSet(false, true)) return;
        try { cleanup.cleanup(100); }
        catch (RuntimeException unavailable) { LOG.warn("Traffic grant retention maintenance unavailable"); }
        finally { running.set(false); }
    }
}
