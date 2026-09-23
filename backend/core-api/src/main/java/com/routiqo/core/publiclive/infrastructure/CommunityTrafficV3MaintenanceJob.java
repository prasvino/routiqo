package com.routiqo.core.publiclive.infrastructure;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class CommunityTrafficV3MaintenanceJob {
    private static final Logger LOG = LoggerFactory.getLogger(CommunityTrafficV3MaintenanceJob.class);
    private final JdbcCommunityTrafficV3Cleanup cleanup;
    private final AtomicBoolean running = new AtomicBoolean();

    public CommunityTrafficV3MaintenanceJob(JdbcCommunityTrafficV3Cleanup cleanup) {
        this.cleanup = cleanup;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000,
            scheduler = "communityTrafficV3MaintenanceScheduler")
    public void runOnce() {
        if (!running.compareAndSet(false, true)) return;
        try {
            cleanup.cleanup(100);
        } catch (RuntimeException unavailable) {
            LOG.warn("Community traffic retention maintenance unavailable");
        } finally {
            running.set(false);
        }
    }
}
