ALTER TABLE auth_session ADD COLUMN authenticated_at TIMESTAMPTZ;
UPDATE auth_session SET authenticated_at = created_at;
ALTER TABLE auth_session ALTER COLUMN authenticated_at SET NOT NULL;
ALTER TABLE auth_session ADD CONSTRAINT session_authentication_before_creation
    CHECK (authenticated_at <= created_at);
CREATE INDEX auth_session_authentication_time ON auth_session (authenticated_at, token_hash);
CREATE INDEX auth_session_family ON auth_session (account_id, authenticated_at);
