CREATE TABLE journey (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('COMMUTE', 'TRIP')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'COMPLETED')),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT journey_completion_valid CHECK (
        (status = 'ACTIVE' AND completed_at IS NULL)
        OR (status = 'COMPLETED' AND completed_at IS NOT NULL AND completed_at >= started_at)
    )
);

CREATE UNIQUE INDEX journey_one_active_per_owner ON journey (owner_id) WHERE status = 'ACTIVE';
CREATE INDEX journey_owner_history ON journey (owner_id, started_at DESC, id DESC);
