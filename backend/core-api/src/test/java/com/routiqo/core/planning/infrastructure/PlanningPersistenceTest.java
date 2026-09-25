package com.routiqo.core.planning.infrastructure;

import com.routiqo.core.planning.application.PlanningAccountUnavailable;
import com.routiqo.core.planning.application.PlanningBackupService;
import com.routiqo.core.planning.application.PlanningConflict;
import com.routiqo.core.planning.application.PlanningDocumentTooLarge;
import com.routiqo.core.planning.domain.AccountPlanningCopy;
import com.routiqo.core.planning.domain.PlanningDocument;
import com.routiqo.core.planning.domain.PlanningMutation;
import com.routiqo.core.planning.domain.PlanningPlan;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("persistence")
class PlanningPersistenceTest {
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16-alpine");
    static { DATABASE.start(); }
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
    }
    static final Instant NOW = Instant.parse("2026-09-25T07:00:00.123456789Z");
    @Autowired DataSource dataSource;
    @Autowired Flyway flyway;
    @Autowired PlanningBackupService wired;

    JdbcTemplate jdbc() { return new JdbcTemplate(dataSource); }
    JdbcPlanningCopyStore store() { return new JdbcPlanningCopyStore(jdbc(), new DataSourceTransactionManager(dataSource)); }
    PlanningBackupService service(Instant now) { return new PlanningBackupService(store(), Clock.fixed(now, ZoneOffset.UTC)); }
    UUID account() {
        UUID id = UUID.randomUUID();
        jdbc().update("INSERT INTO routiqo_account (id, google_subject) VALUES (?, ?)", id, id.toString());
        return id;
    }
    static PlanningPlan plan(String id, String notes) {
        return new PlanningPlan(id, PlanningPlan.Kind.COMMUTE, "Navalur", "DLF Chennai", "2026-09-28", "08:15",
                List.of(1, 2, 3, 4, 5), notes, "2026-09-25T06:00:00.000Z");
    }
    static PlanningDocument document(String... ids) {
        List<PlanningPlan> plans = new ArrayList<>();
        for (String id : ids) plans.add(plan(id, "notes for " + id));
        return new PlanningDocument(plans, List.of("kodaikanal"));
    }
    static PlanningMutation mutation(PlanningDocument document, long expected) {
        return new PlanningMutation(document, expected, UUID.randomUUID());
    }
    long rows(UUID account) {
        return jdbc().queryForObject("SELECT count(*) FROM account_planning_copy WHERE account_id = ?", Long.class, account);
    }

    @Test void migrationIsCurrentAndServiceIsWiredUnderPersistence() {
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(wired).isNotNull();
        assertThat(service(NOW).get(account())).isEqualTo(AccountPlanningCopy.absent());
    }

    @Test void firstSaveCompareAndSwapAndExactReplay() {
        UUID owner = account();
        var first = mutation(document("a", "b"), 0);
        AccountPlanningCopy saved = service(NOW).save(owner, first);
        assertThat(saved.version()).isEqualTo(1);
        assertThat(saved.updatedAt()).isEqualTo(Instant.parse("2026-09-25T07:00:00.123456Z"));
        assertThat(service(NOW).get(owner)).isEqualTo(saved);
        assertThat(service(NOW.plusSeconds(30)).save(owner, first)).isEqualTo(saved);

        assertThatThrownBy(() -> service(NOW).save(owner, new PlanningMutation(document("changed"), 0, first.mutationId())))
                .isInstanceOf(PlanningConflict.class);
        assertThatThrownBy(() -> service(NOW).save(owner, mutation(document("stale"), 0)))
                .isInstanceOf(PlanningConflict.class);
        assertThatThrownBy(() -> service(NOW).save(owner, mutation(document("future"), 2)))
                .isInstanceOf(PlanningConflict.class);

        var second = mutation(document("c"), 1);
        AccountPlanningCopy replaced = service(NOW.plusSeconds(60)).save(owner, second);
        assertThat(replaced.version()).isEqualTo(2);
        assertThat(replaced.document().plans()).extracting(PlanningPlan::id).containsExactly("c");
        assertThatThrownBy(() -> service(NOW).save(owner, first)).isInstanceOf(PlanningConflict.class);
        assertThat(service(NOW).save(owner, second)).isEqualTo(replaced);
        assertThat(service(NOW).get(owner)).isEqualTo(replaced);
    }

    @Test void emptyDocumentsAreStoredAsAnExplicitCopy() {
        UUID owner = account();
        AccountPlanningCopy empty = service(NOW).save(owner, mutation(PlanningDocument.empty(), 0));
        assertThat(empty.version()).isEqualTo(1);
        assertThat(service(NOW).get(owner).document().isEmpty()).isTrue();
        assertThat(service(NOW).get(owner).version()).isEqualTo(1);
    }

    @Test void storedTextRoundTripsExactly() {
        UUID owner = account();
        var plan = new PlanningPlan("id-😀", PlanningPlan.Kind.TRIP, " தமிழ் நாடு ", "Pondicherry \"beach\" \\ 🌊",
                "2028-02-29", "23:59", List.of(), "line\nnext\ttab\r", "2026-09-25T06:00:00.000Z");
        var document = new PlanningDocument(List.of(plan), List.of("a", "b-2"));
        service(NOW).save(owner, mutation(document, 0));
        assertThat(service(NOW).get(owner).document()).isEqualTo(document);
    }

    @Test void ownersAreIsolated() {
        UUID owner = account(); UUID stranger = account();
        service(NOW).save(owner, mutation(document("private"), 0));
        assertThat(service(NOW).get(stranger)).isEqualTo(AccountPlanningCopy.absent());
        assertThatCode(() -> service(NOW).delete(stranger, 1)).doesNotThrowAnyException();
        assertThat(rows(owner)).isEqualTo(1);
        service(NOW).save(stranger, mutation(document("theirs"), 0));
        assertThat(service(NOW).get(owner).document().plans()).extracting(PlanningPlan::id).containsExactly("private");
    }

    @Test void deleteMatchesVersionAndIsIdempotentWhenAbsent() {
        UUID owner = account();
        service(NOW).delete(owner, 1);
        service(NOW).save(owner, mutation(document("a"), 0));
        service(NOW).save(owner, mutation(document("b"), 1));
        assertThatThrownBy(() -> service(NOW).delete(owner, 1)).isInstanceOf(PlanningConflict.class);
        assertThat(rows(owner)).isEqualTo(1);
        service(NOW).delete(owner, 2);
        assertThat(rows(owner)).isZero();
        assertThat(service(NOW).get(owner)).isEqualTo(AccountPlanningCopy.absent());
        service(NOW).delete(owner, 2);
        assertThat(service(NOW).save(owner, mutation(document("again"), 0)).version()).isEqualTo(1);
        assertThatThrownBy(() -> service(NOW).delete(owner, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void accountDeletionCascadesAndMissingAccountsCannotWrite() {
        UUID owner = account();
        service(NOW).save(owner, mutation(document("a"), 0));
        jdbc().update("DELETE FROM routiqo_account WHERE id = ?", owner);
        assertThat(rows(owner)).isZero();
        assertThatThrownBy(() -> service(NOW).save(owner, mutation(document("a"), 0)))
                .isInstanceOf(PlanningAccountUnavailable.class);
        assertThat(rows(owner)).isZero();
    }

    @Test void oversizedDocumentsAreRejectedBeforeStorage() {
        UUID owner = account();
        List<PlanningPlan> plans = new ArrayList<>();
        // 100 plans with 500 three-byte characters of notes fit; three-byte text in every field does not.
        for (int index = 0; index < 100; index++) plans.add(plan("p" + index, "த".repeat(500)));
        var fits = new PlanningDocument(plans, List.of());
        assertThat(service(NOW).save(owner, mutation(fits, 0)).version()).isEqualTo(1);
        assertThat(jdbc().queryForObject("SELECT document_bytes FROM account_planning_copy WHERE account_id = ?",
                Integer.class, owner)).isBetween(150_000, PlanningDocument.MAX_BYTES);

        List<PlanningPlan> escaped = new ArrayList<>();
        for (int index = 0; index < 100; index++)
            escaped.add(new PlanningPlan("த".repeat(97) + String.format("%03d", index), PlanningPlan.Kind.TRIP,
                    "த".repeat(100), "த".repeat(100), "2026-01-01", "00:00", List.of(), "த".repeat(500),
                    "த".repeat(64)));
        UUID other = account();
        assertThatThrownBy(() -> service(NOW).save(other, mutation(new PlanningDocument(escaped, List.of()), 0)))
                .isInstanceOf(PlanningDocumentTooLarge.class);
        assertThat(rows(other)).isZero();
    }

    @Test void concurrentFirstSavesProduceOneVersionAndReconcileExactReplay() throws Exception {
        UUID owner = account();
        var shared = mutation(document("same"), 0);
        var executor = Executors.newFixedThreadPool(4);
        try {
            var ready = new CountDownLatch(4);
            var go = new CountDownLatch(1);
            List<Future<Object>> results = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                PlanningMutation next = index < 2 ? shared : mutation(document("other-" + index), 0);
                Callable<Object> task = () -> {
                    ready.countDown();
                    go.await(5, TimeUnit.SECONDS);
                    try { return service(NOW).save(owner, next); }
                    catch (PlanningConflict conflict) { return conflict; }
                };
                results.add(executor.submit(task));
            }
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> result : results) outcomes.add(result.get(20, TimeUnit.SECONDS));
            long successes = outcomes.stream().filter(AccountPlanningCopy.class::isInstance).count();
            assertThat(successes).isBetween(1L, 2L);
            AccountPlanningCopy stored = service(NOW).get(owner);
            assertThat(stored.version()).isEqualTo(1);
            for (Object outcome : outcomes) {
                if (outcome instanceof AccountPlanningCopy copy) assertThat(copy).isEqualTo(stored);
            }
            if (successes == 2) assertThat(stored.document()).isEqualTo(shared.document());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test void corruptStoredRowsFailClosedWithoutEchoingContent() {
        UUID owner = account();
        service(NOW).save(owner, mutation(document("a"), 0));
        jdbc().update("UPDATE account_planning_copy SET plans = '[{\"id\":\"secret-value\"}]'::jsonb WHERE account_id = ?", owner);
        assertThatThrownBy(() -> service(NOW).get(owner)).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("secret-value");
    }

    @Test void databaseConstraintsBoundRowsIndependentlyOfTheApplication() {
        UUID owner = account();
        assertThatThrownBy(() -> jdbc().update("""
            INSERT INTO account_planning_copy (account_id, plans, saved, document_bytes, version, updated_at, latest_mutation_id)
            VALUES (?, '{}'::jsonb, '[]'::jsonb, 10, 1, now(), gen_random_uuid())""", owner))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc().update("""
            INSERT INTO account_planning_copy (account_id, plans, saved, document_bytes, version, updated_at, latest_mutation_id)
            VALUES (?, '[]'::jsonb, '[]'::jsonb, 262145, 1, now(), gen_random_uuid())""", owner))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc().update("""
            INSERT INTO account_planning_copy (account_id, plans, saved, document_bytes, version, updated_at, latest_mutation_id)
            VALUES (?, '[]'::jsonb, '[]'::jsonb, 10, 0, now(), gen_random_uuid())""", owner))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
