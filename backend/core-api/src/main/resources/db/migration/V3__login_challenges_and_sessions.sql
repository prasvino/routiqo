CREATE TABLE login_challenge (
    id UUID PRIMARY KEY,
    nonce VARCHAR(43) NOT NULL,
    binding_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    CHECK (expires_at > created_at),
    CHECK (consumed_at IS NULL OR consumed_at >= created_at)
);
CREATE INDEX login_challenge_expiry ON login_challenge(expires_at, id);
CREATE TABLE auth_session (
    token_hash CHAR(64) PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES routiqo_account(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CHECK (expires_at > created_at),
    CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);
CREATE INDEX auth_session_account ON auth_session(account_id);
CREATE INDEX auth_session_expiry ON auth_session(expires_at, token_hash);
