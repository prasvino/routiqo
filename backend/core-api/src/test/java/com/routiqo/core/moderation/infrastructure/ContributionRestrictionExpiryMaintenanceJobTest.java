package com.routiqo.core.moderation.infrastructure;

import com.routiqo.core.moderation.application.ContributionRestrictionAuditCleanup;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ContributionRestrictionExpiryMaintenanceJobTest {
    @Test
    void runsOneBoundedPassForEachCategory() {
        ContributionRestrictionAuditCleanup cleanup = mock(
                ContributionRestrictionAuditCleanup.class);
        doReturn(100).when(cleanup).deleteExpired(100);
        doReturn(100).when(cleanup).purgeExpiredDebits(100);
        new ContributionRestrictionExpiryMaintenanceJob(cleanup).runOnce();
        verify(cleanup, times(1)).deleteExpired(100);
        verify(cleanup, times(1)).purgeExpiredDebits(100);
    }

    @Test
    void oneCategoryFailureIsRedactedAndDoesNotBlockTheNextTick() {
        ContributionRestrictionAuditCleanup cleanup = mock(
                ContributionRestrictionAuditCleanup.class);
        doThrow(new IllegalStateException("private detail"))
                .doReturn(0)
                .when(cleanup).deleteExpired(100);
        doReturn(0)
                .doThrow(new IllegalStateException("other private detail"))
                .when(cleanup).purgeExpiredDebits(100);
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                ContributionRestrictionExpiryMaintenanceJob.class);
        var captured = new ch.qos.logback.core.read.ListAppender<
                ch.qos.logback.classic.spi.ILoggingEvent>();
        captured.start();
        logger.addAppender(captured);
        try {
            ContributionRestrictionExpiryMaintenanceJob job =
                    new ContributionRestrictionExpiryMaintenanceJob(cleanup);
            job.runOnce();
            job.runOnce();
            verify(cleanup, times(2)).deleteExpired(100);
            verify(cleanup, times(2)).purgeExpiredDebits(100);
            assertThat(captured.list).extracting(
                    ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .containsExactly("Moderation expiry maintenance failed: audits",
                            "Moderation expiry maintenance failed: debits")
                    .allSatisfy(message -> assertThat(message).doesNotContain("private detail"));
            assertThat(captured.list).allSatisfy(
                    event -> assertThat(event.getThrowableProxy()).isNull());
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
    }

    @Test
    void overlappingTickReturnsWithoutDrainingOrStartingAnotherPass() throws Exception {
        ContributionRestrictionAuditCleanup cleanup = mock(
                ContributionRestrictionAuditCleanup.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");
            return 0;
        }).when(cleanup).deleteExpired(100);
        ContributionRestrictionExpiryMaintenanceJob job =
                new ContributionRestrictionExpiryMaintenanceJob(cleanup);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(job::runOnce);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            job.runOnce();
            verify(cleanup, times(1)).deleteExpired(100);
            verify(cleanup, times(0)).purgeExpiredDebits(100);
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
        }
        verify(cleanup, times(1)).deleteExpired(100);
        verify(cleanup, times(1)).purgeExpiredDebits(100);
    }
}
