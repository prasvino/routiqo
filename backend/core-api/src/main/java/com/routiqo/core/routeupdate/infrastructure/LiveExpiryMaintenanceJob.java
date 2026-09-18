package com.routiqo.core.routeupdate.infrastructure;

import com.routiqo.core.routeupdate.application.LiveRouteContextExpiryMaintenance;
import com.routiqo.core.routeupdate.application.SignalStorageExpiryMaintenance;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs one bounded physical-expiry pass; logical expiry is enforced by the readers. */
public final class LiveExpiryMaintenanceJob {
    private static final Logger LOG = LoggerFactory.getLogger(LiveExpiryMaintenanceJob.class);
    private static final int BATCH_SIZE = 100;

    private final LiveRouteContextExpiryMaintenance contexts;
    private final SignalStorageExpiryMaintenance signals;
    private final AtomicBoolean running = new AtomicBoolean();

    public LiveExpiryMaintenanceJob(LiveRouteContextExpiryMaintenance contexts,
            SignalStorageExpiryMaintenance signals) {
        this.contexts = Objects.requireNonNull(contexts);
        this.signals = Objects.requireNonNull(signals);
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000,
            scheduler = "liveExpiryTaskScheduler")
    public void runOnce() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            try {
                contexts.purgeExpired(BATCH_SIZE);
            } catch (Exception failure) {
                LOG.warn("Live expiry maintenance failed: contexts");
            }
            try {
                signals.purgeExpiredGrants(BATCH_SIZE);
            } catch (Exception failure) {
                LOG.warn("Live expiry maintenance failed: grants");
            }
            try {
                signals.purgeExpiredReceipts(BATCH_SIZE);
            } catch (Exception failure) {
                LOG.warn("Live expiry maintenance failed: receipts");
            }
            try {
                signals.purgeExpiredAcceptances(BATCH_SIZE);
            } catch (Exception failure) {
                LOG.warn("Live expiry maintenance failed: acceptances");
            }
        } finally {
            running.set(false);
        }
    }
}
