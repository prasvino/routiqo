package com.routiqo.core.spot.infrastructure;

import com.routiqo.core.identity.application.AccountAgeReader;
import com.routiqo.core.identity.application.AccountWriteAuthority;
import com.routiqo.core.journey.application.ActiveJourneyReader;
import com.routiqo.core.journey.application.JourneyWriteAuthority;
import com.routiqo.core.moderation.application.BlockedAccountsReader;
import com.routiqo.core.moderation.application.ContributionRestrictionReader;
import com.routiqo.core.moderation.application.DurableBlockPolicyService;
import com.routiqo.core.routing.domain.RouteRequest;
import com.routiqo.core.spot.application.SpotActivityService;
import com.routiqo.core.spot.application.SpotBlockService;
import com.routiqo.core.spot.application.SpotContributionConflict;
import com.routiqo.core.spot.application.SpotContributionForbidden;
import com.routiqo.core.spot.application.SpotContributionNotFound;
import com.routiqo.core.spot.application.SpotContributionService;
import com.routiqo.core.spot.application.SpotContributionService.PostCommand;
import com.routiqo.core.spot.application.SpotContributionService.SignalCommand;
import com.routiqo.core.spot.application.SpotContributionStore.VoteKind;
import com.routiqo.core.spot.application.SpotReportService;
import com.routiqo.core.spot.application.SpotReportService.ReportCommand;
import com.routiqo.core.spot.application.SpotsRateLimited;
import com.routiqo.core.spot.domain.AliasWords;
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
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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

