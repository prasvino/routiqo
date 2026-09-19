package com.routiqo.core.moderation.application;

import com.routiqo.core.moderation.application.AuditedContributionRestrictionService.Action;
import com.routiqo.core.moderation.application.AuditedContributionRestrictionService.Command;
import com.routiqo.core.moderation.application.AuditedContributionRestrictionService.Reason;
import com.routiqo.core.moderation.application.AuditedContributionRestrictionService.Receipt;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditedContributionRestrictionServiceTest {
    private static final UUID NIL = new UUID(0, 0);
    private static final Instant ISSUED = Instant.parse("2026-09-19T06:00:00.123456Z");

    @Test
    void commandRejectsInvalidIdentityRevisionAndReasonActionPairs() {
        UUID request = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        assertInvalid(() -> new Command(NIL, target, 0, Action.RESTRICT, Reason.HARASSMENT));
        assertInvalid(() -> new Command(request, NIL, 0, Action.RESTRICT, Reason.HARASSMENT));
        assertInvalid(() -> new Command(request, target, -1, Action.RESTRICT, Reason.HARASSMENT));
        assertInvalid(() -> new Command(request, target, 0, Action.RESTRICT,
                Reason.APPEAL_UPHELD));
        assertInvalid(() -> new Command(request, target, 0, Action.RESTORE,
                Reason.UNSAFE_CONTENT));
        assertInvalid(() -> new Command(request, target, 0, null, Reason.HARASSMENT));
    }

    @Test
    void receiptRequiresCanonicalSingleRevisionAndFixedDuration() {
        UUID request = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        Receipt receipt = new Receipt(request, target, 4, 5, true, Action.RESTRICT,
                Reason.SPAM_MANIPULATION, ISSUED, ISSUED.plus(Duration.ofDays(30)));
        assertThat(receipt.beforeRevision()).isEqualTo(4);
        assertInvalid(() -> new Receipt(request, target, 4, 6, true, Action.RESTRICT,
                Reason.SPAM_MANIPULATION, ISSUED, ISSUED.plus(Duration.ofDays(30))));
        assertInvalid(() -> new Receipt(request, target, Long.MAX_VALUE, Long.MAX_VALUE, true,
                Action.RESTRICT, Reason.SPAM_MANIPULATION, ISSUED,
                ISSUED.plus(Duration.ofDays(30))));
        assertInvalid(() -> new Receipt(request, target, 4, 5, false, Action.RESTRICT,
                Reason.SPAM_MANIPULATION, ISSUED, ISSUED.plus(Duration.ofDays(30))));
        assertInvalid(() -> new Receipt(request, target, 4, 5, true, Action.RESTRICT,
                Reason.SPAM_MANIPULATION, ISSUED,
                ISSUED.plus(Duration.ofDays(30)).minusNanos(1_000)));
        assertInvalid(() -> new Receipt(request, target, 4, 5, true, Action.RESTRICT,
                Reason.SPAM_MANIPULATION, ISSUED.plusNanos(1),
                ISSUED.plusNanos(1).plus(Duration.ofDays(30))));
        assertInvalid(() -> new Receipt(request, target, 4, 5, true, Action.RESTRICT,
                Reason.SPAM_MANIPULATION, Instant.MIN, Instant.MAX));
    }

    @Test
    void commandAndReceiptStringsDoNotExposeIdentifiersOrIntent() {
        UUID request = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        Command command = new Command(request, target, 9, Action.RESTORE,
                Reason.ERROR_CORRECTION);
        Receipt receipt = new Receipt(request, target, 9, 10, false, Action.RESTORE,
                Reason.ERROR_CORRECTION, ISSUED, ISSUED.plus(Duration.ofDays(30)));
        assertThat(command.toString()).isEqualTo("AuditedRestrictionCommand[private]")
                .doesNotContain(request.toString(), target.toString(), "RESTORE");
        assertThat(receipt.toString()).isEqualTo("AuditedRestrictionReceipt[private]")
                .doesNotContain(request.toString(), target.toString(), "RESTORE");
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Invalid audited restriction").hasNoCause();
    }
}
