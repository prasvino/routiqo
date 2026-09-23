CREATE TABLE admin_login_challenge (
    id UUID PRIMARY KEY,
    nonce VARCHAR(43) NOT NULL,
    binding_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ
);
CREATE INDEX admin_login_challenge_expiry ON admin_login_challenge(expires_at, id);

CREATE TABLE admin_auth_session (
    token_hash CHAR(64) PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES routiqo_account(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ
);
CREATE INDEX admin_auth_session_expiry ON admin_auth_session(expires_at, token_hash);

ALTER TABLE moderation_operator_grant DROP CONSTRAINT moderation_operator_grant_permission_check;
ALTER TABLE moderation_operator_grant ADD CONSTRAINT moderation_operator_grant_permission_check
    CHECK (permission IN ('restrict', 'restore', 'traffic_review', 'traffic_suppress'));

ALTER TABLE community_traffic_report_v3 ADD COLUMN review_sequence BIGINT GENERATED ALWAYS AS IDENTITY;

CREATE TABLE community_traffic_report_group_v3 (
    ref UUID PRIMARY KEY,
    latest TIMESTAMPTZ NOT NULL,
    latest_sequence BIGINT NOT NULL,
    inaccurate INTEGER NOT NULL CHECK (inaccurate >= 0),
    unsafe INTEGER NOT NULL CHECK (unsafe >= 0),
    spam INTEGER NOT NULL CHECK (spam >= 0),
    expires_at TIMESTAMPTZ NOT NULL
);
INSERT INTO community_traffic_report_group_v3
    (ref, latest, latest_sequence, inaccurate, unsafe, spam, expires_at)
SELECT ref, max(created_at), max(review_sequence),
    count(*) FILTER (WHERE reason = 'INACCURATE'),
    count(*) FILTER (WHERE reason = 'UNSAFE'),
    count(*) FILTER (WHERE reason = 'SPAM'), max(expires_at)
FROM community_traffic_report_v3 GROUP BY ref;
CREATE INDEX community_traffic_report_group_queue
    ON community_traffic_report_group_v3(latest DESC, ref DESC);
CREATE INDEX community_traffic_report_group_expiry
    ON community_traffic_report_group_v3(expires_at, ref);
CREATE INDEX community_traffic_report_by_ref_v3
    ON community_traffic_report_v3(ref, expires_at, review_sequence DESC);

CREATE FUNCTION community_traffic_report_group_after_delete_v3() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM community_traffic_report_v3 WHERE ref = OLD.ref) THEN
        UPDATE community_traffic_report_group_v3 g SET
            latest = x.latest, latest_sequence = x.latest_sequence,
            inaccurate = x.inaccurate, unsafe = x.unsafe, spam = x.spam,
            expires_at = x.expires_at
        FROM (SELECT max(created_at) latest, max(review_sequence) latest_sequence,
              count(*) FILTER (WHERE reason = 'INACCURATE') inaccurate,
              count(*) FILTER (WHERE reason = 'UNSAFE') unsafe,
              count(*) FILTER (WHERE reason = 'SPAM') spam, max(expires_at) expires_at
              FROM community_traffic_report_v3 WHERE ref = OLD.ref) x
        WHERE g.ref = OLD.ref;
    ELSE
        DELETE FROM community_traffic_report_group_v3 WHERE ref = OLD.ref;
    END IF;
    RETURN NULL;
END $$;
CREATE TRIGGER community_traffic_report_delete_group_v3
AFTER DELETE ON community_traffic_report_v3 FOR EACH ROW
EXECUTE FUNCTION community_traffic_report_group_after_delete_v3();

CREATE TABLE community_traffic_review_disposition_v3 (
    ref UUID PRIMARY KEY,
    operator_id UUID NOT NULL,
    request_id UUID NOT NULL,
    action VARCHAR(8) NOT NULL CHECK (action IN ('DISMISS', 'SUPPRESS')),
    reason VARCHAR(24) NOT NULL CHECK (reason IN ('INACCURATE', 'UNSAFE', 'SPAM', 'POLICY')),
    closed_through BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    UNIQUE (operator_id, request_id),
    CHECK (expires_at = occurred_at + INTERVAL '720 hours')
);
CREATE INDEX community_traffic_review_disposition_expiry
    ON community_traffic_review_disposition_v3(expires_at, ref);

CREATE TABLE community_traffic_review_action_audit_v3 (
    operator_id UUID NOT NULL,
    request_id UUID NOT NULL,
    ref UUID NOT NULL,
    action VARCHAR(8) NOT NULL CHECK (action IN ('DISMISS', 'SUPPRESS')),
    reason VARCHAR(24) NOT NULL CHECK (reason IN ('INACCURATE', 'UNSAFE', 'SPAM', 'POLICY')),
    occurred_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (operator_id, request_id),
    CHECK (expires_at = occurred_at + INTERVAL '720 hours')
);
CREATE INDEX community_traffic_review_action_expiry
    ON community_traffic_review_action_audit_v3(expires_at, operator_id, request_id);

CREATE TABLE community_traffic_moderator_read_audit_v3 (
    id UUID PRIMARY KEY,
    operator_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    item_count SMALLINT NOT NULL CHECK (item_count BETWEEN 0 AND 50),
    CHECK (expires_at = occurred_at + INTERVAL '720 hours')
);
CREATE INDEX community_traffic_moderator_read_audit_expiry
    ON community_traffic_moderator_read_audit_v3(expires_at, id);
