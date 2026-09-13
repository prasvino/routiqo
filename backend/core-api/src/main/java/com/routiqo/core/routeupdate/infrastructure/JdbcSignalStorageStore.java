package com.routiqo.core.routeupdate.infrastructure;

import com.routiqo.core.routeupdate.application.SignalStorageConflict;
import com.routiqo.core.routeupdate.application.SignalStorageDenied;
import com.routiqo.core.routeupdate.application.SignalStorageRateLimited;
import com.routiqo.core.routeupdate.application.SignalStorageStore;
import com.routiqo.core.routeupdate.domain.QuickSignal;
import com.routiqo.core.routeupdate.domain.QuickSignalReceipt;
import com.routiqo.core.routeupdate.domain.QuickSignalValue;
import com.routiqo.core.routeupdate.domain.SignalAdmission;
import com.routiqo.core.routeupdate.domain.SignalCommandGrant;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class JdbcSignalStorageStore implements SignalStorageStore {
    private final JdbcTemplate jdbc;

    public JdbcSignalStorageStore(JdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
    }

    @Override
    public Optional<SignalCommandGrant> findGrant(UUID actorId, UUID commandId) {
        requireTransaction();
        return jdbc.query("""
            SELECT actor_id, command_id, journey_id, context_id, route_revision, anchor_id,
                   consent_generation, permitted_categories, issued_at, expires_at, state
            FROM signal_command_grant
            WHERE actor_id = ? AND command_id = ? FOR UPDATE
            """, JdbcSignalStorageStore::mapGrant, actorId, commandId).stream().findFirst();
    }

    @Override
    public Optional<QuickSignalReceipt> findReceipt(UUID actorId, UUID commandId) {
        requireTransaction();
        return jdbc.query("""
            SELECT actor_id, command_id, journey_id, anchor_id, signal_value, category,
                   consent_generation, context_id, route_revision, received_at,
                   evidence_expires_at, retain_until, state
            FROM quick_signal_receipt
            WHERE actor_id = ? AND command_id = ? FOR UPDATE
            """, JdbcSignalStorageStore::mapReceipt, actorId, commandId).stream().findFirst();
    }

    @Override
    public Optional<QuickSignalReceipt> findActiveSlot(
            UUID actorId, UUID anchorId, QuickSignalValue.Category category) {
        requireTransaction();
        return jdbc.query("""
            SELECT actor_id, command_id, journey_id, anchor_id, signal_value, category,
                   consent_generation, context_id, route_revision, received_at,
                   evidence_expires_at, retain_until, state
            FROM quick_signal_receipt
            WHERE actor_id = ? AND anchor_id = ? AND category = ? AND state = 'ACTIVE'
            FOR UPDATE
            """, JdbcSignalStorageStore::mapReceipt,
                actorId, anchorId, category.name()).stream().findFirst();
    }

    @Override
    public void reserveBudget(UUID actorId, BudgetAction action, Instant now, int limit) {
        requireTransaction();
        Instant bucket = now.truncatedTo(ChronoUnit.MINUTES);
        List<Budget> rows = jdbc.query("""
            SELECT bucket_start, used_count FROM signal_actor_budget
            WHERE actor_id = ? AND action = ? FOR UPDATE
            """, (row, number) -> new Budget(
                    row.getTimestamp("bucket_start").toInstant(), row.getInt("used_count")),
                actorId, action.name());
        if (rows.isEmpty()) {
            jdbc.update("""
                INSERT INTO signal_actor_budget(actor_id, action, bucket_start, used_count)
                VALUES (?, ?, ?, 1)
                """, actorId, action.name(), Timestamp.from(bucket));
            return;
        }
        Budget stored = rows.getFirst();
        if (bucket.isBefore(stored.bucketStart())) {
            throw new SignalStorageDenied();
        }
        if (bucket.isAfter(stored.bucketStart())) {
            jdbc.update("""
                UPDATE signal_actor_budget SET bucket_start = ?, used_count = 1
                WHERE actor_id = ? AND action = ? AND bucket_start = ?
                """, Timestamp.from(bucket), actorId, action.name(),
                    Timestamp.from(stored.bucketStart()));
            return;
        }
        if (stored.usedCount() >= limit) {
            throw new SignalStorageRateLimited();
        }
        int updated = jdbc.update("""
            UPDATE signal_actor_budget SET used_count = used_count + 1
            WHERE actor_id = ? AND action = ? AND bucket_start = ? AND used_count = ?
            """, actorId, action.name(), Timestamp.from(bucket), stored.usedCount());
        if (updated != 1) {
            throw new SignalStorageConflict();
        }
    }

    @Override
    public void insertGrant(SignalCommandGrant grant) {
        requireTransaction();
        SignalAdmission admission = grant.admission();
        jdbc.update("""
            INSERT INTO signal_command_grant
                (actor_id, command_id, journey_id, context_id, route_revision, anchor_id,
                 consent_generation, permitted_categories, issued_at, expires_at, state)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, admission.actorId(), grant.commandId(), admission.journeyId(),
                admission.contextId(), admission.routeRevision(), admission.anchorId(),
                admission.consentGeneration(), names(admission.permittedCategories()),
                Timestamp.from(admission.issuedAt()), Timestamp.from(admission.expiresAt()),
                grant.state().name());
    }

    @Override
    public void consumeGrant(SignalCommandGrant grant) {
        requireTransaction();
        int updated = jdbc.update("""
            UPDATE signal_command_grant SET state = 'CONSUMED'
            WHERE actor_id = ? AND command_id = ? AND state = 'UNUSED'
            """, grant.admission().actorId(), grant.commandId());
        if (updated != 1) {
            throw new SignalStorageConflict();
        }
    }

    @Override
    public void supersede(QuickSignalReceipt receipt) {
        requireTransaction();
        updateReceiptState(receipt, "ACTIVE", QuickSignalReceipt.State.SUPERSEDED);
    }

    @Override
    public void insertReceipt(QuickSignalReceipt receipt) {
        requireTransaction();
        QuickSignal signal = receipt.signal();
        jdbc.update("""
            INSERT INTO quick_signal_receipt
                (actor_id, command_id, journey_id, anchor_id, signal_value, category,
                 consent_generation, context_id, route_revision, received_at,
                 evidence_expires_at, retain_until, state)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, signal.actorId(), signal.signalId(), signal.journeyId(), signal.anchorId(),
                signal.value().name(), signal.value().category().name(), signal.consentGeneration(),
                receipt.contextId(), receipt.routeRevision(), Timestamp.from(signal.receivedAt()),
                Timestamp.from(signal.expiresAt()), Timestamp.from(receipt.retainUntil()),
                receipt.state().name());
    }

    @Override
    public void withdraw(QuickSignalReceipt receipt) {
        requireTransaction();
        if (receipt.state() == QuickSignalReceipt.State.WITHDRAWN) {
            updateReceiptState(receipt, "ACTIVE", QuickSignalReceipt.State.WITHDRAWN);
        }
    }

    private void updateReceiptState(
            QuickSignalReceipt receipt, String expected, QuickSignalReceipt.State state) {
        int updated = jdbc.update("""
            UPDATE quick_signal_receipt SET state = ?
            WHERE actor_id = ? AND command_id = ? AND state = ?
            """, state.name(), receipt.signal().actorId(), receipt.signal().signalId(), expected);
        if (updated != 1) {
            throw new SignalStorageConflict();
        }
    }

    private static SignalCommandGrant mapGrant(ResultSet row, int number) throws SQLException {
        Set<QuickSignalValue.Category> categories = new LinkedHashSet<>();
        Array array = row.getArray("permitted_categories");
        for (String value : (String[]) array.getArray()) {
            categories.add(QuickSignalValue.Category.valueOf(value));
        }
        SignalAdmission admission = new SignalAdmission(
                row.getObject("actor_id", UUID.class), row.getObject("journey_id", UUID.class),
                row.getObject("context_id", UUID.class), row.getObject("anchor_id", UUID.class),
                row.getLong("route_revision"), row.getLong("consent_generation"), categories,
                row.getTimestamp("issued_at").toInstant(), row.getTimestamp("expires_at").toInstant());
        return new SignalCommandGrant(row.getObject("command_id", UUID.class), admission,
                SignalCommandGrant.State.valueOf(row.getString("state")));
    }

    private static QuickSignalReceipt mapReceipt(ResultSet row, int number) throws SQLException {
        QuickSignalValue value = QuickSignalValue.valueOf(row.getString("signal_value"));
        if (!value.category().name().equals(row.getString("category"))) {
            throw new SQLException("Stored signal receipt is invalid");
        }
        QuickSignal signal = new QuickSignal(
                row.getObject("command_id", UUID.class), row.getObject("actor_id", UUID.class),
                row.getObject("journey_id", UUID.class), row.getObject("anchor_id", UUID.class),
                value, row.getLong("consent_generation"),
                row.getTimestamp("received_at").toInstant(),
                row.getTimestamp("evidence_expires_at").toInstant());
        return new QuickSignalReceipt(signal, row.getObject("context_id", UUID.class),
                row.getLong("route_revision"), row.getTimestamp("retain_until").toInstant(),
                QuickSignalReceipt.State.valueOf(row.getString("state")));
    }

    private static String[] names(Set<QuickSignalValue.Category> categories) {
        return categories.stream().map(Enum::name).sorted().toArray(String[]::new);
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Signal storage transaction is required");
        }
    }

    private record Budget(Instant bucketStart, int usedCount) {
        @Override public String toString() { return "SignalBudget[private]"; }
    }

    @Override
    public String toString() {
        return "JdbcSignalStorageStore[private]";
    }
}
