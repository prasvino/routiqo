-- Explicit, per-receipt purpose change. This is private candidate storage, not a public feed.
-- The pseudonymous slot survives account deletion briefly, so deletion/reassignment cannot
-- create a second effective person in the same window. No account or receipt ID is retained.
CREATE TABLE public_person_window_slot (
    person_ref UUID NOT NULL CHECK (person_ref <> '00000000-0000-0000-0000-000000000000'),
    anchor_id UUID NOT NULL,
    category VARCHAR(24) NOT NULL CHECK (category IN
        ('QUEUE','TRAFFIC','PARKING','FOOD_QUEUE','RESTROOM')),
    window_start TIMESTAMPTZ NOT NULL CHECK (isfinite(window_start)),
    expires_at TIMESTAMPTZ NOT NULL CHECK (isfinite(expires_at)),
    PRIMARY KEY (person_ref, anchor_id, category, window_start),
    CONSTRAINT public_person_slot_lifetime CHECK (
        expires_at = window_start + INTERVAL '24 hours')
);
CREATE INDEX public_person_slot_expiry ON public_person_window_slot(expires_at,
    person_ref, anchor_id, category, window_start);

CREATE TABLE public_signal_intent (
    actor_id UUID NOT NULL REFERENCES routiqo_account(id) ON DELETE CASCADE,
    command_id UUID NOT NULL,
    journey_id UUID NOT NULL,
    person_ref UUID NOT NULL CHECK (person_ref <> '00000000-0000-0000-0000-000000000000'),
    verification_revision BIGINT NOT NULL CHECK (verification_revision > 0),
    restriction_revision BIGINT NOT NULL CHECK (restriction_revision >= 0),
    anchor_id UUID NOT NULL,
    category VARCHAR(24) NOT NULL CHECK (category IN
        ('QUEUE','TRAFFIC','PARKING','FOOD_QUEUE','RESTROOM')),
    signal_value VARCHAR(40) NOT NULL,
    window_start TIMESTAMPTZ NOT NULL CHECK (isfinite(window_start)),
    received_at TIMESTAMPTZ NOT NULL CHECK (isfinite(received_at)),
    evidence_expires_at TIMESTAMPTZ NOT NULL CHECK (isfinite(evidence_expires_at)),
    shared_at TIMESTAMPTZ NOT NULL CHECK (isfinite(shared_at)),
    share_request_id UUID NOT NULL CHECK
        (share_request_id <> '00000000-0000-0000-0000-000000000000'),
    stopped_at TIMESTAMPTZ CHECK (stopped_at IS NULL OR isfinite(stopped_at)),
    state VARCHAR(12) NOT NULL CHECK (state IN ('ACTIVE','STOPPED')),
    PRIMARY KEY (actor_id, command_id),
    CONSTRAINT public_signal_private_receipt FOREIGN KEY (actor_id, command_id)
        REFERENCES quick_signal_receipt(actor_id, command_id) ON DELETE CASCADE,
    CONSTRAINT public_signal_times CHECK (
        window_start = date_trunc('minute', window_start)
        AND EXTRACT(MINUTE FROM window_start)::INT % 5 = 0
        AND received_at >= window_start
        AND received_at < window_start + INTERVAL '5 minutes'
        AND evidence_expires_at > shared_at
        AND shared_at >= received_at
        AND shared_at < window_start + INTERVAL '5 minutes'
        AND ((state = 'ACTIVE' AND stopped_at IS NULL)
          OR (state = 'STOPPED' AND stopped_at IS NOT NULL AND stopped_at >= shared_at))
    )
);
CREATE UNIQUE INDEX public_signal_one_person_slot ON public_signal_intent
    (person_ref, anchor_id, category, window_start);
CREATE INDEX public_signal_window ON public_signal_intent
    (window_start, anchor_id, category, state);
CREATE INDEX public_signal_expiry ON public_signal_intent
    (evidence_expires_at, actor_id, command_id);
