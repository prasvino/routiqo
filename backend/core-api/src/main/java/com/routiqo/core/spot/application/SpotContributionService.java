package com.routiqo.core.spot.application;

import com.routiqo.core.identity.application.AccountAgeReader;
import com.routiqo.core.identity.application.AccountWriteAuthority;
import com.routiqo.core.journey.application.ActiveJourneyReader;
import com.routiqo.core.journey.application.JourneyWriteAuthority;
import com.routiqo.core.journey.domain.Journey;
import com.routiqo.core.moderation.application.ContributionRestrictionReader;
import com.routiqo.core.moderation.domain.ContributorAssessment;
import com.routiqo.core.spot.application.SpotContributionStore.Action;
import com.routiqo.core.spot.application.SpotContributionStore.Kind;
import com.routiqo.core.spot.application.SpotContributionStore.VoteKind;
import com.routiqo.core.spot.domain.AliasWords;
import com.routiqo.core.spot.domain.ContributionLife;
import com.routiqo.core.spot.domain.PostText;
import com.routiqo.core.spot.domain.PostType;
import com.routiqo.core.spot.domain.SignalChoice;
import com.routiqo.core.spot.domain.Spot;
import com.routiqo.core.spot.domain.SpotCatalog;
import com.routiqo.core.spot.domain.SpotCategory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntUnaryOperator;

/**
 * Public signals, posts, votes and "delete my post" on Spots (POSTS_AND_SIGNALS_SPEC, ADR 0071).
 * Every write takes the account row first (then the journey row), samples server time once after
 * the locks, and charges its budget in the same transaction. Exact replays never charge.
 */
public final class SpotContributionService {
    static final ZoneId ROOM_ZONE = ZoneId.of("Asia/Kolkata");
    /** A journey started while offline gets its server start on arrival; allow captures shortly before. */
    static final Duration OFFLINE_START_TOLERANCE = Duration.ofMinutes(30);
    static final Duration NEW_ACCOUNT = Duration.ofHours(24);
    static final Duration SIGNAL_COOLDOWN = Duration.ofSeconds(60);

    public record SignalCommand(UUID clientKey, UUID spotId, String category, String value,
            Instant capturedAt, UUID journeyId) {
        @Override public String toString() { return "SpotSignalCommand[private]"; }
    }
    public record PostCommand(UUID clientKey, UUID spotId, String type, String text, Instant capturedAt,
            UUID journeyId) {
        @Override public String toString() { return "SpotPostCommand[private]"; }
    }
    /** What the author sees: `active`, `replaced`, `deleted` or `expired`, never another account. */
    public record Receipt(UUID ref, String status, Instant expiresAt, String alias) {
        @Override public String toString() { return "SpotReceipt[private]"; }
    }
    public record VoteResult(UUID ref, String status, Instant expiresAt, int stillTrue) {}

    private final JourneyWriteAuthority journeys;
    private final AccountWriteAuthority accounts;
    private final ActiveJourneyReader activeJourneys;
    private final ContributionRestrictionReader restrictions;
    private final AccountAgeReader ages;
    private final SpotContributionStore store;
    private final SpotCatalog catalog;
    private final Map<UUID, Spot> spots;
    private final AliasWords aliases;
    private final Clock clock;
    private final IntUnaryOperator random;

    public SpotContributionService(JourneyWriteAuthority journeys, AccountWriteAuthority accounts,
            ActiveJourneyReader activeJourneys, ContributionRestrictionReader restrictions,
            AccountAgeReader ages, SpotContributionStore store, SpotCatalog catalog, AliasWords aliases,
            Clock clock, IntUnaryOperator random) {
        this.journeys = Objects.requireNonNull(journeys);
        this.accounts = Objects.requireNonNull(accounts);
        this.activeJourneys = Objects.requireNonNull(activeJourneys);
        this.restrictions = Objects.requireNonNull(restrictions);
        this.ages = Objects.requireNonNull(ages);
        this.store = Objects.requireNonNull(store);
        this.catalog = Objects.requireNonNull(catalog);
        this.spots = Map.copyOf(catalog.byId());
        this.aliases = Objects.requireNonNull(aliases);
        this.clock = Objects.requireNonNull(clock);
        this.random = Objects.requireNonNull(random);
    }

