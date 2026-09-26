package com.routiqo.core.spot.infrastructure;

import com.routiqo.core.identity.application.AccountAgeReader;
import com.routiqo.core.identity.application.AccountWriteAuthority;
import com.routiqo.core.journey.application.ActiveJourneyReader;
import com.routiqo.core.journey.application.JourneyWriteAuthority;
import com.routiqo.core.moderation.application.ContributionRestrictionReader;
import com.routiqo.core.routing.domain.RouteRequest;
import com.routiqo.core.spot.application.SpotActivityService;
import com.routiqo.core.spot.application.SpotContributionConflict;
import com.routiqo.core.spot.application.SpotContributionForbidden;
import com.routiqo.core.spot.application.SpotContributionNotFound;
import com.routiqo.core.spot.application.SpotContributionService;
import com.routiqo.core.spot.application.SpotContributionService.PostCommand;
import com.routiqo.core.spot.application.SpotContributionService.SignalCommand;
import com.routiqo.core.spot.application.SpotContributionStore.VoteKind;
import com.routiqo.core.spot.application.SpotJourneyNotActive;
import com.routiqo.core.spot.application.SpotsRateLimited;
import com.routiqo.core.spot.domain.AliasWords;
import com.routiqo.core.spot.domain.ContributionLife;
import com.routiqo.core.spot.domain.Spot;
import com.routiqo.core.spot.domain.SpotActivity;
import com.routiqo.core.spot.domain.SpotCatalog;
import com.routiqo.core.spot.domain.SpotCategory;
import com.routiqo.core.spot.domain.SpotCorridor;
import com.routiqo.core.spot.domain.SpotKind;
import com.routiqo.core.spot.domain.SpotProvenance;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("persistence")
class SpotContributionPersistenceTest {
    private static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16-alpine");
    static { DATABASE.start(); }

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
    }

    /** A clock the test moves; the service samples it after its locks. */
    static final class MovableClock extends Clock {
        volatile Instant now;
        MovableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration by) { now = now.plus(by); }
    }

    private static final Instant START = Instant.parse("2026-11-05T01:00:00Z"); // 06:30 in Kolkata
    private static final UUID VERSION = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
    private static final UUID TOLL = UUID.fromString("00000000-0000-4000-8000-00000000a001");
    private static final UUID EATERY = UUID.fromString("00000000-0000-4000-8000-00000000a002");

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired JourneyWriteAuthority journeys;
    @Autowired AccountWriteAuthority accounts;
    @Autowired ActiveJourneyReader activeJourneys;
    @Autowired ContributionRestrictionReader restrictions;
    @Autowired AccountAgeReader ages;

    MovableClock clock;
    SpotContributionService service;
    SpotActivityService activity;
    AliasWords words;
    SpotCatalog catalog;

    private static Spot spot(UUID id, SpotKind kind, Set<SpotCategory> categories) {
        return new Spot(id, "Test " + kind.key(), "சோதனை", kind, new RouteRequest.Coordinate(79.9, 12.7),
                "chengalpattu", List.of("gst-trunk"), categories,
                new SpotProvenance("Curator", SpotProvenance.Source.OSM, LocalDate.of(2026, 10, 1)));
    }

    @BeforeEach void reset() throws Exception {
        jdbc.update("DELETE FROM routiqo_account");
        jdbc.update("DELETE FROM spot_signal_group");
        jdbc.update("DELETE FROM spot_vote");
        jdbc.update("DELETE FROM spot_highlight");
        clock = new MovableClock(START);
        catalog = new SpotCatalog(VERSION, List.of(new SpotCorridor("gst-trunk", "GST Road")), List.of(
                spot(TOLL, SpotKind.TOLL, Set.of(SpotCategory.TRAFFIC, SpotCategory.QUEUE)),
                spot(EATERY, SpotKind.EATERY, Set.of(SpotCategory.FOOD, SpotCategory.RESTROOM))));
        try (InputStream input = getClass().getResourceAsStream("/spot/alias-words-v1.txt")) {
            words = AliasWords.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        var random = new Random(11);
        service = new SpotContributionService(journeys, accounts, activeJourneys, restrictions, ages,
                new JdbcSpotContributionStore(jdbc), catalog, words, clock, random::nextInt);
        activity = new SpotActivityService((key, category, limit) -> true, activeJourneys, catalog, clock,
                new JdbcSpotActivityReader(jdbc));
    }

    record Traveller(UUID account, UUID journey) {}

    /** An account created long ago (or just now) with a journey started an hour before START. */
    Traveller traveller(boolean newAccount) {
        UUID account = UUID.randomUUID();
        jdbc.update("INSERT INTO routiqo_account (id, google_subject, created_at) VALUES (?, ?, ?)", account,
                "subject-" + account, Timestamp.from(newAccount ? START.minusSeconds(60) : START.minus(Duration.ofDays(30))));
        UUID journey = UUID.randomUUID();
        jdbc.update("INSERT INTO journey (id, owner_id, kind, status, started_at) VALUES (?, ?, 'TRIP', 'ACTIVE', ?)",
                journey, account, Timestamp.from(START.minus(Duration.ofHours(1))));
        return new Traveller(account, journey);
    }

    SignalCommand signal(Traveller t, UUID spot, String category, String value, Instant captured) {
        return new SignalCommand(UUID.randomUUID(), spot, category, value, captured, t.journey());
    }

    PostCommand post(Traveller t, UUID spot, String type, String text) {
        return new PostCommand(UUID.randomUUID(), spot, type, text, clock.instant(), t.journey());
    }

    int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    @Test void signalsReplayExactlyConflictOnChangeAndReplaceTheAuthorsPreviousSignal() {
        Traveller t = traveller(false);
        SignalCommand first = signal(t, TOLL, "traffic", "slow", START.minusSeconds(120));
        var receipt = service.submitSignal(t.account(), first);
        assertThat(receipt.status()).isEqualTo("active");
        assertThat(receipt.expiresAt()).isEqualTo(START.minusSeconds(120).plus(Duration.ofMinutes(60)));
        assertThat(service.submitSignal(t.account(), first)).isEqualTo(receipt);
        assertThat(count("spot_contribution_ledger")).isEqualTo(1);
        assertThatThrownBy(() -> service.submitSignal(t.account(), new SignalCommand(first.clientKey(), TOLL,
                "traffic", "moving", first.capturedAt(), t.journey()))).isInstanceOf(SpotContributionConflict.class);
        clock.advance(Duration.ofSeconds(61));
        var replaced = service.submitSignal(t.account(), signal(t, TOLL, "traffic", "moving", clock.instant()));
        assertThat(service.submitSignal(t.account(), first).status()).isEqualTo("replaced");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spot_signal WHERE state = 'ACTIVE'", Integer.class))
                .isEqualTo(1);
        assertThat(replaced.status()).isEqualTo("active");
        assertThatThrownBy(() -> service.submitSignal(t.account(), signal(t, TOLL, "food", "good", START)))
                .isInstanceOf(SpotContributionNotFound.class);
        assertThatThrownBy(() -> service.submitSignal(t.account(), signal(t, TOLL, "traffic", "busy", START)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.submitSignal(t.account(), signal(t, UUID.randomUUID(), "traffic", "slow", START)))
                .isInstanceOf(SpotContributionNotFound.class);
    }

    @Test void captureRulesAndTheJourneyActiveAtCaptureAreEnforcedOnServerTime() {
        Traveller t = traveller(false);
        assertThatThrownBy(() -> service.submitSignal(t.account(), signal(t, TOLL, "traffic", "slow",
                START.minus(Duration.ofMinutes(60))))).isInstanceOf(ContributionLife.ContributionTooOld.class);
        assertThatThrownBy(() -> service.submitSignal(t.account(), signal(t, TOLL, "traffic", "slow",
                START.plusSeconds(121)))).isInstanceOf(IllegalArgumentException.class);
        // Captured more than 30 minutes before the journey's server start: refused.
        Traveller late = traveller(false);
        jdbc.update("UPDATE journey SET started_at = ? WHERE id = ?", Timestamp.from(START.minus(Duration.ofMinutes(10))),
                late.journey());
        assertThatThrownBy(() -> service.submitSignal(late.account(), signal(late, TOLL, "traffic", "slow",
                START.minus(Duration.ofMinutes(41))))).isInstanceOf(SpotJourneyNotActive.class);
        assertThat(service.submitSignal(late.account(), signal(late, TOLL, "traffic", "slow",
                START.minus(Duration.ofMinutes(39)))).status()).isEqualTo("active");
        // A queued item captured before completion is accepted; one captured after it is not.
        jdbc.update("UPDATE journey SET status = 'COMPLETED', completed_at = ? WHERE id = ?",
                Timestamp.from(START.minusSeconds(30)), t.journey());
        assertThat(service.submitPost(t.account(), new PostCommand(UUID.randomUUID(), TOLL, "traffic",
                "Queue at lane 3", START.minusSeconds(60), t.journey())).status()).isEqualTo("active");
        assertThatThrownBy(() -> service.submitPost(t.account(), new PostCommand(UUID.randomUUID(), TOLL,
                "traffic", "Later", START, t.journey()))).isInstanceOf(SpotJourneyNotActive.class);
        Traveller other = traveller(false);
        assertThatThrownBy(() -> service.submitSignal(other.account(), signal(t, TOLL, "traffic", "slow", START)))
                .isInstanceOf(com.routiqo.core.journey.application.JourneyNotFound.class);
    }

    @Test void restrictedAccountsCannotContribute() {
        Traveller t = traveller(false);
        jdbc.update("INSERT INTO live_contribution_restriction (actor_id, revision, restricted) VALUES (?, 1, TRUE)",
                t.account());
        assertThatThrownBy(() -> service.submitSignal(t.account(), signal(t, TOLL, "traffic", "slow", START)))
                .isInstanceOf(SpotContributionForbidden.class);
        assertThat(count("spot_signal")).isZero();
    }

    @Test void postsGetOneAliasPerRoomAndOnlyTheAuthorCanDeleteThem() {
        Traveller t = traveller(false);
        Traveller other = traveller(false);
        var first = service.submitPost(t.account(), post(t, TOLL, "traffic", "Slow near the toll"));
        var second = service.submitPost(t.account(), post(t, TOLL, "traffic", "Now moving"));
        var elsewhere = service.submitPost(t.account(), post(t, EATERY, "place", "Good idli"));
        assertThat(first.alias()).isEqualTo(second.alias());
        assertThat(words.issued(first.alias())).isTrue();
        assertThat(words.issued(elsewhere.alias())).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spot_alias WHERE actor_id = ?", Integer.class,
                t.account())).isEqualTo(2);
        var theirs = service.submitPost(other.account(), post(other, TOLL, "traffic", "Agreed, slow"));
        assertThat(theirs.alias()).isNotEqualTo(first.alias());
        assertThatThrownBy(() -> service.deletePost(other.account(), first.ref()))
                .isInstanceOf(SpotContributionNotFound.class);
        assertThat(service.deletePost(t.account(), first.ref()).status()).isEqualTo("deleted");
        assertThat(service.deletePost(t.account(), first.ref()).status()).isEqualTo("deleted");
        assertThatThrownBy(() -> service.submitPost(t.account(), post(t, TOLL, "traffic", "Call 9876543210")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rateLimitsAreStricterForNewAccountsAndReplaysNeverCharge() {
        Traveller fresh = traveller(true);
        for (int index = 0; index < 2; index++)
            service.submitPost(fresh.account(), post(fresh, TOLL, "traffic", "Update " + index));
        assertThatThrownBy(() -> service.submitPost(fresh.account(), post(fresh, TOLL, "traffic", "One more")))
                .isInstanceOf(SpotsRateLimited.class);
        clock.advance(Duration.ofMinutes(11));
        for (int index = 0; index < 2; index++)
            service.submitPost(fresh.account(), post(fresh, TOLL, "traffic", "Later " + index));
        clock.advance(Duration.ofMinutes(11));
        service.submitPost(fresh.account(), post(fresh, TOLL, "traffic", "Fifth"));
        clock.advance(Duration.ofMinutes(11));
        assertThatThrownBy(() -> service.submitPost(fresh.account(), post(fresh, TOLL, "traffic", "Sixth today")))
                .isInstanceOf(SpotsRateLimited.class);

        Traveller settled = traveller(false);
        SignalCommand first = signal(settled, TOLL, "traffic", "slow", clock.instant());
        service.submitSignal(settled.account(), first);
        assertThatThrownBy(() -> service.submitSignal(settled.account(),
                signal(settled, TOLL, "traffic", "moving", clock.instant()))).isInstanceOf(SpotsRateLimited.class);
        service.submitSignal(settled.account(), first); // exact replay: no charge, no cooldown
        service.submitSignal(settled.account(), signal(settled, TOLL, "queue", "under_5", clock.instant()));
        // Rotate through four Spot/category slots so the 60 s cooldown never applies: 20 per hour total.
        String[][] slots = {{"traffic", "stopped"}, {"queue", "5_to_15"}, {"food", "good"}, {"restroom", "busy"}};
        for (int index = 0; index < 18; index++) {
            clock.advance(Duration.ofSeconds(61));
            String[] slot = slots[index % 4];
            UUID spot = index % 4 < 2 ? TOLL : EATERY;
            service.submitSignal(settled.account(), signal(settled, spot, slot[0], slot[1], clock.instant()));
        }
        clock.advance(Duration.ofSeconds(61));
        assertThatThrownBy(() -> service.submitSignal(settled.account(),
                signal(settled, EATERY, "restroom", "usable", clock.instant()))).isInstanceOf(SpotsRateLimited.class);
    }

    @Test void stillTrueExtendsAndTwoNonAuthorsExpireAPostWithoutRevival() {
        Traveller author = traveller(false);
        Traveller a = traveller(false);
        Traveller b = traveller(false);
        var post = service.submitPost(author.account(), post(author, TOLL, "traffic", "Accident in lane 1"));
        assertThatThrownBy(() -> service.vote(author.account(), post.ref(), VoteKind.STILL_TRUE))
                .isInstanceOf(SpotContributionForbidden.class);
        clock.advance(Duration.ofMinutes(80));
        var confirmed = service.vote(a.account(), post.ref(), VoteKind.STILL_TRUE);
        assertThat(confirmed.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(45)));
        assertThat(confirmed.stillTrue()).isEqualTo(1);
        var repeat = service.vote(a.account(), post.ref(), VoteKind.STILL_TRUE);
        assertThat(repeat.expiresAt()).isEqualTo(confirmed.expiresAt());
        service.vote(a.account(), post.ref(), VoteKind.NO_LONGER_TRUE);
        var gone = service.vote(b.account(), post.ref(), VoteKind.NO_LONGER_TRUE);
        assertThat(gone.status()).isEqualTo("expired");
        assertThatThrownBy(() -> service.vote(b.account(), post.ref(), VoteKind.STILL_TRUE))
                .isInstanceOf(SpotContributionNotFound.class);
    }

    @Test void summaryVotesExcludeAuthorsAndExpireTheWholeGroup() {
        Traveller author = traveller(false);
        Traveller second = traveller(false);
        Traveller c = traveller(false);
        Traveller d = traveller(false);
        service.submitSignal(author.account(), signal(author, TOLL, "traffic", "stopped", START));
        UUID group = jdbc.queryForObject("SELECT group_ref FROM spot_signal", UUID.class);
        assertThatThrownBy(() -> service.vote(author.account(), group, VoteKind.NO_LONGER_TRUE))
                .isInstanceOf(SpotContributionForbidden.class);
        service.submitSignal(second.account(), signal(second, TOLL, "traffic", "stopped", START));
        // Both authors may now vote, but authors' "No longer true" never counts toward the two.
        service.vote(author.account(), group, VoteKind.NO_LONGER_TRUE);
        assertThat(service.vote(second.account(), group, VoteKind.NO_LONGER_TRUE).status()).isEqualTo("active");
        assertThat(service.vote(c.account(), group, VoteKind.NO_LONGER_TRUE).status()).isEqualTo("active");
        assertThat(service.vote(d.account(), group, VoteKind.NO_LONGER_TRUE).status()).isEqualTo("expired");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spot_signal WHERE state = 'EXPIRED_EARLY'",
                Integer.class)).isEqualTo(2);
    }

    @Test void oneActiveSignalPerSlotUnderConcurrentWrites() throws Exception {
        Traveller t = traveller(false);
        List<Callable<Object>> writes = new ArrayList<>();
        for (int index = 0; index < 6; index++)
            writes.add(() -> {
                try {
                    return service.submitSignal(t.account(), signal(t, TOLL, "queue", "over_30", START));
                } catch (SpotsRateLimited limited) {
                    return limited;
                }
            });
        try (var pool = Executors.newFixedThreadPool(6)) {
            for (var result : pool.invokeAll(writes)) result.get();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spot_signal WHERE state = 'ACTIVE'", Integer.class))
                .isEqualTo(1);
        assertThat(count("spot_signal")).isEqualTo(1); // the 60 s cooldown refuses the rest
    }

    @Test void activityShowsUnattributedSummariesNewestPostsAndLiveFadingQuiet() {
        Traveller a = traveller(false);
        Traveller b = traveller(false);
        Traveller viewer = traveller(false);
        service.submitSignal(a.account(), signal(a, TOLL, "traffic", "slow", START));
        service.submitSignal(b.account(), signal(b, TOLL, "traffic", "slow", START));
        service.submitSignal(viewer.account(), signal(viewer, TOLL, "traffic", "moving", START));
        var mine = service.submitPost(viewer.account(), post(viewer, TOLL, "traffic", "Lane 2 is faster"));
        var read = activity.read(viewer.account(), List.of(TOLL, EATERY));
        SpotActivity.Entry toll = read.spots().getFirst();
        assertThat(toll.state()).isEqualTo(SpotActivity.State.LIVE);
        assertThat(toll.signals()).singleElement().satisfies(summary -> {
            assertThat(summary.topValue()).isEqualTo("slow");
            assertThat(summary.values()).containsExactly(new SpotActivity.ValueCount("slow", 2),
                    new SpotActivity.ValueCount("moving", 1));
        });
        assertThat(toll.posts()).singleElement().satisfies(post -> {
            assertThat(post.ref()).isEqualTo(mine.ref());
            assertThat(post.mine()).isTrue();
            assertThat(post.alias()).isEqualTo(mine.alias());
        });
        assertThat(read.spots().get(1).state()).isEqualTo(SpotActivity.State.QUIET);
        assertThat(activity.read(a.account(), List.of(TOLL)).spots().getFirst().posts().getFirst().mine()).isFalse();
        clock.advance(Duration.ofMinutes(31));
        assertThat(activity.read(viewer.account(), List.of(TOLL)).spots().getFirst().state())
                .isEqualTo(SpotActivity.State.FADING);
        clock.advance(Duration.ofMinutes(120));
        assertThat(activity.read(viewer.account(), List.of(TOLL)).spots().getFirst().state())
                .isEqualTo(SpotActivity.State.QUIET);
    }

    @Test void maintenancePromotesConfirmedPlaceTipsAndPurgesExpiredItems() {
        Traveller author = traveller(false);
        Traveller a = traveller(false);
        Traveller b = traveller(false);
        var tip = service.submitPost(author.account(), post(author, EATERY, "place", "Clean restrooms at the back"));
        PostCommand trafficCommand = post(author, TOLL, "traffic", "Slow");
        var traffic = service.submitPost(author.account(), trafficCommand);
        service.vote(a.account(), tip.ref(), VoteKind.STILL_TRUE);
        service.vote(b.account(), tip.ref(), VoteKind.STILL_TRUE);
        service.vote(a.account(), traffic.ref(), VoteKind.STILL_TRUE);
        service.vote(b.account(), traffic.ref(), VoteKind.STILL_TRUE);
        var maintenance = new JdbcSpotContributionMaintenance(jdbc, manager, clock);
        clock.advance(Duration.ofHours(37));
        assertThat(maintenance.promoteHighlights(100)).isEqualTo(1);
        assertThat(maintenance.promoteHighlights(100)).isZero();
        assertThat(jdbc.queryForObject("SELECT text FROM spot_highlight", String.class))
                .isEqualTo("Clean restrooms at the back");
        assertThat(activity.read(a.account(), List.of(EATERY)).spots().getFirst().highlights()).hasSize(1);
        clock.advance(Duration.ofHours(24));
        assertThat(maintenance.purgeItems(100)).isEqualTo(2);
        assertThat(count("spot_post")).isZero();
        // Keys go with their items, so a late replay is judged afresh: too old, never a dangling 503.
        assertThat(count("spot_contribution_key")).isZero();
        assertThatThrownBy(() -> service.submitPost(author.account(), trafficCommand))
                .isInstanceOf(ContributionLife.ContributionTooOld.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spot_vote WHERE item_ref IN (?, ?)", Integer.class,
                tip.ref(), traffic.ref())).isZero();
        assertThat(maintenance.purgeBookkeeping(100)).isPositive();
        assertThat(count("spot_alias")).isZero();
        assertThat(count("spot_contribution_ledger")).isZero();
        assertThat(count("spot_contribution_key")).isZero();
        clock.advance(Duration.ofDays(30));
        maintenance.purgeBookkeeping(100);
        assertThat(count("spot_highlight")).isZero();
    }

    @Test void aSameKindVoteFromBeforeTheCurrentSignalsCountsAgain() {
        Traveller author = traveller(false);
        Traveller voter = traveller(false);
        Traveller third = traveller(false);
        service.submitSignal(author.account(), signal(author, TOLL, "traffic", "slow", START));
        UUID group = jdbc.queryForObject("SELECT group_ref FROM spot_signal", UUID.class);
        service.vote(voter.account(), group, VoteKind.NO_LONGER_TRUE);
        // The next day, fresh signals with the same value: yesterday's vote is outside the window.
        clock.advance(Duration.ofHours(15));
        Traveller later = traveller(false);
        service.submitSignal(later.account(), signal(later, TOLL, "traffic", "slow", clock.instant()));
        service.vote(voter.account(), group, VoteKind.NO_LONGER_TRUE);
        var result = service.vote(third.account(), group, VoteKind.NO_LONGER_TRUE);
        assertThat(result.status()).isEqualTo("expired");
    }

    @Test void deletingAPostAlsoRemovesItsHighlight() {
        Traveller author = traveller(false);
        Traveller a = traveller(false);
        Traveller b = traveller(false);
        var tip = service.submitPost(author.account(), post(author, EATERY, "place", "Ask for the family room"));
        service.vote(a.account(), tip.ref(), VoteKind.STILL_TRUE);
        service.vote(b.account(), tip.ref(), VoteKind.STILL_TRUE);
        var maintenance = new JdbcSpotContributionMaintenance(jdbc, manager, clock);
        clock.advance(Duration.ofHours(37));
        assertThat(maintenance.promoteHighlights(100)).isEqualTo(1);
        assertThat(service.deletePost(author.account(), tip.ref()).status()).isEqualTo("deleted");
        assertThat(count("spot_highlight")).isZero();
        assertThat(activity.read(a.account(), List.of(EATERY)).spots().getFirst().highlights()).isEmpty();
    }

    @Test void highlightsKeepTheBestThreeAndNeverChurnOnLaterPasses() {
        Traveller author = traveller(false);
        List<Traveller> voters = List.of(traveller(false), traveller(false), traveller(false), traveller(false));
        for (int index = 0; index < 5; index++) {
            clock.advance(Duration.ofMinutes(11));
            var tip = service.submitPost(author.account(), post(author, EATERY, "place", "Tip " + index));
            for (int vote = 0; vote < 2 + (index % 3); vote++)
                service.vote(voters.get(vote).account(), tip.ref(), VoteKind.STILL_TRUE);
        }
        var maintenance = new JdbcSpotContributionMaintenance(jdbc, manager, clock);
        clock.advance(Duration.ofHours(37));
        maintenance.promoteHighlights(100);
        List<String> first = jdbc.queryForList("SELECT ref::text FROM spot_highlight ORDER BY ref", String.class);
        assertThat(first).hasSize(3);
        assertThat(maintenance.promoteHighlights(100)).isZero();
        assertThat(jdbc.queryForList("SELECT ref::text FROM spot_highlight ORDER BY ref", String.class))
                .isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT min(still_true) FROM spot_highlight", Integer.class)).isEqualTo(3);
    }

    @Test void concurrentReReportsAndSummaryVotesDoNotDeadlock() throws Exception {
        Traveller seed = traveller(false);
        service.submitSignal(seed.account(), signal(seed, TOLL, "traffic", "slow", START));
        UUID group = jdbc.queryForObject("SELECT group_ref FROM spot_signal", UUID.class);
        List<Traveller> reporters = new ArrayList<>();
        List<Traveller> voters = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            Traveller reporter = traveller(false);
            service.submitSignal(reporter.account(), signal(reporter, TOLL, "traffic", "slow", START));
            reporters.add(reporter);
            voters.add(traveller(false));
        }
        clock.advance(Duration.ofSeconds(61));
        List<Callable<Object>> work = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            Traveller reporter = reporters.get(index);
            Traveller voter = voters.get(index);
            work.add(() -> service.submitSignal(reporter.account(),
                    signal(reporter, TOLL, "traffic", "slow", clock.instant())));
            work.add(() -> service.vote(voter.account(), group, VoteKind.STILL_TRUE));
        }
        try (var pool = Executors.newFixedThreadPool(12)) {
            for (var result : pool.invokeAll(work)) result.get(); // any deadlock surfaces as a failure here
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spot_signal WHERE state = 'ACTIVE'", Integer.class))
                .isEqualTo(7);
    }

    @Test void concurrentFirstPostsInOneRoomGetDistinctAliases() throws Exception {
        var single = new AliasWords("t", names("Aa", 20), names("Bb", 20));
        var pinned = new SpotContributionService(journeys, accounts, activeJourneys, restrictions, ages,
                new JdbcSpotContributionStore(jdbc), catalog, single, clock,
                // Everyone wants the same pair; only the numbered fallback varies.
                bound -> bound == 98 ? java.util.concurrent.ThreadLocalRandom.current().nextInt(98) : 0);
        List<Traveller> travellers = new ArrayList<>();
        for (int index = 0; index < 4; index++) travellers.add(traveller(false));
        List<Callable<SpotContributionService.Receipt>> posts = new ArrayList<>();
        for (Traveller t : travellers)
            posts.add(() -> pinned.submitPost(t.account(), post(t, TOLL, "traffic", "Hello")));
        List<String> aliases = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(4)) {
            for (var result : pool.invokeAll(posts)) aliases.add(result.get().alias());
        }
        assertThat(aliases).doesNotHaveDuplicates().contains("Aaa Bba");
    }

    private static List<String> names(String prefix, int count) {
        var out = new ArrayList<String>();
        for (int index = 0; index < count; index++) out.add(prefix + (char) ('a' + index));
        return out;
    }

    @Test void deletingTheAccountRemovesEverythingItContributed() {
        Traveller t = traveller(false);
        Traveller other = traveller(false);
        var post = service.submitPost(t.account(), post(t, EATERY, "place", "Good coffee"));
        service.submitSignal(t.account(), signal(t, EATERY, "food", "good", START));
        service.vote(other.account(), post.ref(), VoteKind.STILL_TRUE);
        jdbc.update("DELETE FROM routiqo_account WHERE id = ?", t.account());
        for (String table : List.of("spot_post", "spot_signal", "spot_alias", "spot_contribution_key"))
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE actor_id = ?", Integer.class,
                    t.account())).as(table).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spot_contribution_ledger WHERE actor_id = ?",
                Integer.class, t.account())).isZero();
    }
}
