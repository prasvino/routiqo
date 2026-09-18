-- Latest directed intent only. Unblocked revision rows remain to reject stale commands.
CREATE TABLE live_block_edge (
    blocker_id UUID NOT NULL REFERENCES routiqo_account(id) ON DELETE CASCADE
        CHECK (blocker_id <> '00000000-0000-0000-0000-000000000000'),
    target_id UUID NOT NULL REFERENCES routiqo_account(id) ON DELETE CASCADE
        CHECK (target_id <> '00000000-0000-0000-0000-000000000000'),
    revision BIGINT NOT NULL CHECK (revision > 0),
    blocked BOOLEAN NOT NULL,
    PRIMARY KEY (blocker_id, target_id),
    CONSTRAINT live_block_distinct_pair CHECK (blocker_id <> target_id),
    CONSTRAINT live_block_saturation CHECK (
        revision < 9223372036854775807 OR blocked = TRUE)
);
CREATE INDEX live_block_target ON live_block_edge(target_id, blocker_id);
