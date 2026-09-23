package com.routiqo.core.publiclive.infrastructure;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Bounded staging worker. Both publisher and cleanup are default off. */
public final class CommunityTrafficV3Job {
    private static final Logger LOG = LoggerFactory.getLogger(CommunityTrafficV3Job.class);
    private final JdbcCommunityTrafficPublisherV3 publisher;
    private final AtomicBoolean running = new AtomicBoolean();

    public CommunityTrafficV3Job(JdbcCommunityTrafficPublisherV3 publisher) {
        this.publisher = publisher;
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 15_000,
            scheduler = "communityTrafficV3TaskScheduler")
    public void runOnce() {
        if (!running.compareAndSet(false, true)) return;
        try {
            try { publisher.publish(100); }
            catch (RuntimeException unavailable) { LOG.warn("Community traffic publication unavailable"); }
        } finally {
            running.set(false);
        }
    }
}
