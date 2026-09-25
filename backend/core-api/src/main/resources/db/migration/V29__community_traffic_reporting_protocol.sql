-- Reporting protocol (docs/adr/0064-v1-reporting-protocol.md).

-- Reporter-linked rows are kept 7 days for new writes. Existing rows keep their original 30-day expiry.
DO $$
DECLARE name TEXT;
BEGIN
    SELECT conname INTO name FROM pg_constraint
    WHERE conrelid = 'community_traffic_report_v3'::regclass AND contype = 'c'
      AND pg_get_constraintdef(oid) LIKE '%720:00:00%';
    EXECUTE format('ALTER TABLE community_traffic_report_v3 DROP CONSTRAINT %I', name);
END $$;
ALTER TABLE community_traffic_report_v3 ADD CONSTRAINT community_traffic_report_v3_expiry_check
    CHECK (expires_at = created_at + INTERVAL '168 hours' OR expires_at = created_at + INTERVAL '720 hours');

-- Durable per-reporter rolling quota reads recent rows by reporter.
CREATE INDEX community_traffic_report_by_actor_v3
    ON community_traffic_report_v3(actor_id, created_at);

-- Reporter-free group counts outlive reporter rows. A retention purge leaves counts unchanged;
-- deleting a reporter (account deletion) still removes that reporter's contribution.
CREATE OR REPLACE FUNCTION community_traffic_report_group_after_delete_v3() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF current_setting('routiqo.report_retention_purge', true) = 'on' THEN
        RETURN NULL;
    END IF;
    UPDATE community_traffic_report_group_v3 SET
        inaccurate = GREATEST(inaccurate - (OLD.reason = 'INACCURATE')::int, 0),
        unsafe = GREATEST(unsafe - (OLD.reason = 'UNSAFE')::int, 0),
        spam = GREATEST(spam - (OLD.reason = 'SPAM')::int, 0)
    WHERE ref = OLD.ref;
    DELETE FROM community_traffic_report_group_v3
    WHERE ref = OLD.ref AND inaccurate = 0 AND unsafe = 0 AND spam = 0;
    RETURN NULL;
END $$;

-- Safety reports first in the operator queue.
CREATE INDEX community_traffic_report_group_priority_queue
    ON community_traffic_report_group_v3((unsafe > 0) DESC, latest DESC, ref DESC);

-- Automatic closure when evidence is gone, distinct from an operator dismissal.
DO $$
DECLARE name TEXT;
BEGIN
    SELECT conname INTO name FROM pg_constraint
    WHERE conrelid = 'community_traffic_review_disposition_v3'::regclass AND contype = 'c'
      AND pg_get_constraintdef(oid) LIKE '%DISMISS%';
    EXECUTE format('ALTER TABLE community_traffic_review_disposition_v3 DROP CONSTRAINT %I', name);
END $$;
ALTER TABLE community_traffic_review_disposition_v3
    ALTER COLUMN action TYPE VARCHAR(32),
    ALTER COLUMN operator_id DROP NOT NULL,
    ALTER COLUMN request_id DROP NOT NULL,
    ALTER COLUMN reason DROP NOT NULL,
    ADD CONSTRAINT community_traffic_review_disposition_v3_action_check
        CHECK (action IN ('DISMISS', 'SUPPRESS', 'CLOSED_EVIDENCE_UNAVAILABLE')),
    ADD CONSTRAINT community_traffic_review_disposition_v3_system_check
        CHECK ((action = 'CLOSED_EVIDENCE_UNAVAILABLE')
            = (operator_id IS NULL AND request_id IS NULL AND reason IS NULL));

-- One-time backfill: groups whose projection content was already purged close the same way.
INSERT INTO community_traffic_review_disposition_v3
    (ref, operator_id, request_id, action, reason, closed_through, occurred_at, expires_at)
SELECT g.ref, NULL, NULL, 'CLOSED_EVIDENCE_UNAVAILABLE', NULL, g.latest_sequence, now(), now() + INTERVAL '720 hours'
FROM community_traffic_report_group_v3 g
WHERE NOT EXISTS (SELECT 1 FROM community_traffic_projection_v3 p WHERE p.ref = g.ref)
ON CONFLICT (ref) DO UPDATE SET operator_id = NULL, request_id = NULL, action = EXCLUDED.action,
    reason = NULL, closed_through = EXCLUDED.closed_through, occurred_at = EXCLUDED.occurred_at,
    expires_at = EXCLUDED.expires_at
WHERE community_traffic_review_disposition_v3.action = 'DISMISS'
  AND community_traffic_review_disposition_v3.closed_through < EXCLUDED.closed_through;
