package com.routiqo.core.publiclive.infrastructure;

import com.routiqo.core.verification.infrastructure.VerificationAuditCleanup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(OutputCaptureExtension.class)
class PublicLivePrerequisiteMaintenanceJobTest {
    @Test
    void failureIsRedactedAndOtherCategoryStillRuns(CapturedOutput output) {
        var intents = mock(JdbcPublicSignalIntentCleanup.class);
        var audits = mock(VerificationAuditCleanup.class);
        doThrow(new IllegalStateException("private-person-reference"))
                .doReturn(0).when(intents).deleteExpired();
        var job = new PublicLivePrerequisiteMaintenanceJob(intents, audits);

        job.runOnce();
        job.runOnce();

        verify(intents, times(2)).deleteExpired();
        verify(audits, times(2)).deleteExpiredBatch();
        assertThat(output.getAll())
                .contains("Public LIVE prerequisite maintenance failed: intents")
                .doesNotContain("private-person-reference");
    }
}