    public Receipt submitSignal(UUID actor, SignalCommand command) {
        requireIds(actor, command.clientKey(), command.spotId(), command.journeyId());
        Spot spot = spot(command.spotId());
        SpotCategory category = SpotCategory.fromKey(command.category());
        if (!spot.categories().contains(category)) throw new SpotContributionNotFound();
        SignalChoice choice = SignalChoice.of(category, command.value());
        String fingerprint = fingerprint("SIGNAL", spot.id(), category.key(), choice.key(),
                command.capturedAt(), command.journeyId());
        return journeys.withOwnedJourney(actor, command.journeyId(), journey -> {
            Optional<Receipt> replay = replay(actor, command.clientKey(), Kind.SIGNAL, fingerprint);
            if (replay.isPresent()) return replay.get();
            requireUnrestricted(actor);
            Instant now = clock.instant();
            ContributionLife.Timing timing = ContributionLife.forSignal(category)
                    .timing(command.capturedAt(), now);
            requireActiveAt(journey, timing.effectiveCreated());
            boolean fresh = newAccount(actor, now);
            if (store.countCharges(actor, Action.SIGNAL, now.minus(Duration.ofHours(1))) >= (fresh ? 10 : 20)
                    || store.chargedFor(actor, Action.SIGNAL, spot.id(), category.key(),
                            now.minus(SIGNAL_COOLDOWN)))
                throw new SpotsRateLimited();
            UUID group = store.groupRef(spot.id(), category.key(), choice.key(), UUID.randomUUID());
            store.supersedeActiveSignal(actor, spot.id(), category.key(), now);
            UUID ref = UUID.randomUUID();
            store.insertSignal(new SpotContributionStore.NewSignal(ref, actor, journey.id(), spot.id(),
                    catalog.version(), group, category.key(), choice.key(), command.capturedAt(), now, timing));
            store.insertKey(actor, command.clientKey(), Kind.SIGNAL, ref, fingerprint, now);
            store.charge(actor, Action.SIGNAL, spot.id(), category.key(), now);
            return receipt(ref, now);
        });
    }

    public Receipt submitPost(UUID actor, PostCommand command) {
        requireIds(actor, command.clientKey(), command.spotId(), command.journeyId());
        Spot spot = spot(command.spotId());
        PostType type = PostType.fromKey(command.type());
        String text = PostText.normalize(command.text());
        String fingerprint = fingerprint("POST", spot.id(), type.key(), text, command.capturedAt(),
                command.journeyId());
        return journeys.withOwnedJourney(actor, command.journeyId(), journey -> {
            Optional<Receipt> replay = replay(actor, command.clientKey(), Kind.POST, fingerprint);
            if (replay.isPresent()) return replay.get();
            requireUnrestricted(actor);
            Instant now = clock.instant();
            ContributionLife.Timing timing = ContributionLife.forPost(type).timing(command.capturedAt(), now);
            requireActiveAt(journey, timing.effectiveCreated());
            boolean fresh = newAccount(actor, now);
            if (store.countCharges(actor, Action.POST, now.minus(Duration.ofMinutes(10))) >= (fresh ? 2 : 5)
                    || store.countCharges(actor, Action.POST, now.minus(Duration.ofHours(24))) >= (fresh ? 5 : 20))
                throw new SpotsRateLimited();
            LocalDate room = LocalDate.ofInstant(timing.effectiveCreated(), ROOM_ZONE);
            String alias = store.alias(spot.id(), room, actor).orElseGet(() -> {
                String picked = aliases.pick(random, candidate -> store.aliasTaken(spot.id(), room, candidate));
                store.insertAlias(spot.id(), room, actor, picked, now);
                return picked;
            });
            UUID ref = UUID.randomUUID();
            store.insertPost(new SpotContributionStore.NewPost(ref, actor, journey.id(), spot.id(),
                    catalog.version(), type.key(), text, room, alias, command.capturedAt(), now, timing));
            store.insertKey(actor, command.clientKey(), Kind.POST, ref, fingerprint, now);
            store.charge(actor, Action.POST, spot.id(), null, now);
            return receipt(ref, now);
        });
    }