/** Report and Block on Spot items (ADR 0072) against PostgreSQL. */
@SpringBootTest
@ActiveProfiles("persistence")
class SpotReportPersistenceTest {
    private static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16-alpine");
    static { DATABASE.start(); }

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
    }

    static final class MovableClock extends Clock {
        volatile Instant now;
        MovableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration by) { now = now.plus(by); }
    }

    private static final Instant START = Instant.parse("2026-11-05T01:00:00Z");
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
    @Autowired DurableBlockPolicyService blockPolicy;
    @Autowired BlockedAccountsReader blocked;

    MovableClock clock;
    SpotContributionService service;
    SpotReportService reports;
    SpotBlockService blocks;
    SpotActivityService activity;
    JdbcSpotContributionMaintenance maintenance;
    final AtomicBoolean blockRateOpen = new AtomicBoolean(true);

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
        jdbc.update("DELETE FROM spot_report_group");
        clock = new MovableClock(START);
        blockRateOpen.set(true);
        var catalog = new SpotCatalog(VERSION, List.of(new SpotCorridor("gst-trunk", "GST Road")), List.of(
                spot(TOLL, SpotKind.TOLL, Set.of(SpotCategory.TRAFFIC, SpotCategory.QUEUE)),
                spot(EATERY, SpotKind.EATERY, Set.of(SpotCategory.FOOD, SpotCategory.RESTROOM))));
        AliasWords words;
        try (InputStream input = getClass().getResourceAsStream("/spot/alias-words-v1.txt")) {
            words = AliasWords.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        var random = new Random(7);
        service = new SpotContributionService(journeys, accounts, activeJourneys, restrictions, ages,
                new JdbcSpotContributionStore(jdbc), catalog, words, clock, random::nextInt);
        var store = new JdbcSpotReportStore(jdbc);
        reports = new SpotReportService(accounts, store, clock);
        blocks = new SpotBlockService((key, category, limit) -> blockRateOpen.get(), store, blockPolicy);
        activity = new SpotActivityService((key, category, limit) -> true, activeJourneys, catalog, clock,
                new JdbcSpotActivityReader(jdbc), blocked);
        maintenance = new JdbcSpotContributionMaintenance(jdbc, manager, clock);
    }

    record Traveller(UUID account, UUID journey) {}

    Traveller traveller() {
        UUID account = UUID.randomUUID();
        jdbc.update("INSERT INTO routiqo_account (id, google_subject, created_at) VALUES (?, ?, ?)", account,
                "subject-" + account, Timestamp.from(START.minus(Duration.ofDays(30))));
        UUID journey = UUID.randomUUID();
        jdbc.update("INSERT INTO journey (id, owner_id, kind, status, started_at) VALUES (?, ?, 'TRIP', 'ACTIVE', ?)",
                journey, account, Timestamp.from(START.minus(Duration.ofHours(1))));
        return new Traveller(account, journey);
    }

    UUID post(Traveller t, UUID spot, String type, String text) {
        return service.submitPost(t.account(), new PostCommand(UUID.randomUUID(), spot, type, text, clock.instant(),
                t.journey())).ref();
    }

    UUID signal(Traveller t, String value) {
        service.submitSignal(t.account(), new SignalCommand(UUID.randomUUID(), TOLL, "traffic", value,
                clock.instant(), t.journey()));
        return jdbc.queryForObject("SELECT ref FROM spot_signal_group WHERE spot_id = ? AND value = ?",
                UUID.class, TOLL, value);
    }

    static ReportCommand report(String reason) { return new ReportCommand(UUID.randomUUID(), reason); }

    int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    int reasonCount(UUID item, String reason) {
        return jdbc.queryForObject("SELECT " + reason + " FROM spot_report_group WHERE item_ref = ?", Integer.class, item);
    }

    @Test void reportsAreReceiptFirstOncePerItemAndNeverOnYourOwnContent() {
        Traveller author = traveller();
        Traveller reporter = traveller();
        Traveller other = traveller();
        UUID ref = post(author, TOLL, "traffic", "Lane 3 blocked by a lorry");
        ReportCommand abuse = report("abuse");
        var receipt = reports.report(reporter.account(), ref, abuse);
        assertThat(receipt.receivedAt()).isEqualTo(START);
        assertThat(receipt.receiptExpiresAt()).isEqualTo(START.plus(Duration.ofDays(7)));
        clock.advance(Duration.ofMinutes(5));
        assertThat(reports.report(reporter.account(), ref, abuse)).isEqualTo(receipt);
        assertThatThrownBy(() -> reports.report(reporter.account(), ref, new ReportCommand(abuse.requestId(), "spam")))
                .isInstanceOf(SpotContributionConflict.class);
        assertThatThrownBy(() -> reports.report(reporter.account(), UUID.randomUUID(), abuse))
                .isInstanceOf(SpotContributionConflict.class);
        assertThatThrownBy(() -> reports.report(reporter.account(), ref, report("spam")))
                .isInstanceOf(SpotContributionConflict.class);
        reports.report(other.account(), ref, report("spam"));
        assertThat(reasonCount(ref, "abuse")).isEqualTo(1);
        assertThat(reasonCount(ref, "spam")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT item_kind FROM spot_report_group WHERE item_ref = ?", String.class, ref))
                .isEqualTo("POST");
        assertThat(jdbc.queryForObject("SELECT latest FROM spot_report_group WHERE item_ref = ?", Timestamp.class, ref)
                .toInstant()).isEqualTo(clock.instant());
        assertThatThrownBy(() -> reports.report(author.account(), ref, report("unsafe")))
                .isInstanceOf(SpotContributionForbidden.class);
        assertThatThrownBy(() -> reports.report(reporter.account(), UUID.randomUUID(), report("unsafe")))
                .isInstanceOf(SpotContributionNotFound.class);
        assertThatThrownBy(() -> reports.report(reporter.account(), ref, report("rude")))
                .isInstanceOf(IllegalArgumentException.class);

        UUID group = signal(author, "slow");
        assertThatThrownBy(() -> reports.report(author.account(), group, report("false_alarm")))
                .isInstanceOf(SpotContributionForbidden.class);
        reports.report(reporter.account(), group, report("false_alarm"));
        assertThat(jdbc.queryForObject("SELECT item_kind FROM spot_report_group WHERE item_ref = ?", String.class,
                group)).isEqualTo("SUMMARY");
        assertThat(reasonCount(group, "false_alarm")).isEqualTo(1);

        // Expired items cannot be reported; an exact replay still returns its receipt.
        clock.advance(Duration.ofHours(3));
        assertThatThrownBy(() -> reports.report(other.account(), group, report("false_alarm")))
                .isInstanceOf(SpotContributionNotFound.class);
        assertThat(reports.report(reporter.account(), ref, abuse)).isEqualTo(receipt);
        UUID deleted = post(author, EATERY, "place", "Tea stall open all night");
        service.deletePost(author.account(), deleted);
        assertThatThrownBy(() -> reports.report(reporter.account(), deleted, report("spam")))
                .isInstanceOf(SpotContributionNotFound.class);
    }

    @Test void tenReportsPerRollingDayThenTheQuotaRollsOver() {
        Traveller reporter = traveller();
        var refs = new java.util.ArrayList<UUID>();
        for (int index = 0; index < 11; index++) refs.add(post(traveller(), EATERY, "place", "Tip " + index));
        ReportCommand first = report("spam");
        reports.report(reporter.account(), refs.getFirst(), first);
        for (int index = 1; index < 10; index++) {
            clock.advance(Duration.ofMinutes(1));
            reports.report(reporter.account(), refs.get(index), report("spam"));
        }
        assertThatThrownBy(() -> reports.report(reporter.account(), refs.get(10), report("spam")))
                .isInstanceOf(SpotsRateLimited.class);
        assertThat(reports.report(reporter.account(), refs.getFirst(), first).receivedAt()).isEqualTo(START);
        clock.now = START.plus(Duration.ofHours(24)).plusSeconds(1);
        // Place posts are still current 24 hours on only if confirmed; post a fresh one to report.
        UUID fresh = post(traveller(), EATERY, "place", "Fresh tip");
        reports.report(reporter.account(), fresh, report("spam"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spot_report WHERE reporter_id = ?", Integer.class,
                reporter.account())).isEqualTo(11);
    }

    @Test void deletingTheReporterKeepsTheReporterFreeCountsAndTheQueueIsSeverityFirst() {
        Traveller author = traveller();
        Traveller reporter = traveller();
        UUID spam = post(author, EATERY, "place", "Rooms available, ask at counter");
        UUID unsafe = post(author, TOLL, "traffic", "Drive on the wrong side, it's faster");
        UUID falseAlarm = signal(author, "slow");
        reports.report(reporter.account(), spam, report("spam"));
        clock.advance(Duration.ofSeconds(1));
        reports.report(reporter.account(), falseAlarm, report("false_alarm"));
        clock.advance(Duration.ofSeconds(1));
        reports.report(reporter.account(), unsafe, report("unsafe"));
        assertThat(jdbc.queryForList("""
                SELECT item_ref FROM spot_report_group ORDER BY ((unsafe + abuse) > 0) DESC, latest_sequence
                """, UUID.class)).containsExactly(unsafe, spam, falseAlarm);
        jdbc.update("DELETE FROM routiqo_account WHERE id = ?", reporter.account());
        assertThat(count("spot_report")).isZero();
        assertThat(reasonCount(spam, "spam")).isEqualTo(1);
        assertThat(reasonCount(unsafe, "unsafe")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'spot_report_group' AND column_name LIKE '%reporter%'
                """, Integer.class)).isZero();
    }

    @Test void reportedItemsOutliveTheirPurgeUntilTheGroupExpiresAndAreNeverHighlighted() {
        Traveller author = traveller();
        Traveller a = traveller();
        Traveller b = traveller();
        UUID tip = post(author, EATERY, "place", "Clean restrooms at the back");
        UUID plain = post(author, EATERY, "place", "Parking behind the hall");
        service.vote(a.account(), tip, VoteKind.STILL_TRUE);
        service.vote(b.account(), tip, VoteKind.STILL_TRUE);
        UUID group = signal(author, "slow");
        reports.report(a.account(), tip, report("personal_data"));
        reports.report(a.account(), group, report("false_alarm"));
        // A signal in the same group after the report is not evidence and purges normally.
        clock.advance(Duration.ofMinutes(2));
        Traveller later = traveller();
        signal(later, "slow");

        clock.advance(Duration.ofHours(37));
        maintenance.promoteHighlights(100);
        assertThat(count("spot_highlight")).isZero();
        clock.advance(Duration.ofHours(24));
        maintenance.purgeItems(100);
        assertThat(jdbc.queryForList("SELECT ref FROM spot_post", UUID.class)).containsExactly(tip);
        assertThat(plain).isNotEqualTo(tip);
        assertThat(jdbc.queryForList("SELECT actor_id FROM spot_signal", UUID.class)).containsExactly(author.account());

        // Reporter rows go at 7 days; the reporter-free group at 30 days, then the evidence purges.
        clock.now = START.plus(Duration.ofDays(7)).plusSeconds(1);
        maintenance.purgeBookkeeping(100);
        assertThat(count("spot_report")).isZero();
        assertThat(count("spot_report_group")).isEqualTo(2);
        maintenance.purgeItems(100);
        assertThat(count("spot_post")).isEqualTo(1);
        clock.now = START.plus(Duration.ofDays(30)).plusSeconds(1);
        maintenance.purgeBookkeeping(100);
        assertThat(count("spot_report_group")).isZero();
        maintenance.purgeItems(100);
        assertThat(count("spot_post")).isZero();
        assertThat(count("spot_signal")).isZero();
    }

    @Test void blockingAnAuthorHidesTheirPostsAndVotesForTheBlockerOnly() {
        Traveller author = traveller();
        Traveller blocker = traveller();
        Traveller bystander = traveller();
        Traveller friend = traveller();
        UUID mine = post(friend, TOLL, "traffic", "Toll lane 2 is fastest");
        UUID theirs = post(author, TOLL, "traffic", "Buy my cashew packets at lane 4");
        service.vote(author.account(), mine, VoteKind.STILL_TRUE);
        UUID group = signal(author, "slow");

        blocks.blockAuthor(blocker.account(), theirs);
        long revision = jdbc.queryForObject("SELECT revision FROM live_block_edge WHERE blocker_id = ? AND target_id = ?",
                Long.class, blocker.account(), author.account());
        blocks.blockAuthor(blocker.account(), theirs);
        assertThat(jdbc.queryForObject("SELECT revision FROM live_block_edge WHERE blocker_id = ?", Long.class,
                blocker.account())).isEqualTo(revision);

        SpotActivity.Entry seen = activity.read(blocker.account(), List.of(TOLL)).spots().getFirst();
        assertThat(seen.posts()).extracting(SpotActivity.PostView::ref).containsExactly(mine);
        assertThat(seen.posts().getFirst().stillTrue()).isZero();
        assertThat(seen.signals()).extracting(SpotActivity.SignalSummary::ref).containsExactly(group);
        SpotActivity.Entry unaffected = activity.read(bystander.account(), List.of(TOLL)).spots().getFirst();
        assertThat(unaffected.posts()).hasSize(2);
        assertThat(unaffected.posts()).filteredOn(view -> view.ref().equals(mine))
                .extracting(SpotActivity.PostView::stillTrue).containsExactly(1);
        // The blocked author is not told and still reads normally.
        assertThat(activity.read(author.account(), List.of(TOLL)).spots().getFirst().posts()).hasSize(2);

        assertThatThrownBy(() -> blocks.blockAuthor(author.account(), theirs))
                .isInstanceOf(SpotContributionForbidden.class);
        assertThatThrownBy(() -> blocks.blockAuthor(blocker.account(), UUID.randomUUID()))
                .isInstanceOf(SpotContributionNotFound.class);
        assertThatThrownBy(() -> blocks.blockAuthor(blocker.account(), group))
                .isInstanceOf(SpotContributionNotFound.class);
        blockRateOpen.set(false);
        assertThatThrownBy(() -> blocks.blockAuthor(bystander.account(), theirs))
                .isInstanceOf(SpotsRateLimited.class);
    }

    @Test void blockCapacityIsAConflictAndTheBlockedSetIsBoundedByIt() {
        Traveller blocker = traveller();
        for (int index = 0; index < 100; index++) {
            UUID target = traveller().account();
            jdbc.update("INSERT INTO live_block_edge (blocker_id, target_id, revision, blocked) VALUES (?, ?, 1, TRUE)",
                    blocker.account(), target);
        }
        Traveller author = traveller();
        UUID ref = post(author, TOLL, "traffic", "Slow near the flyover");
        assertThatThrownBy(() -> blocks.blockAuthor(blocker.account(), ref))
                .isInstanceOf(SpotContributionConflict.class);
        assertThat(blocked.blockedBy(blocker.account())).hasSize(100).doesNotContain(author.account());
    }
}
