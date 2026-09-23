-- Cases and grants are provisioned only by the controlled verification operator workflow.
-- No document, raw identity, free text, or location is stored here.
CREATE TABLE live_verification_case (
    id UUID PRIMARY KEY CHECK (id <> '00000000-0000-0000-0000-000000000000'),
    account_id UUID NOT NULL REFERENCES routiqo_account(id) ON DELETE CASCADE,
    person_ref UUID NOT NULL CHECK (person_ref <> '00000000-0000-0000-0000-000000000000'),
    expires_at TIMESTAMPTZ NOT NULL CHECK (isfinite(expires_at))
);
CREATE INDEX live_verification_case_account ON live_verification_case(account_id, id);

CREATE TABLE live_verification_reviewer_grant (
    reviewer_id UUID NOT NULL REFERENCES routiqo_account(id) ON DELETE CASCADE,
    case_id UUID NOT NULL REFERENCES live_verification_case(id) ON DELETE CASCADE,
    action VARCHAR(12) NOT NULL CHECK (action IN ('review', 'revoke')),
    issued_at TIMESTAMPTZ NOT NULL CHECK (isfinite(issued_at)),
    expires_at TIMESTAMPTZ NOT NULL CHECK (isfinite(expires_at)),
    PRIMARY KEY (reviewer_id, case_id, action),
    CONSTRAINT live_verification_grant_lifetime CHECK (
        expires_at > issued_at AND expires_at <= issued_at + INTERVAL '24 hours')
);

CREATE TABLE live_verified_contributor (
    account_id UUID PRIMARY KEY REFERENCES routiqo_account(id) ON DELETE CASCADE,
    case_id UUID NOT NULL UNIQUE REFERENCES live_verification_case(id) ON DELETE CASCADE,
    person_ref UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision BETWEEN 1 AND 9223372036854775807),
    state VARCHAR(12) NOT NULL CHECK (state IN ('pending', 'active', 'revoked')),
    first_reviewer_id UUID NOT NULL REFERENCES routiqo_account(id) ON DELETE CASCADE,
    second_reviewer_id UUID REFERENCES routiqo_account(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL CHECK (isfinite(expires_at)),
    CONSTRAINT live_verified_contributor_reviewers CHECK (
        (state = 'pending' AND second_reviewer_id IS NULL)
        OR (state IN ('active', 'revoked') AND second_reviewer_id IS NOT NULL
            AND first_reviewer_id <> second_reviewer_id)),
    CONSTRAINT live_verified_contributor_no_self_review CHECK (
        account_id <> first_reviewer_id AND
        (second_reviewer_id IS NULL OR account_id <> second_reviewer_id))
);
CREATE UNIQUE INDEX live_verified_contributor_one_active_person
    ON live_verified_contributor(person_ref) WHERE state = 'active';
CREATE INDEX live_verified_contributor_expiry ON live_verified_contributor(expires_at, account_id)
    WHERE state = 'active';

CREATE TABLE live_verification_audit (
    reviewer_id UUID NOT NULL REFERENCES routiqo_account(id) ON DELETE CASCADE,
    request_id UUID NOT NULL CHECK (request_id <> '00000000-0000-0000-0000-000000000000'),
    case_id UUID NOT NULL REFERENCES live_verification_case(id) ON DELETE CASCADE,
    account_id UUID NOT NULL REFERENCES routiqo_account(id) ON DELETE CASCADE,
    action VARCHAR(12) NOT NULL CHECK (action IN ('review', 'revoke')),
    before_revision BIGINT NOT NULL CHECK (before_revision >= 0),
    after_revision BIGINT NOT NULL CHECK (after_revision = before_revision + 1),
    active BOOLEAN NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL CHECK (isfinite(occurred_at)),
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at = occurred_at + INTERVAL '2160 hours'),
    PRIMARY KEY (reviewer_id, request_id)
);
CREATE INDEX live_verification_audit_case ON live_verification_audit(case_id, occurred_at);
CREATE INDEX live_verification_audit_expiry ON live_verification_audit(expires_at, reviewer_id, request_id);
