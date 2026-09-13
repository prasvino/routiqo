CREATE FUNCTION routiqo_text_array_is_distinct(values_to_check TEXT[])
RETURNS BOOLEAN LANGUAGE SQL IMMUTABLE STRICT PARALLEL SAFE AS $$
    SELECT cardinality(values_to_check) = count(DISTINCT value)
    FROM unnest(values_to_check) AS value
$$;

CREATE TABLE signal_command_grant (
    actor_id UUID NOT NULL REFERENCES routiqo_account(id) ON DELETE CASCADE,
    command_id UUID NOT NULL UNIQUE CHECK (command_id <> '00000000-0000-0000-0000-000000000000'),
    journey_id UUID NOT NULL,
    context_id UUID NOT NULL CHECK (context_id <> '00000000-0000-0000-0000-000000000000'),
    route_revision BIGINT NOT NULL CHECK (route_revision >= 0),
    anchor_id UUID NOT NULL CHECK (anchor_id <> '00000000-0000-0000-0000-000000000000'),
    consent_generation BIGINT NOT NULL CHECK (consent_generation >= 0),
    permitted_categories TEXT[] NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('UNUSED', 'CONSUMED')),
    PRIMARY KEY (actor_id, command_id),
    CONSTRAINT signal_grant_owned_journey FOREIGN KEY (journey_id, actor_id)
        REFERENCES journey(id, owner_id) ON DELETE CASCADE,
    CONSTRAINT signal_grant_categories CHECK (
        cardinality(permitted_categories) BETWEEN 1 AND 5
        AND array_ndims(permitted_categories) = 1
        AND array_position(permitted_categories, NULL) IS NULL
        AND permitted_categories <@ ARRAY['QUEUE','TRAFFIC','PARKING','FOOD_QUEUE','RESTROOM']::TEXT[]
        AND routiqo_text_array_is_distinct(permitted_categories)
    ),
    CONSTRAINT signal_grant_times CHECK (
        isfinite(issued_at) AND isfinite(expires_at)
        AND expires_at > issued_at AND expires_at <= issued_at + INTERVAL '90 seconds'
    )
);
CREATE INDEX signal_grant_expiry ON signal_command_grant(expires_at, actor_id, command_id);

CREATE TABLE quick_signal_receipt (
    actor_id UUID NOT NULL REFERENCES routiqo_account(id) ON DELETE CASCADE,
    command_id UUID NOT NULL UNIQUE CHECK (command_id <> '00000000-0000-0000-0000-000000000000'),
    journey_id UUID NOT NULL,
    anchor_id UUID NOT NULL CHECK (anchor_id <> '00000000-0000-0000-0000-000000000000'),
    signal_value TEXT NOT NULL CHECK (signal_value IN (
        'QUEUE_UNDER_5','QUEUE_5_TO_15','QUEUE_15_TO_30','QUEUE_OVER_30',
        'TRAFFIC_MOVING','TRAFFIC_SLOW','TRAFFIC_VERY_SLOW','TRAFFIC_STOPPED',
        'PARKING_AVAILABLE','PARKING_FILLING','PARKING_FULL',
        'FOOD_QUEUE_NONE','FOOD_QUEUE_SHORT','FOOD_QUEUE_LONG',
        'RESTROOM_USABLE','RESTROOM_BUSY','RESTROOM_PROBLEM_REPORTED')),
    category TEXT NOT NULL CHECK (category IN ('QUEUE','TRAFFIC','PARKING','FOOD_QUEUE','RESTROOM')),
    consent_generation BIGINT NOT NULL CHECK (consent_generation >= 0),
    context_id UUID NOT NULL CHECK (context_id <> '00000000-0000-0000-0000-000000000000'),
    route_revision BIGINT NOT NULL CHECK (route_revision >= 0),
    received_at TIMESTAMPTZ NOT NULL,
    evidence_expires_at TIMESTAMPTZ NOT NULL,
    retain_until TIMESTAMPTZ NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('ACTIVE','WITHDRAWN','SUPERSEDED')),
    PRIMARY KEY (actor_id, command_id),
    CONSTRAINT signal_receipt_owned_journey FOREIGN KEY (journey_id, actor_id)
        REFERENCES journey(id, owner_id) ON DELETE CASCADE,
    CONSTRAINT signal_receipt_value_category CHECK (
        (signal_value LIKE 'QUEUE_%' AND category = 'QUEUE') OR
        (signal_value LIKE 'TRAFFIC_%' AND category = 'TRAFFIC') OR
        (signal_value LIKE 'PARKING_%' AND category = 'PARKING') OR
        (signal_value LIKE 'FOOD_QUEUE_%' AND category = 'FOOD_QUEUE') OR
        (signal_value LIKE 'RESTROOM_%' AND category = 'RESTROOM')),
    CONSTRAINT signal_receipt_times CHECK (
        isfinite(received_at) AND isfinite(evidence_expires_at) AND isfinite(retain_until)
        AND evidence_expires_at > received_at
        AND evidence_expires_at <= received_at + INTERVAL '15 minutes'
        AND retain_until >= evidence_expires_at
        AND retain_until <= received_at + INTERVAL '24 hours')
);
CREATE UNIQUE INDEX quick_signal_active_slot
    ON quick_signal_receipt(actor_id, anchor_id, category) WHERE state = 'ACTIVE';
CREATE INDEX quick_signal_receipt_expiry
    ON quick_signal_receipt(retain_until, actor_id, command_id);

CREATE TABLE signal_actor_budget (
    actor_id UUID NOT NULL REFERENCES routiqo_account(id) ON DELETE CASCADE,
    action TEXT NOT NULL CHECK (action IN ('GRANT','ACCEPT')),
    bucket_start TIMESTAMPTZ NOT NULL,
    used_count SMALLINT NOT NULL CHECK (used_count BETWEEN 1 AND 10),
    PRIMARY KEY (actor_id, action),
    CONSTRAINT signal_budget_minute CHECK (
        isfinite(bucket_start) AND date_trunc('minute', bucket_start) = bucket_start)
);
