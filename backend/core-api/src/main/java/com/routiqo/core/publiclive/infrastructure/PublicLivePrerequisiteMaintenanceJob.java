package com.routiqo.core.publiclive.infrastructure;

import com.routiqo.core.verification.infrastructure.VerificationAuditCleanup;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Bounded physical retention passes. Logical expiry is enforced by readers. */
public final class PublicLivePrerequisiteMaintenanceJob {
    private static final Logger LOG = LoggerFactory.getLogger(PublicLivePrerequisiteMaintenanceJob.class);
    private final JdbcPublicSignalIntentCleanup intents;
    private final VerificationAuditCleanup audits;
    private final AtomicBoolean running = new AtomicBoolean();

    public PublicLivePrerequisiteMaintenanceJob(JdbcPublicSignalIntentCleanup intents,
            VerificationAuditCleanup audits) {
        this.intents = Objects.requireNonNull(intents);
        this.audits = Objects.requireNonNull(audits);
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000,
            scheduler = "publicLivePrerequisiteTaskScheduler")
    public void runOnce() {
        if (!running.compareAndSet(false, true)) return;
        try {
            try {
                intents.deleteExpired();
            } catch (Exception failure) {
                LOG.warn("Public LIVE prerequisite maintenance failed: intents");
            }
            try {
                audits.deleteExpiredBatch();
            } catch (Exception failure) {
                LOG.warn("Public LIVE prerequisite maintenance failed: verification audit");
            }
        } finally {
            running.set(false);
        }
    }
}
