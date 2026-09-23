package com.routiqo.core.publiclive.infrastructure;

import com.routiqo.core.identity.infrastructure.JdbcAccountWriteAuthority;
import com.routiqo.core.journey.infrastructure.JdbcJourneyStore;
import com.routiqo.core.moderation.application.ContributionRestrictionReader;
import com.routiqo.core.moderation.domain.ContributorAssessment;
import com.routiqo.core.privacy.application.PresenceConsentParticipant;
import com.routiqo.core.privacy.domain.PresenceConsent;
import com.routiqo.core.publiclive.application.FrozenPublicShareService;
import com.routiqo.core.routeupdate.application.LiveRouteContextParticipant;
import com.routiqo.core.routeupdate.application.SignalStorageStore;
import com.routiqo.core.routeupdate.domain.*;
import com.routiqo.core.routing.domain.RouteRequest;
import com.routiqo.core.verification.application.VerifiedContributorReader;
import com.routiqo.core.verification.infrastructure.JdbcVerifiedContributorAuthority;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Exercises the real account/journey transaction and the reviewer-deletion FK cascade. */
@SpringBootTest
@ActiveProfiles("persistence")
class FrozenPublicShareAuthorityRaceTest {
    private static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16-alpine");
    static { DATABASE.start(); }

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;

