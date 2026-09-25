-- ADR 0062: one explicit, owner-only planning copy per account. Deleted with the account.
CREATE TABLE account_planning_copy (
    account_id UUID PRIMARY KEY REFERENCES routiqo_account(id) ON DELETE CASCADE,
    plans JSONB NOT NULL
        CHECK (jsonb_typeof(plans) = 'array' AND jsonb_array_length(plans) <= 100),
    saved JSONB NOT NULL
        CHECK (jsonb_typeof(saved) = 'array' AND jsonb_array_length(saved) <= 100),
    document_bytes INTEGER NOT NULL CHECK (document_bytes BETWEEN 1 AND 262144),
    version BIGINT NOT NULL CHECK (version BETWEEN 1 AND 9007199254740991),
    updated_at TIMESTAMPTZ NOT NULL,
    latest_mutation_id UUID NOT NULL
);
