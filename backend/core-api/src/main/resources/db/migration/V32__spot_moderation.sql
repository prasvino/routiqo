-- Spots moderation (ADR 0075, PILOT_MODERATION_SPEC.md): shift grants, renewable admin sessions,
-- the Spot report queue with decisions, moderator hide, and minimized 30-day audit.

-- Spots permissions join the existing exact-account grants (the 24-hour database cap stays).
ALTER TABLE moderation_operator_grant DROP CONSTRAINT moderation_operator_grant_permission_check;
ALTER TABLE moderation_operator_grant ADD CONSTRAINT moderation_operator_grant_permission_check
    CHECK (permission IN ('restrict', 'restore', 'traffic_review', 'traffic_suppress', 'traffic_grant_admin',
        'spots_review', 'spots_hide', 'spots_restrict', 'spots_alias_lookup', 'spots_grant_admin'));

-- Sessions renew while active, never past 8 hours from sign-in.
ALTER TABLE admin_auth_session ADD COLUMN absolute_expires_at TIMESTAMPTZ;
UPDATE admin_auth_session SET absolute_expires_at = expires_at;
ALTER TABLE admin_auth_session ALTER COLUMN absolute_expires_at SET NOT NULL;
ALTER TABLE admin_auth_session ADD CONSTRAINT admin_auth_session_absolute CHECK (
    expires_at <= absolute_expires_at AND absolute_expires_at <= created_at + INTERVAL '8 hours');

-- Moderator hide is a separate column, so state, ended_at and expiry purges are unchanged.
ALTER TABLE spot_post ADD COLUMN moderation_hidden_at TIMESTAMPTZ;
ALTER TABLE spot_signal ADD COLUMN moderation_hidden_at TIMESTAMPTZ;

-- Report groups gain an opaque queue ref and a decision. A group is open until decided, and a
-- newer report (latest_sequence > closed_through) reopens it.
ALTER TABLE spot_report_group ADD COLUMN ref UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE spot_report_group ADD CONSTRAINT spot_report_group_ref UNIQUE (ref);
ALTER TABLE spot_report_group ADD COLUMN open_since TIMESTAMPTZ;
UPDATE spot_report_group SET open_since = latest;
ALTER TABLE spot_report_group ALTER COLUMN open_since SET NOT NULL;
ALTER TABLE spot_report_group ADD COLUMN closed_through BIGINT;
ALTER TABLE spot_report_group ADD COLUMN decision VARCHAR(32) CHECK (decision IN
    ('DISMISSED', 'HIDDEN', 'RESTORED', 'CLEARED', 'CLOSED_EVIDENCE_UNAVAILABLE'));
ALTER TABLE spot_report_group ADD COLUMN decided_at TIMESTAMPTZ;
-- Reports up to this sequence were already ruled not upheld (dismiss or restore), so a later
-- ruling never counts a reporter twice and never counts reports a hide upheld.
ALTER TABLE spot_report_group ADD COLUMN not_upheld_through BIGINT;
ALTER TABLE spot_report_group ADD CONSTRAINT spot_report_group_decision CHECK (
    (closed_through IS NULL) = (decision IS NULL) AND (decision IS NULL) = (decided_at IS NULL));
-- Urgent first (unsafe, abuse or personal data), newest report first within a tier.
DROP INDEX spot_report_group_queue;
CREATE INDEX spot_report_group_open_queue ON spot_report_group
    ((((unsafe + abuse + personal_data) > 0)) DESC, latest_sequence DESC, ref)
    WHERE closed_through IS NULL OR latest_sequence > closed_through;

-- Evidence ruled not upheld (dismissed or restored) no longer blocks highlights.
ALTER TABLE spot_report_evidence ADD COLUMN not_upheld_at TIMESTAMPTZ;

-- Reporters whose reports were ruled not upheld, for the lookup flag (3 or more in 30 days).
CREATE TABLE spot_reporter_not_upheld (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reporter_id UUID NOT NULL REFERENCES routiqo_account(id) ON DELETE CASCADE,
    decided_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CHECK (expires_at = decided_at + INTERVAL '30 days')
);
CREATE INDEX spot_reporter_not_upheld_by_reporter ON spot_reporter_not_upheld (reporter_id, decided_at);
CREATE INDEX spot_reporter_not_upheld_expiry ON spot_reporter_not_upheld (expires_at);

