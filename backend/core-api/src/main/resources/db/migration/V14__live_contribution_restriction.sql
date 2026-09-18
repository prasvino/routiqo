-- Latest private suspension authority only. Assessment metadata is not durable in this slice.
CREATE TABLE live_contribution_restriction (
    actor_id UUID PRIMARY KEY REFERENCES routiqo_account(id) ON DELETE CASCADE
        CHECK (actor_id <> '00000000-0000-0000-0000-000000000000'),
    revision BIGINT NOT NULL CHECK (revision > 0),
    restricted BOOLEAN NOT NULL,
    CONSTRAINT live_contribution_restriction_state CHECK (
        revision < 9223372036854775807 OR restricted = TRUE
    )
);

ALTER TABLE signal_command_grant
    ADD COLUMN restriction_revision BIGINT NOT NULL DEFAULT 0
        CHECK (restriction_revision >= 0);
