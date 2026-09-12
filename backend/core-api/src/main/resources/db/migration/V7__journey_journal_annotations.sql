ALTER TABLE journey
    ADD CONSTRAINT journey_id_owner_unique UNIQUE (id, owner_id);

CREATE TABLE journey_journal_annotation (
    journey_id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES routiqo_account(id) ON DELETE CASCADE,
    title VARCHAR(120) NOT NULL,
    notes VARCHAR(4000) NOT NULL,
    version BIGINT NOT NULL CHECK (version BETWEEN 1 AND 9007199254740991),
    updated_at TIMESTAMPTZ NOT NULL,
    latest_mutation_id UUID NOT NULL,
    CONSTRAINT journal_owned_journey FOREIGN KEY (journey_id, account_id)
        REFERENCES journey(id, owner_id) ON DELETE CASCADE
);
