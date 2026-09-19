CREATE TABLE moderation_operator_grant (
    operator_id UUID NOT NULL CHECK (operator_id <> '00000000-0000-0000-0000-000000000000')
        REFERENCES routiqo_account(id) ON DELETE CASCADE,
    permission VARCHAR(16) NOT NULL CHECK (permission IN ('restrict', 'restore')),
    issued_at TIMESTAMPTZ NOT NULL CHECK (isfinite(issued_at)),
    expires_at TIMESTAMPTZ NOT NULL CHECK (isfinite(expires_at)),
    PRIMARY KEY (operator_id, permission),
    CONSTRAINT moderation_operator_grant_lifetime CHECK (
        expires_at > issued_at AND expires_at <= issued_at + INTERVAL '24 hours')
);

CREATE TABLE moderation_contribution_audit (
    operator_id UUID NOT NULL CHECK (operator_id <> '00000000-0000-0000-0000-000000000000')
        REFERENCES routiqo_account(id) ON DELETE CASCADE,
    request_id UUID NOT NULL CHECK (request_id <> '00000000-0000-0000-0000-000000000000'),
    target_id UUID NOT NULL CHECK (target_id <> '00000000-0000-0000-0000-000000000000')
        REFERENCES routiqo_account(id) ON DELETE CASCADE,
    expected_revision BIGINT NOT NULL CHECK (expected_revision >= 0),
    action VARCHAR(16) NOT NULL CHECK (action IN ('restrict', 'restore')),
    reason VARCHAR(32) NOT NULL CHECK (reason IN (
        'spam_manipulation', 'harassment', 'unsafe_content',
        'appeal_upheld', 'error_correction')),
    before_revision BIGINT NOT NULL CHECK (before_revision >= 0),
    after_revision BIGINT NOT NULL,
    restricted BOOLEAN NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL CHECK (isfinite(issued_at)),
    expires_at TIMESTAMPTZ NOT NULL CHECK (isfinite(expires_at)),
    PRIMARY KEY (operator_id, request_id),
    CONSTRAINT moderation_contribution_audit_identity CHECK (operator_id <> target_id),
    CONSTRAINT moderation_contribution_audit_revision CHECK (
        expected_revision = before_revision
        AND before_revision < 9223372036854775807
        AND after_revision = before_revision + 1),
    CONSTRAINT moderation_contribution_audit_action CHECK (
        (action = 'restrict' AND restricted = TRUE
            AND reason IN ('spam_manipulation', 'harassment', 'unsafe_content'))
        OR (action = 'restore' AND restricted = FALSE
            AND reason IN ('appeal_upheld', 'error_correction'))),
    CONSTRAINT moderation_contribution_audit_lifetime CHECK (
        expires_at = issued_at + INTERVAL '720 hours')
);
CREATE INDEX moderation_contribution_audit_expiry
    ON moderation_contribution_audit(expires_at, operator_id, request_id);
CREATE INDEX moderation_contribution_audit_target
    ON moderation_contribution_audit(target_id, operator_id, request_id);

CREATE TABLE moderation_restriction_action_slot (
    operator_id UUID NOT NULL CHECK (operator_id <> '00000000-0000-0000-0000-000000000000')
        REFERENCES routiqo_account(id) ON DELETE CASCADE,
    slot SMALLINT NOT NULL CHECK (slot BETWEEN 0 AND 19),
    used_at TIMESTAMPTZ NOT NULL CHECK (isfinite(used_at)),
    PRIMARY KEY (operator_id, slot)
);
CREATE INDEX moderation_restriction_action_slot_expiry
    ON moderation_restriction_action_slot(used_at, operator_id, slot);
