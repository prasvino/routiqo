ALTER TABLE moderation_operator_grant ALTER COLUMN permission TYPE VARCHAR(24);
ALTER TABLE moderation_operator_grant DROP CONSTRAINT moderation_operator_grant_permission_check;
ALTER TABLE moderation_operator_grant ADD CONSTRAINT moderation_operator_grant_permission_check
    CHECK (permission IN ('restrict', 'restore', 'traffic_review', 'traffic_suppress', 'traffic_grant_admin'));

CREATE TABLE traffic_grant_action_audit_v3 (
    administrator_id UUID NOT NULL REFERENCES routiqo_account(id) ON DELETE CASCADE,
    request_id UUID NOT NULL CHECK (request_id <> '00000000-0000-0000-0000-000000000000'),
    target_id UUID NOT NULL REFERENCES routiqo_account(id) ON DELETE CASCADE,
    permission VARCHAR(16) NOT NULL CHECK (permission IN ('traffic_review', 'traffic_suppress')),
    action VARCHAR(8) NOT NULL CHECK (action IN ('ISSUE', 'REVOKE')),
    reason VARCHAR(24) NOT NULL CHECK (reason IN
        ('OPERATOR_TRIAL', 'COVERAGE_CHANGE', 'SECURITY_RESPONSE', 'ERROR_CORRECTION')),
    duration_minutes SMALLINT CHECK (duration_minutes BETWEEN 15 AND 240),
    grant_expires_at TIMESTAMPTZ,
    occurred_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (administrator_id, request_id),
    CHECK (administrator_id <> target_id),
    CHECK ((action = 'ISSUE' AND duration_minutes IS NOT NULL AND grant_expires_at IS NOT NULL)
        OR (action = 'REVOKE' AND duration_minutes IS NULL AND grant_expires_at IS NULL)),
    CHECK (expires_at = occurred_at + INTERVAL '720 hours')
);
CREATE INDEX traffic_grant_action_audit_v3_expiry
    ON traffic_grant_action_audit_v3(expires_at, administrator_id, request_id);

CREATE TABLE traffic_grant_read_audit_v3 (
    id UUID PRIMARY KEY,
    administrator_id UUID NOT NULL REFERENCES routiqo_account(id) ON DELETE CASCADE,
    occurred_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CHECK (expires_at = occurred_at + INTERVAL '720 hours')
);
CREATE INDEX traffic_grant_read_audit_v3_expiry
    ON traffic_grant_read_audit_v3(expires_at, id);