    /** "Still true" or "No longer true" on a post or a signal summary; a repeat of the same vote is a no-op. */
    public VoteResult vote(UUID actor, UUID ref, VoteKind kind) {
        requireIds(actor, ref);
        Objects.requireNonNull(kind);
        if (!activeJourneys.hasActiveJourney(actor)) throw new SpotActivityNeedsActiveJourney();
        return accounts.withEnabledAccount(actor, () -> {
            requireUnrestricted(actor);
            Instant now = clock.instant();
            Optional<SpotContributionStore.LockedPost> post = store.lockPost(ref);
            if (post.isPresent()) return votePost(actor, post.get(), kind, now);
            if (!store.lockGroup(ref)) throw new SpotContributionNotFound();
            return voteGroup(actor, ref, kind, now);
        });
    }

    /** Delete my post: immediate and idempotent; other accounts' posts look like unknown refs. */
    public Receipt deletePost(UUID actor, UUID ref) {
        requireIds(actor, ref);
        return accounts.withEnabledAccount(actor, () -> {
            SpotContributionStore.LockedPost post = store.lockPost(ref)
                    .filter(found -> found.actorId().equals(actor))
                    .orElseThrow(SpotContributionNotFound::new);
            Instant now = clock.instant();
            if ("ACTIVE".equals(post.state())) store.endPost(ref, "DELETED", now);
            return receipt(ref, now);
        });
    }

    private VoteResult votePost(UUID actor, SpotContributionStore.LockedPost post, VoteKind kind, Instant now) {
        if (!"ACTIVE".equals(post.state()) || !post.expiresAt().isAfter(now))
            throw new SpotContributionNotFound();
        if (post.actorId().equals(actor)) throw new SpotContributionForbidden();
        Instant expires = post.expiresAt();
        String status = "active";
        if (store.vote(post.ref(), actor).orElse(null) != kind) {
            chargeVote(actor, now);
            store.putVote(post.ref(), actor, kind, now);
            if (kind == VoteKind.STILL_TRUE) {
                expires = ContributionLife.forPost(PostType.fromKey(post.type()))
                        .stillTrue(post.expiresAt(), now, post.effectiveCreated());
                if (!expires.equals(post.expiresAt())) store.extendPost(post.ref(), expires);
            } else if (store.countVotes(post.ref(), VoteKind.NO_LONGER_TRUE, post.effectiveCreated(),
                    List.of(post.actorId())) >= 2) {
                store.endPost(post.ref(), "EXPIRED_EARLY", now);
                expires = now;
                status = "expired";
            }
        }
        return new VoteResult(post.ref(), status, expires,
                store.countVotes(post.ref(), VoteKind.STILL_TRUE, post.effectiveCreated(), List.of()));
    }

