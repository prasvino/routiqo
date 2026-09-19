package com.routiqo.core.moderation.infrastructure;

import com.routiqo.core.moderation.application.ContributionRestrictionAuditCleanup;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs one bounded physical-expiry pass; logical expiry remains enforced on access. */
public final class ContributionRestrictionExpiryMaintenanceJob {
    private static final Logger LOG = LoggerFactory.getLogger(
            ContributionRestrictionExpiryMaintenanceJob.class);
    private static final int BATCH_SIZE = 100;

    private final ContributionRestrictionAuditCleanup cleanup;
    private final AtomicBoolean running = new AtomicBoolean();

    public ContributionRestrictionExpiryMaintenanceJob(
            ContributionRestrictionAuditCleanup cleanup) {
        this.cleanup = Objects.requireNonNull(cleanup);
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000,
            scheduler = "moderationExpiryTaskScheduler")
    public void runOnce() {
        if (!running.compareAndSet(false, true)) return;
        try {
            try {
                cleanup.deleteExpired(BATCH_SIZE);
            } catch (Exception failure) {
                LOG.warn("Moderation expiry maintenance failed: audits");
            }
            try {
                cleanup.purgeExpiredDebits(BATCH_SIZE);
            } catch (Exception failure) {
                LOG.warn("Moderation expiry maintenance failed: debits");
            }
        } finally {
            running.set(false);
        }
    }
}
