package com.routiqo.core.spot.application;

import com.routiqo.core.spot.domain.ContributionLife;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Trusted storage participant for Spot contributions. Every write method runs inside the caller's
 * account-locked transaction (account row first, then items), so per-account counts are race-free.
 */
public interface SpotContributionStore {
    enum Kind { SIGNAL, POST }
    enum Action { SIGNAL, POST, VOTE }
    enum VoteKind { STILL_TRUE, NO_LONGER_TRUE }

    record KeyRecord(Kind kind, UUID ref, String fingerprint) {}
    record NewSignal(UUID ref, UUID actorId, UUID journeyId, UUID spotId, UUID catalogVersion,
            UUID groupRef, String category, String value, Instant capturedAt, Instant receivedAt,
            ContributionLife.Timing timing) {}
    record NewPost(UUID ref, UUID actorId, UUID journeyId, UUID spotId, UUID catalogVersion,
            String type, String text, LocalDate roomDay, String alias, Instant capturedAt,
            Instant receivedAt, ContributionLife.Timing timing) {
        @Override public String toString() { return "NewSpotPost[private]"; }
    }
    /** Stored item state as seen by its author: ACTIVE, SUPERSEDED, DELETED or EXPIRED_EARLY. */
    record ItemState(UUID ref, Kind kind, String state, Instant expiresAt, String alias) {}
    /** {@code hidden}: a moderator hid it (ADR 0075); only its author can still act on it (delete). */
    record LockedPost(UUID ref, UUID actorId, String type, String state, Instant effectiveCreated,
            Instant expiresAt, Instant maxExpiresAt, boolean hidden) {}
    record LockedSignal(UUID ref, UUID actorId, String category, Instant effectiveCreated,
            Instant expiresAt, Instant maxExpiresAt) {}

    Optional<KeyRecord> findKey(UUID actorId, UUID clientKey);
    void insertKey(UUID actorId, UUID clientKey, Kind kind, UUID ref, String fingerprint, Instant now);
    Optional<ItemState> item(UUID ref);

    int countCharges(UUID actorId, Action action, Instant since);
    boolean chargedFor(UUID actorId, Action action, UUID spotId, String category, Instant since);
    void charge(UUID actorId, Action action, UUID spotId, String category, Instant now);

    UUID groupRef(UUID spotId, String category, String value, UUID candidate);
    void supersedeActiveSignal(UUID actorId, UUID spotId, String category, Instant now);
    void insertSignal(NewSignal signal);

    Optional<String> alias(UUID spotId, LocalDate roomDay, UUID actorId);
    boolean aliasTaken(UUID spotId, LocalDate roomDay, String alias);
    void insertAlias(UUID spotId, LocalDate roomDay, UUID actorId, String alias, Instant now);
    void insertPost(NewPost post);

    Optional<LockedPost> lockPost(UUID ref);
    boolean lockGroup(UUID groupRef);
    List<LockedSignal> lockActiveSignals(UUID groupRef, Instant now);
    /** The account's current vote, ignoring votes cast before {@code since} (the item's vote window). */
    Optional<VoteKind> vote(UUID itemRef, UUID actorId, Instant since);
    void putVote(UUID itemRef, UUID actorId, VoteKind kind, Instant now);
    int countVotes(UUID itemRef, VoteKind kind, Instant since, List<UUID> excludedActors);
    void extendPost(UUID ref, Instant expiresAt);
    void extendSignal(UUID ref, Instant expiresAt);
    void endPost(UUID ref, String state, Instant now);
    void endSignal(UUID ref, Instant now);
    /** Removes any highlight made from this post (delete my post, and later moderation). */
    void deleteHighlightOf(UUID postRef);
    /** Serializes first posts in a room across accounts, so alias choice cannot race. */
    void lockRoom(UUID spotId, LocalDate roomDay);
}
