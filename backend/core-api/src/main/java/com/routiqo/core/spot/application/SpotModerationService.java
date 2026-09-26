package com.routiqo.core.spot.application;

import com.routiqo.core.identity.application.AccountWriteAuthority;
import com.routiqo.core.moderation.application.ModerationAccountFacts;
import com.routiqo.core.moderation.application.OperatorGrantAuthority;
import com.routiqo.core.spot.domain.Spot;
import com.routiqo.core.spot.domain.SpotCatalog;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The Spots moderator queue and decisions (PILOT_MODERATION_SPEC.md, ADR 0075). Every call runs under
 * the operator's enabled-account lock, checks the operator's current grant after locking the item and
 * its report group, and re-checks the admin session last. Responses carry no reporter, author or
 * account identifier, and no position.
 */
public final class SpotModerationService {
    static final int PAGE = 20;
    static final Set<String> HIDE_REASONS = Set.of("abuse", "spam", "false_alarm", "personal_data", "unsafe");
    static final Set<String> RESTORE_REASONS = Set.of("not_upheld", "error_correction");
    static final Set<String> CLEAR_REASONS = Set.of("false_alarm", "spam");
    static final Set<String> DISMISS_REASONS = Set.of("not_upheld");

    public enum Action { DISMISS, HIDE, RESTORE, CLEAR_SIGNALS }

    public record Queue(List<QueueItem> items, String nextCursor) {
        public Queue { items = List.copyOf(items); }
    }

    /** One report group. Post fields or summary fields are null for the other kind. */
    public record QueueItem(UUID reportRef, String kind, boolean urgent, String spotName, String spotNameTa,
            String text, String postType, String alias, String category, String value, Instant capturedAt,
            Instant expiresAt, String state, Map<String, Integer> reports, int stillTrue, int noLongerTrue,
            Instant openSince) {
        public QueueItem { reports = Map.copyOf(reports); }
        @Override public String toString() { return "SpotQueueItem[private]"; }
    }

    public record Decision(String status, boolean replayed) {}

    /**
     * One author behind a reported item. {@code accountRef} is an opaque 30-minute reference for this
     * operator only; there is never an account ID, e-mail, name or Google subject.
     */
    public record Author(String accountRef, long accountAgeDays, int completedJourneys, boolean restricted,
            long restrictionRevision, int notUpheldReports, boolean repeatedNotUpheld) {
        @Override public String toString() { return "SpotAuthor[private]"; }
    }

    public record AuthorLookup(List<Author> authors, boolean replayed) {
        public AuthorLookup { authors = List.copyOf(authors); }
    }

    static final int REPEATED_NOT_UPHELD = 3;
    static final Duration NOT_UPHELD_WINDOW = Duration.ofDays(30);

    private final AccountWriteAuthority accounts;
    private final SpotModerationStore store;
    private final OperatorGrantAuthority grants;
    private final ModerationAccountFacts facts;
    private final Map<UUID, Spot> spots;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public SpotModerationService(AccountWriteAuthority accounts, SpotModerationStore store,
            OperatorGrantAuthority grants, ModerationAccountFacts facts, SpotCatalog catalog, Clock clock) {
        this.accounts = accounts;
        this.store = store;
        this.grants = grants;
        this.facts = facts;
        this.spots = catalog.byId();
        this.clock = clock;
    }

    /**
     * Audited author lookup (spots_alias_lookup, a reason required). An exact retry issues fresh
     * references without a second audit row; a changed retry conflicts.
     */
    public AuthorLookup lookup(UUID operator, UUID requestId, UUID reportRef, String reason, Runnable sessionRecheck) {
        if (operator == null || invalid(requestId) || invalid(reportRef) || !HIDE_REASONS.contains(reason))
            throw new IllegalArgumentException("Invalid lookup");
        var intended = new SpotModerationStore.StoredAction("LOOKUP", reportRef, reason,
                fingerprint("LOOKUP", reportRef, reason));
        return accounts.withEnabledAccount(operator, () -> {
            Optional<SpotModerationStore.StoredAction> stored = store.action(operator, requestId);
            if (stored.isPresent() && !stored.get().fingerprint().equals(intended.fingerprint()))
                throw new SpotContributionConflict();
            SpotModerationStore.Group group = store.group(reportRef).orElseThrow(SpotContributionNotFound::new);
            grants.requireCurrent(operator, "spots_alias_lookup");
            sessionRecheck.run();
            Instant now = clock.instant();
            List<UUID> authors = store.authors(group, now);
            if (authors.isEmpty()) throw new SpotContributionNotFound(); // Evidence gone.
            var result = new ArrayList<Author>();
            for (UUID account : authors) {
                var context = facts.read(account);
                int notUpheld = store.notUpheldReports(account, now.minus(NOT_UPHELD_WINDOW));
                String token = token();
                store.issueAccountRef(operator, account, sha256(token), now);
                result.add(new Author(token, context.accountAgeDays(), context.completedJourneys(),
                        context.restricted(), context.restrictionRevision(), notUpheld,
                        notUpheld >= REPEATED_NOT_UPHELD));
            }
            if (stored.isEmpty()) store.recordAction(operator, requestId, intended, now);
            return new AuthorLookup(result, stored.isPresent());
        });
    }