-- Exact-request replay and a minimized decision audit. No item text, alias or reporter.
CREATE TABLE spot_moderation_action (
    operator_id UUID NOT NULL REFERENCES routiqo_account(id) ON DELETE CASCADE,
    request_id UUID NOT NULL CHECK (request_id <> '00000000-0000-0000-0000-000000000000'),
    action VARCHAR(16) NOT NULL CHECK (action IN ('DISMISS', 'HIDE', 'RESTORE', 'CLEAR_SIGNALS', 'LOOKUP')),
    report_ref UUID NOT NULL,
    reason VARCHAR(24) NOT NULL CHECK (reason IN ('abuse', 'spam', 'false_alarm', 'personal_data', 'unsafe',
        'not_upheld', 'error_correction')),
    fingerprint CHAR(64) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (operator_id, request_id),
    CHECK (expires_at = occurred_at + INTERVAL '30 days')
);
CREATE INDEX spot_moderation_action_expiry ON spot_moderation_action (expires_at);

CREATE TABLE spot_moderation_read_audit (
    id UUID PRIMARY KEY,
    operator_id UUID NOT NULL REFERENCES routiqo_account(id) ON DELETE CASCADE,
    item_count SMALLINT NOT NULL CHECK (item_count BETWEEN 0 AND 20),
    occurred_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CHECK (expires_at = occurred_at + INTERVAL '30 days')
);
CREATE INDEX spot_moderation_read_audit_expiry ON spot_moderation_read_audit (expires_at);

-- Opaque account references from an audited lookup: hashed, 30 minutes, usable only by that operator.
CREATE TABLE spot_account_lookup_ref (
    token_hash CHAR(64) PRIMARY KEY,
    operator_id UUID NOT NULL REFERENCES routiqo_account(id) ON DELETE CASCADE,
    account_id UUID NOT NULL REFERENCES routiqo_account(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CHECK (expires_at = created_at + INTERVAL '30 minutes')
);
CREATE INDEX spot_account_lookup_ref_expiry ON spot_account_lookup_ref (expires_at);

-- Spots shift grants: 1 to 12 hours, issued and revoked by a grant admin, audited 30 days.
CREATE TABLE spot_grant_action_audit (
    administrator_id UUID NOT NULL REFERENCES routiqo_account(id) ON DELETE CASCADE,
    request_id UUID NOT NULL CHECK (request_id <> '00000000-0000-0000-0000-000000000000'),
    target_id UUID NOT NULL REFERENCES routiqo_account(id) ON DELETE CASCADE,
    permission VARCHAR(24) NOT NULL CHECK (permission IN
        ('spots_review', 'spots_hide', 'spots_restrict', 'spots_alias_lookup')),
    action VARCHAR(8) NOT NULL CHECK (action IN ('ISSUE', 'REVOKE')),
    reason VARCHAR(24) NOT NULL CHECK (reason IN
        ('SHIFT_START', 'COVERAGE_CHANGE', 'SECURITY_RESPONSE', 'ERROR_CORRECTION')),
    duration_minutes SMALLINT CHECK (duration_minutes BETWEEN 60 AND 720),
    grant_expires_at TIMESTAMPTZ,
    occurred_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (administrator_id, request_id),
    CHECK (administrator_id <> target_id),
    CHECK ((action = 'ISSUE' AND duration_minutes IS NOT NULL AND grant_expires_at IS NOT NULL)
        OR (action = 'REVOKE' AND duration_minutes IS NULL AND grant_expires_at IS NULL)),
    CHECK (expires_at = occurred_at + INTERVAL '720 hours')
);
CREATE INDEX spot_grant_action_audit_expiry ON spot_grant_action_audit (expires_at);

CREATE TABLE spot_grant_read_audit (
    id UUID PRIMARY KEY,
    administrator_id UUID NOT NULL REFERENCES routiqo_account(id) ON DELETE CASCADE,
    occurred_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CHECK (expires_at = occurred_at + INTERVAL '720 hours')
);
CREATE INDEX spot_grant_read_audit_expiry ON spot_grant_read_audit (expires_at);