    @Test void reviewerDeletionWaitsForFrozenShareCommitThenCannotRefundClaim() throws Exception {
        Fixture f = fixture();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var deletionStarted = new CountDownLatch(1);
        var realVerification = new JdbcVerifiedContributorAuthority(jdbc);
        VerifiedContributorReader pausingVerification = new VerifiedContributorReader() {
            @Override public Optional<VerifiedContributor> current(UUID account, Instant now) {
                return realVerification.current(account, now);
            }
            @Override public Optional<VerifiedContributor> currentForFrozenShare(UUID account, Instant now) {
                var result = realVerification.currentForFrozenShare(account, now);
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Share release timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                return result;
            }
        };
        FrozenPublicShareService service = service(f, pausingVerification);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var share = executor.submit(() -> service.share(f.actor, f.journey,
                    f.command, f.request));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var deletion = executor.submit(() -> {
                deletionStarted.countDown();
                return jdbc.update("DELETE FROM routiqo_account WHERE id = ?", f.reviewer);
            });
            assertThat(deletionStarted.await(5, TimeUnit.SECONDS)).isTrue();
            // The FK cascade must wait on the verification row held by Share.
            assertThatThrownBy(() -> deletion.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();
            assertThat(share.get().publicKey()).contains("TRAFFIC_SLOW");
            assertThat(deletion.get()).isEqualTo(1);
        } finally {
            release.countDown();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM live_verified_contributor WHERE account_id = ?",
                Integer.class, f.actor)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public_live_person_claim WHERE pilot_id = ?",
                Integer.class, f.pilot)).isEqualTo(1);
        assertThatThrownBy(() -> service(f, realVerification).share(f.actor, f.journey,
                UUID.randomUUID(), UUID.randomUUID())).isInstanceOf(SecurityException.class);
    }

    @Test void reviewerDeletionCommittedFirstDeniesShareWithoutConsumingPilotSlot() {
        Fixture f = fixture();
        jdbc.update("DELETE FROM routiqo_account WHERE id = ?", f.reviewer);
        var service = service(f, new JdbcVerifiedContributorAuthority(jdbc));
        assertThatThrownBy(() -> service.share(f.actor, f.journey, f.command, f.request))
                .isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public_live_person_claim WHERE pilot_id = ?",
                Integer.class, f.pilot)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public_live_frozen_share_v2 WHERE pilot_id = ?",
                Integer.class, f.pilot)).isZero();
    }

    private FrozenPublicShareService service(Fixture f, VerifiedContributorReader verification) {
        PresenceConsentParticipant consents = mock(PresenceConsentParticipant.class);
        LiveRouteContextParticipant contexts = mock(LiveRouteContextParticipant.class);
        ContributionRestrictionReader restrictions = mock(ContributionRestrictionReader.class);
        SignalStorageStore receipts = mock(SignalStorageStore.class);
        when(consents.read(any())).thenReturn(new PresenceConsent(f.actor, f.journey, 7, true, true));
        when(contexts.read(any())).thenReturn(Optional.of(new StoredLiveRouteContext(
                new LiveRouteContext(f.context, f.actor, f.journey, 3, Set.of(f.anchor)),
                f.now.minusSeconds(60), f.now.plusSeconds(600), Optional.of(f.version))));
        when(restrictions.read(f.actor)).thenReturn(new ContributorAssessment(f.actor, 1,
                ContributorAssessment.State.ASSESSED, UUID.randomUUID(),
                f.now.minusSeconds(60), f.now.plusSeconds(600)));
        var admission = new SignalAdmission(f.actor, f.journey, f.context, f.anchor,
                3, 7, Set.of(QuickSignalValue.Category.TRAFFIC),
                f.now.minusSeconds(30), f.now.plusSeconds(30));
        when(receipts.findGrant(f.actor, f.command)).thenReturn(Optional.of(new SignalCommandGrant(
                f.command, admission, 1, SignalCommandGrant.State.CONSUMED)));
        var signal = new QuickSignal(f.command, f.actor, f.journey, f.anchor,
                QuickSignalValue.TRAFFIC_SLOW, 7, f.window,
                f.now.plusSeconds(600));
        when(receipts.findReceipt(f.actor, f.command)).thenReturn(Optional.of(
                new QuickSignalReceipt(signal, f.context, 3, f.now.plusSeconds(700),
                        QuickSignalReceipt.State.ACTIVE)));
        var catalog = new RouteAnchorCatalog(f.version, List.of(new RouteAnchor(
                f.anchor, new RouteRequest.Coordinate(80, 13),
                Set.of(QuickSignalValue.Category.TRAFFIC))));
        var accounts = new JdbcAccountWriteAuthority(jdbc, manager);
        var journeys = new JdbcJourneyStore(jdbc, accounts);
        var store = new JdbcFrozenPublicShareStore(jdbc, new com.routiqo.core.publiclive.privacy.JdbcPilotPersonClaimStore(jdbc));
        return new FrozenPublicShareService(f.pilot, journeys, consents, contexts,
                restrictions, verification, catalog, receipts, store,
                Clock.fixed(f.now, ZoneOffset.UTC));
    }

    private Fixture fixture() {
        Instant now = Instant.now();
        Instant window = Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(), 300) * 300);
        UUID actor = account(), reviewer = account(), otherReviewer = account();
        UUID pilot = UUID.randomUUID(), journey = UUID.randomUUID();
        UUID person = UUID.randomUUID(), verificationCase = UUID.randomUUID();
        UUID anchor = UUID.randomUUID(), version = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO journey(id, owner_id, kind, status, started_at)
            VALUES (?, ?, 'TRIP', 'ACTIVE', ?)
            """, journey, actor, Timestamp.from(now.minusSeconds(600)));
        jdbc.update("""
            INSERT INTO live_verification_case(id, account_id, person_ref, expires_at)
            VALUES (?, ?, ?, ?)
            """, verificationCase, actor, person, Timestamp.from(now.plusSeconds(3600)));
        jdbc.update("""
            INSERT INTO live_verified_contributor(account_id, case_id, person_ref,
                revision, state, first_reviewer_id, second_reviewer_id, expires_at)
            VALUES (?, ?, ?, 2, 'active', ?, ?, ?)
            """, actor, verificationCase, person, reviewer, otherReviewer,
                Timestamp.from(now.plusSeconds(3600)));
        jdbc.update("""
            INSERT INTO public_live_pilot(pilot_id, starts_at, ends_at, retain_until)
            VALUES (?, ?, ?, ?)
            """, pilot, Timestamp.from(window.minusSeconds(86400)),
                Timestamp.from(window.minusSeconds(86400).plusSeconds(720L * 3600)),
                Timestamp.from(window.minusSeconds(86400).plusSeconds(1440L * 3600)));
        jdbc.update("""
            INSERT INTO public_live_pilot_manifest(pilot_id, catalog_version, anchor_ids)
            VALUES (?, ?, ARRAY[?]::UUID[])
            """, pilot, version, anchor);
        return new Fixture(actor, reviewer, journey, pilot, person, anchor, version,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), now, window);
    }

    private UUID account() {
        UUID actor = UUID.randomUUID();
        jdbc.update("INSERT INTO routiqo_account(id, google_subject) VALUES (?, ?)",
                actor, "frozen-race-" + actor);
        return actor;
    }

    private record Fixture(UUID actor, UUID reviewer, UUID journey, UUID pilot,
            UUID person, UUID anchor, UUID version, UUID context, UUID command,
            UUID request, Instant now, Instant window) {}
}
