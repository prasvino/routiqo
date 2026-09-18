-- Only the account owns these bounded charges. Receipt, grant, journey, and consent
-- deletion must not reset the rolling actor budget. Rows expire logically after one
-- hour; physical removal can lag until a bounded maintenance pass.
CREATE TABLE signal_actor_acceptance (
    actor_id UUID NOT NULL REFERENCES routiqo_account(id) ON DELETE CASCADE,
    slot SMALLINT NOT NULL CHECK (slot BETWEEN 1 AND 20),
    accepted_at TIMESTAMPTZ NOT NULL CHECK (isfinite(accepted_at)),
    anchor_id UUID NOT NULL CHECK (anchor_id <> '00000000-0000-0000-0000-000000000000'),
    category TEXT NOT NULL CHECK (category IN ('QUEUE','TRAFFIC','PARKING','FOOD_QUEUE','RESTROOM')),
    PRIMARY KEY (actor_id, slot)
);
CREATE INDEX signal_actor_acceptance_expiry
    ON signal_actor_acceptance(accepted_at, actor_id, slot);
