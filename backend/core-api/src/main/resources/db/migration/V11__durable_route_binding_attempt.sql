CREATE TABLE route_binding_attempt (
    actor_id UUID PRIMARY KEY REFERENCES routiqo_account(id) ON DELETE CASCADE,
    attempt_id UUID NOT NULL UNIQUE CHECK (attempt_id <> '00000000-0000-0000-0000-000000000000'),
    journey_id UUID NOT NULL,
    consent_generation BIGINT NOT NULL CHECK (consent_generation >= 0),
    catalog_version UUID NOT NULL CHECK (catalog_version <> '00000000-0000-0000-0000-000000000000'),
    expected_context_id UUID CHECK (
        expected_context_id IS NULL OR expected_context_id <> '00000000-0000-0000-0000-000000000000'),
    issued_at TIMESTAMPTZ NOT NULL,
    deadline TIMESTAMPTZ NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('PENDING', 'CONSUMED', 'INVALIDATED')),
    CONSTRAINT route_binding_attempt_owned_journey FOREIGN KEY (journey_id, actor_id)
        REFERENCES journey(id, owner_id) ON DELETE CASCADE,
    CONSTRAINT route_binding_attempt_times CHECK (
        isfinite(issued_at) AND isfinite(deadline)
        AND deadline = issued_at + INTERVAL '90 seconds')
);