    private VoteResult voteGroup(UUID actor, UUID group, VoteKind kind, Instant now) {
        List<SpotContributionStore.LockedSignal> signals = store.lockActiveSignals(group, now);
        if (signals.isEmpty()) throw new SpotContributionNotFound();
        List<UUID> authors = signals.stream().map(SpotContributionStore.LockedSignal::actorId).distinct().toList();
        if (authors.size() == 1 && authors.getFirst().equals(actor)) throw new SpotContributionForbidden();
        // Votes count only while the group's current signals exist, so old votes never carry over.
        Instant window = signals.stream().map(SpotContributionStore.LockedSignal::effectiveCreated)
                .min(Instant::compareTo).orElseThrow();
        String status = "active";
        if (store.vote(group, actor).orElse(null) != kind) {
            chargeVote(actor, now);
            store.putVote(group, actor, kind, now);
            if (kind == VoteKind.STILL_TRUE) {
                for (var signal : signals) {
                    Instant extended = ContributionLife.forSignal(SpotCategory.fromKey(signal.category()))
                            .stillTrue(signal.expiresAt(), now, signal.effectiveCreated());
                    if (!extended.equals(signal.expiresAt())) store.extendSignal(signal.ref(), extended);
                }
            } else if (store.countVotes(group, VoteKind.NO_LONGER_TRUE, window, authors) >= 2) {
                for (var signal : signals) store.endSignal(signal.ref(), now);
                status = "expired";
            }
        }
        Instant expires = "expired".equals(status) ? now
                : store.lockActiveSignals(group, now).stream().map(SpotContributionStore.LockedSignal::expiresAt)
                        .max(Instant::compareTo).orElse(now);
        return new VoteResult(group, status, expires,
                store.countVotes(group, VoteKind.STILL_TRUE, window, List.of()));
    }

    private void chargeVote(UUID actor, Instant now) {
        boolean fresh = newAccount(actor, now);
        if (store.countCharges(actor, Action.VOTE, now.minus(Duration.ofHours(1))) >= (fresh ? 30 : 60))
            throw new SpotsRateLimited();
        store.charge(actor, Action.VOTE, null, null, now);
    }

    private Optional<Receipt> replay(UUID actor, UUID clientKey, Kind kind, String fingerprint) {
        return store.findKey(actor, clientKey).map(key -> {
            if (key.kind() != kind || !key.fingerprint().equals(fingerprint))
                throw new SpotContributionConflict();
            return receipt(key.ref(), clock.instant());
        });
    }

    private Receipt receipt(UUID ref, Instant now) {
        SpotContributionStore.ItemState item = store.item(ref).orElseThrow(SpotsUnavailable::new);
        String status = switch (item.state()) {
            case "SUPERSEDED" -> "replaced";
            case "DELETED" -> "deleted";
            case "EXPIRED_EARLY" -> "expired";
            default -> item.expiresAt().isAfter(now) ? "active" : "expired";
        };
        return new Receipt(item.ref(), status, item.expiresAt(), item.alias());
    }

    private void requireUnrestricted(UUID actor) {
        ContributorAssessment assessment = restrictions.read(actor);
        if (assessment == null || !actor.equals(assessment.actorId())
                || assessment.state() == ContributorAssessment.State.SUSPENDED)
            throw new SpotContributionForbidden();
    }

    private static void requireActiveAt(Journey journey, Instant at) {
        boolean started = !at.isBefore(journey.startedAt().minus(OFFLINE_START_TOLERANCE));
        boolean open = journey.completedAt() == null || at.isBefore(journey.completedAt());
        if (!started || !open) throw new SpotJourneyNotActive();
    }

    private boolean newAccount(UUID actor, Instant now) {
        return ages.createdAt(actor).map(created -> created.isAfter(now.minus(NEW_ACCOUNT))).orElse(true);
    }

    private Spot spot(UUID id) {
        Spot spot = spots.get(id);
        if (spot == null) throw new SpotContributionNotFound();
        return spot;
    }

    private static void requireIds(UUID... ids) {
        for (UUID id : ids)
            if (id == null || (id.getMostSignificantBits() == 0 && id.getLeastSignificantBits() == 0))
                throw new IllegalArgumentException("Invalid identifier");
    }

    static String fingerprint(Object... parts) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (Object part : parts) {
                byte[] bytes = String.valueOf(part).getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) ':');
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }
}