    /**
     * Resolves this operator's unexpired account reference for a restriction (spots_restrict). The
     * account never leaves the server.
     */
    public UUID accountForRestriction(UUID operator, String accountRef, Runnable sessionRecheck) {
        if (operator == null || accountRef == null || !accountRef.matches("[A-Za-z0-9_-]{43}"))
            throw new SpotContributionNotFound();
        return accounts.withEnabledAccount(operator, () -> {
            grants.requireCurrent(operator, "spots_restrict");
            sessionRecheck.run();
            return store.accountRef(operator, sha256(accountRef), clock.instant())
                    .orElseThrow(SpotContributionNotFound::new);
        });
    }

    private String token() {
        byte[] value = new byte[32];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** A page of open report groups, urgent first. Items whose evidence is gone close on the way. */
    public Queue queue(UUID operator, String cursor, Runnable sessionRecheck) {
        Optional<SpotModerationStore.Cursor> after = decodeCursor(cursor);
        return accounts.withEnabledAccount(operator, () -> {
            grants.requireCurrent(operator, "spots_review");
            sessionRecheck.run();
            Instant now = clock.instant();
            List<SpotModerationStore.Group> groups = store.openGroups(after, PAGE);
            var items = new ArrayList<QueueItem>();
            for (var group : groups) {
                Optional<QueueItem> item = item(group, now);
                if (item.isPresent()) items.add(item.get());
                else store.close(group.ref(), group.latestSequence(), "CLOSED_EVIDENCE_UNAVAILABLE", now);
            }
            store.recordRead(operator, items.size(), now);
            String next = groups.size() == PAGE ? encodeCursor(groups.getLast()) : null;
            return new Queue(items, next);
        });
    }

    public Decision decide(UUID operator, UUID requestId, UUID reportRef, Action action, String reason,
            Runnable sessionRecheck) {
        if (operator == null || invalid(requestId) || invalid(reportRef) || action == null
                || !reasons(action).contains(reason)) throw new IllegalArgumentException("Invalid decision");
        var intended = new SpotModerationStore.StoredAction(action.name(), reportRef, reason,
                fingerprint(action.name(), reportRef, reason));
        return accounts.withEnabledAccount(operator, () -> {
            Optional<SpotModerationStore.StoredAction> stored = store.action(operator, requestId);
            if (stored.isPresent()) {
                if (!stored.get().fingerprint().equals(intended.fingerprint())) throw new SpotContributionConflict();
                return new Decision(status(action), true);
            }
            Instant now = clock.instant();
            SpotModerationStore.Group peek = store.group(reportRef).orElseThrow(SpotContributionNotFound::new);
            // Lock order: item, then report group, then grant.
            SpotModerationStore.PostItem post = null;
            SpotModerationStore.SummaryItem summary = null;
            if (peek.kind() == SpotModerationStore.Kind.POST) post = store.lockPost(peek.itemRef(), now).orElse(null);
            else summary = store.lockSummary(peek, now).orElse(null);
            SpotModerationStore.Group group = store.lockGroup(reportRef).orElseThrow(SpotContributionNotFound::new);
            grants.requireCurrent(operator, action == Action.DISMISS ? "spots_review" : "spots_hide");
            sessionRecheck.run();
            if (post == null && summary == null) throw new SpotContributionNotFound(); // Evidence gone.
            List<UUID> evidence = post != null ? List.of(group.itemRef()) : summary.signals();
            long ruled = group.latestSequence();
            switch (action) {
                case DISMISS -> {
                    if (!group.open()) throw new SpotContributionConflict();
                    store.ruleNotUpheld(group, evidence, group.closedThrough() == null ? 0 : group.closedThrough(), now);
                    store.close(reportRef, ruled, "DISMISSED", now);
                }
                case HIDE -> {
                    boolean visible = post != null ? !post.hidden() && !"DELETED".equals(post.state())
                            : summary.active() > 0;
                    if (!group.open() || !visible) throw new SpotContributionConflict();
                    if (post != null) store.hidePost(group.itemRef(), now);
                    else store.hideSignals(summary.signals(), now);
                    store.ruleUpheld(evidence);
                    store.close(reportRef, ruled, "HIDDEN", now);
                }
                case RESTORE -> {
                    boolean hidden = post != null ? post.hidden() : summary.hidden() > 0;
                    if (!hidden) throw new SpotContributionConflict();
                    if (post != null) store.unhidePost(group.itemRef());
                    else store.unhideSignals(summary.signals());
                    store.ruleNotUpheld(group, evidence,
                            group.notUpheldThrough() == null ? 0 : group.notUpheldThrough(), now);
                    store.close(reportRef, ruled, "RESTORED", now);
                }
                case CLEAR_SIGNALS -> {
                    if (summary == null || !group.open()) throw new SpotContributionConflict();
                    store.clearSignals(group.spotId(), summary.category(), now);
                    store.ruleUpheld(evidence);
                    store.close(reportRef, ruled, "CLEARED", now);
                }
            }
            store.recordAction(operator, requestId, intended, now);
            return new Decision(status(action), false);
        });
    }

    private Optional<QueueItem> item(SpotModerationStore.Group group, Instant now) {
        Spot spot = spots.get(group.spotId());
        String name = spot == null ? null : spot.name();
        String nameTa = spot == null ? null : spot.nameTa();
        var counts = group.counts();
        var reports = new LinkedHashMap<String, Integer>();
        reports.put("unsafe", counts.unsafe());
        reports.put("abuse", counts.abuse());
        reports.put("personal_data", counts.personalData());
        reports.put("false_alarm", counts.falseAlarm());
        reports.put("spam", counts.spam());
        SpotModerationStore.Votes votes = store.votes(group.itemRef());
        if (group.kind() == SpotModerationStore.Kind.POST) {
            return store.post(group.itemRef(), now).map(post -> new QueueItem(group.ref(), "post",
                    counts.urgent(), name, nameTa, post.text(), post.type(), post.alias(), null, null,
                    post.captured(), post.expires(), postState(post, now), reports, votes.stillTrue(),
                    votes.noLongerTrue(), group.openSince()));
        }
        return store.summary(group, now).map(summary -> new QueueItem(group.ref(), "summary",
                counts.urgent(), name, nameTa, null, null, null, summary.category(), summary.value(),
                summary.captured(), summary.expires(),
                summary.hidden() > 0 ? "hidden" : summary.active() > 0 ? "active" : "expired", reports,
                votes.stillTrue(), votes.noLongerTrue(), group.openSince()));
    }

    private static String postState(SpotModerationStore.PostItem post, Instant now) {
        if ("DELETED".equals(post.state())) return "deleted";
        if (post.hidden()) return "hidden";
        if (!"ACTIVE".equals(post.state()) || !post.expires().isAfter(now)) return "expired";
        return "active";
    }

    private static Set<String> reasons(Action action) {
        return switch (action) {
            case DISMISS -> DISMISS_REASONS;
            case HIDE -> HIDE_REASONS;
            case RESTORE -> RESTORE_REASONS;
            case CLEAR_SIGNALS -> CLEAR_REASONS;
        };
    }

    private static String status(Action action) {
        return switch (action) {
            case DISMISS -> "dismissed";
            case HIDE -> "hidden";
            case RESTORE -> "restored";
            case CLEAR_SIGNALS -> "cleared";
        };
    }

    static String encodeCursor(SpotModerationStore.Group group) {
        String raw = "1|" + (group.counts().urgent() ? "u" : "n") + "|" + group.latestSequence() + "|" + group.ref();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }

    static Optional<SpotModerationStore.Cursor> decodeCursor(String cursor) {
        if (cursor == null) return Optional.empty();
        try {
            if (cursor.length() > 96) throw new IllegalArgumentException();
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
            String[] parts = raw.split("\\|", -1);
            if (parts.length != 4 || !parts[0].equals("1") || !(parts[1].equals("u") || parts[1].equals("n"))
                    || !parts[2].matches("[1-9][0-9]{0,17}")) throw new IllegalArgumentException();
            UUID ref = UUID.fromString(parts[3]);
            if (!ref.toString().equals(parts[3])) throw new IllegalArgumentException();
            return Optional.of(new SpotModerationStore.Cursor(parts[1].equals("u"), Long.parseLong(parts[2]), ref));
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("Invalid cursor");
        }
    }

    private static String fingerprint(String action, UUID ref, String reason) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((action + "|" + ref + "|" + reason).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static boolean invalid(UUID value) { return value == null || new UUID(0, 0).equals(value); }
}
