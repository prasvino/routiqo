package com.routiqo.core.spot.application;

import com.routiqo.core.identity.application.AccountWriteAuthority;
import com.routiqo.core.identity.application.AuthRateGate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Report a Spot post or signal summary (POSTS_AND_SIGNALS_SPEC, ADR 0064 mechanics, ADR 0072).
 * Receipt first: an exact replay of a request returns its original receipt, even after the quota
 * is spent or the item has expired. A summary is reported per incident (its current signals), so
 * a later incident on the same summary can be reported again. Nothing about a report is public.
 */
public final class SpotReportService {
    static final Set<String> REASONS = Set.of("false_alarm", "abuse", "spam", "personal_data", "unsafe");
    static final int DAILY_QUOTA = 10;
    static final Duration QUOTA_WINDOW = Duration.ofHours(24);
    static final String RATE_CATEGORY = "spot-report-account";
    /** Attempts of any outcome per minute, so failing probes cannot hammer the account lock. */
    static final int RATE_LIMIT = 20;

    public record ReportCommand(UUID requestId, String reason) {
        @Override public String toString() { return "SpotReportCommand[private]"; }
    }
    /** Minimized receipt: no item, reason, reporter or count is echoed. */
    public record ReportReceipt(Instant receivedAt, Instant receiptExpiresAt) {}

    private final AuthRateGate rates;
    private final AccountWriteAuthority accounts;
    private final SpotReportStore store;
    private final Clock clock;

    public SpotReportService(AuthRateGate rates, AccountWriteAuthority accounts, SpotReportStore store,
            Clock clock) {
        this.rates = Objects.requireNonNull(rates);
        this.accounts = Objects.requireNonNull(accounts);
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
    }

    public ReportReceipt report(UUID actor, UUID ref, ReportCommand command) {
        requireIds(actor, ref, command == null ? null : command.requestId());
        if (!REASONS.contains(command.reason())) throw new IllegalArgumentException("Invalid report reason");
        final boolean allowed;
        try {
            allowed = rates.allow(actor.toString(), RATE_CATEGORY, RATE_LIMIT);
        } catch (RuntimeException unavailable) {
            throw new SpotsUnavailable();
        }
        if (!allowed) throw new SpotsRateLimited();
        return accounts.withEnabledAccount(actor, () -> {
            var replay = store.findReport(actor, command.requestId());
            if (replay.isPresent()) {
                var stored = replay.get();
                if (!stored.itemRef().equals(ref) || !stored.reason().equals(command.reason()))
                    throw new SpotContributionConflict();
                return receipt(stored);
            }
            Instant now = clock.instant();
            Instant since = now.minus(QUOTA_WINDOW);
            if (store.countReports(actor, since) >= DAILY_QUOTA) {
                Instant oldest = store.oldestReport(actor, since).orElse(now);
                throw new SpotsRateLimited(
                        (Duration.between(now, oldest.plus(QUOTA_WINDOW)).toMillis() + 999) / 1000);
            }
            SpotReportStore.Reportable item = store.reportable(ref, now)
                    .orElseThrow(SpotContributionNotFound::new);
            if (item.authors().stream().allMatch(actor::equals)) throw new SpotContributionForbidden();
            if (store.reported(actor, ref, item.windowStart())) throw new SpotContributionConflict();
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
