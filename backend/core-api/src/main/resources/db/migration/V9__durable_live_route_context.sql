CREATE FUNCTION routiqo_uuid_array_is_distinct(values_to_check UUID[])
RETURNS BOOLEAN
LANGUAGE SQL
IMMUTABLE
STRICT
PARALLEL SAFE
AS $$
    SELECT cardinality(values_to_check) = count(DISTINCT value)
    FROM unnest(values_to_check) AS value
$$;

CREATE TABLE live_route_context (
    actor_id UUID PRIMARY KEY REFERENCES routiqo_account(id) ON DELETE CASCADE,
    journey_id UUID NOT NULL,
    context_id UUID NOT NULL UNIQUE CHECK (context_id <> '00000000-0000-0000-0000-000000000000'),
    revision BIGINT NOT NULL CHECK (revision >= 0),
    anchor_ids UUID[] NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT live_route_context_owned_journey FOREIGN KEY (journey_id, actor_id)
        REFERENCES journey(id, owner_id) ON DELETE CASCADE,
    CONSTRAINT live_route_context_anchor_count CHECK (cardinality(anchor_ids) BETWEEN 1 AND 128),
    CONSTRAINT live_route_context_anchor_dimension CHECK (array_ndims(anchor_ids) = 1),
    CONSTRAINT live_route_context_anchor_values CHECK (
        array_position(anchor_ids, NULL) IS NULL
        AND array_position(anchor_ids, '00000000-0000-0000-0000-000000000000'::UUID) IS NULL
        AND routiqo_uuid_array_is_distinct(anchor_ids)
    ),
    CONSTRAINT live_route_context_finite_times CHECK (isfinite(issued_at) AND isfinite(expires_at)),
    CONSTRAINT live_route_context_lifetime CHECK (
        expires_at > issued_at AND expires_at <= issued_at + INTERVAL '24 hours'
    )
);

CREATE INDEX live_route_context_expiry
    ON live_route_context(expires_at, actor_id);
