package com.routiqo.core.spot.application;

import com.routiqo.core.identity.application.AccountWriteAuthority;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Report a Spot post or signal summary (POSTS_AND_SIGNALS_SPEC, ADR 0064 mechanics, ADR 0072).
 * Receipt first: an exact replay of a request returns its original receipt, even after the quota
 * is spent or the item has expired. Nothing about the report is public.
 */
public final class SpotReportService {
    static final Set<String> REASONS = Set.of("false_alarm", "abuse", "spam", "personal_data", "unsafe");
    static final int DAILY_QUOTA = 10;

    public record ReportCommand(UUID requestId, String reason) {
        @Override public String toString() { return "SpotReportCommand[private]"; }
    }
    /** Minimized receipt: no item, reason, reporter or count is echoed. */
    public record ReportReceipt(Instant receivedAt, Instant receiptExpiresAt) {}

    private final AccountWriteAuthority accounts;
    private final SpotReportStore store;
    private final Clock clock;

    public SpotReportService(AccountWriteAuthority accounts, SpotReportStore store, Clock clock) {
        this.accounts = Objects.requireNonNull(accounts);
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
    }

    public ReportReceipt report(UUID actor, UUID ref, ReportCommand command) {
        requireIds(actor, ref, command == null ? null : command.requestId());
        if (!REASONS.contains(command.reason())) throw new IllegalArgumentException("Invalid report reason");
        return accounts.withEnabledAccount(actor, () -> {
            var replay = store.findReport(actor, command.requestId());
            if (replay.isPresent()) {
                var stored = replay.get();
                if (!stored.itemRef().equals(ref) || !stored.reason().equals(command.reason()))
                    throw new SpotContributionConflict();
                return receipt(stored);
            }
            Instant now = clock.instant();
            if (store.countReports(actor, now.minus(Duration.ofHours(24))) >= DAILY_QUOTA)
                throw new SpotsRateLimited();
            SpotReportStore.Reportable item = store.reportable(ref, now)
                    .orElseThrow(SpotContributionNotFound::new);
            if (item.authors().stream().allMatch(actor::equals)) throw new SpotContributionForbidden();
            if (store.reported(actor, ref)) throw new SpotContributionConflict();
            return receipt(store.insertReport(actor, command.requestId(), item, command.reason(), now));
        });
    }

    private static ReportReceipt receipt(SpotReportStore.StoredReport stored) {
        return new ReportReceipt(stored.createdAt(), stored.expiresAt());
    }

    private static void requireIds(UUID... ids) {
        for (UUID id : ids)
            if (id == null || (id.getMostSignificantBits() == 0 && id.getLeastSignificantBits() == 0))
                throw new IllegalArgumentException("Invalid identifier");
    }
}
