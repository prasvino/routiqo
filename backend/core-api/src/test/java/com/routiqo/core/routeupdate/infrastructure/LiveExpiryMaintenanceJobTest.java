package com.routiqo.core.routeupdate.infrastructure;

import com.routiqo.core.routeupdate.application.LiveRouteContextExpiryMaintenance;
import com.routiqo.core.routeupdate.application.SignalStorageExpiryMaintenance;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class LiveExpiryMaintenanceJobTest {
    @Test
    void attemptsOneFixedBatchPerCategoryAndRecoversAfterFailures(CapturedOutput output) {
        AtomicBoolean fail = new AtomicBoolean(true);
        AtomicInteger contexts = new AtomicInteger();
        AtomicInteger grants = new AtomicInteger();
        AtomicInteger receipts = new AtomicInteger();
        AtomicInteger acceptances = new AtomicInteger();
        String privateValue = "private-actor-id-and-sql";
        LiveRouteContextExpiryMaintenance contextMaintenance = limit -> {
            assertThat(limit).isEqualTo(100);
            contexts.incrementAndGet();
            if (fail.get()) throw new IllegalStateException(privateValue);
            return 100;
        };
        SignalStorageExpiryMaintenance signalMaintenance = new SignalStorageExpiryMaintenance() {
            @Override public int purgeExpiredGrants(int limit) {
                assertThat(limit).isEqualTo(100);
                grants.incrementAndGet();
                if (fail.get()) throw new IllegalStateException(privateValue);
                return 100;
            }

            @Override public int purgeExpiredReceipts(int limit) {
                assertThat(limit).isEqualTo(100);
                receipts.incrementAndGet();
                if (fail.get()) throw new IllegalStateException(privateValue);
                return 100;
            }

            @Override public int purgeExpiredAcceptances(int limit) {
                assertThat(limit).isEqualTo(100);
                acceptances.incrementAndGet();
                if (fail.get()) throw new IllegalStateException(privateValue);
                return 100;
            }
        };
        LiveExpiryMaintenanceJob job = new LiveExpiryMaintenanceJob(
                contextMaintenance, signalMaintenance);

        job.runOnce();
        assertThat(contexts).hasValue(1);
        assertThat(grants).hasValue(1);
        assertThat(receipts).hasValue(1);
        assertThat(acceptances).hasValue(1);
        assertThat(output.getAll()).contains("Live expiry maintenance failed: contexts")
                .contains("Live expiry maintenance failed: grants")
                .contains("Live expiry maintenance failed: receipts")
                .contains("Live expiry maintenance failed: acceptances")
                .doesNotContain(privateValue, "IllegalStateException");

        fail.set(false);
        job.runOnce();
        assertThat(contexts).hasValue(2);
        assertThat(grants).hasValue(2);
        assertThat(receipts).hasValue(2);
        assertThat(acceptances).hasValue(2);
    }

    @Test
    void duplicateConcurrentEntryDoesNotStartAnotherTick() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger contexts = new AtomicInteger();
        AtomicInteger grants = new AtomicInteger();
        AtomicInteger receipts = new AtomicInteger();
        AtomicInteger acceptances = new AtomicInteger();
        LiveExpiryMaintenanceJob job = new LiveExpiryMaintenanceJob(limit -> {
            assertThat(limit).isEqualTo(100);
            contexts.incrementAndGet();
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted");
            }
            return 0;
        }, new SignalStorageExpiryMaintenance() {
            @Override public int purgeExpiredGrants(int limit) {
                assertThat(limit).isEqualTo(100);
                grants.incrementAndGet();
                return 0;
            }

            @Override public int purgeExpiredReceipts(int limit) {
                assertThat(limit).isEqualTo(100);
                receipts.incrementAndGet();
                return 0;
            }

            @Override public int purgeExpiredAcceptances(int limit) {
                assertThat(limit).isEqualTo(100);
                acceptances.incrementAndGet();
                return 0;
            }
        });

        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(job::runOnce);
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                job.runOnce();
                assertThat(contexts).hasValue(1);
                assertThat(grants).hasValue(0);
                assertThat(receipts).hasValue(0);
                assertThat(acceptances).hasValue(0);
            } finally {
                release.countDown();
            }
            first.get(5, TimeUnit.SECONDS);
            assertThat(grants).hasValue(1);
            assertThat(receipts).hasValue(1);
            assertThat(acceptances).hasValue(1);
            job.runOnce();
            assertThat(contexts).hasValue(2);
            assertThat(grants).hasValue(2);
            assertThat(receipts).hasValue(2);
            assertThat(acceptances).hasValue(2);
        }
    }
}
