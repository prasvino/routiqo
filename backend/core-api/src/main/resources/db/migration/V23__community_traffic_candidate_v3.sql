-- V3 has its own purpose, account slot and debit. V18/V21/V22 are never migrated.
CREATE TABLE community_traffic_candidate_v3 (
    candidate_id UUID PRIMARY KEY,
    actor_id UUID NOT NULL REFERENCES routiqo_account(id) ON DELETE CASCADE,
    command_id UUID NOT NULL,
    request_id UUID NOT NULL,
    journey_id UUID NOT NULL,
    anchor_id UUID NOT NULL,
    traffic_value TEXT NOT NULL CHECK (traffic_value IN
        ('TRAFFIC_MOVING', 'TRAFFIC_SLOW', 'TRAFFIC_VERY_SLOW', 'TRAFFIC_STOPPED')),
    window_start TIMESTAMPTZ NOT NULL,
    catalog_version UUID NOT NULL,
    consent_generation BIGINT NOT NULL CHECK (consent_generation >= 0),
    state TEXT NOT NULL CHECK (state IN ('ACTIVE', 'STOPPED')),
    received_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    stopped_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    UNIQUE (actor_id, command_id),
    UNIQUE (actor_id, request_id),
    UNIQUE (actor_id, window_start),
    CHECK (mod(extract(epoch FROM window_start), 300) = 0),
    CHECK (received_at >= window_start AND received_at < window_start + interval '5 minutes'),
    CHECK (expires_at = window_start + interval '24 hours 5 minutes'),
    CHECK ((state = 'ACTIVE' AND stopped_at IS NULL) OR
           (state = 'STOPPED' AND stopped_at IS NOT NULL))
);
CREATE INDEX community_traffic_candidate_snapshot_v3
    ON community_traffic_candidate_v3(anchor_id, window_start, traffic_value)
    WHERE state = 'ACTIVE';
CREATE INDEX community_traffic_candidate_expiry_v3
    ON community_traffic_candidate_v3(expires_at);
CREATE INDEX community_traffic_candidate_owner_v3
    ON community_traffic_candidate_v3(actor_id, created_at DESC);

CREATE TABLE community_traffic_daily_debit_v3 (
    actor_id UUID NOT NULL REFERENCES routiqo_account(id) ON DELETE CASCADE,
    utc_day DATE NOT NULL,
    used INTEGER NOT NULL CHECK (used BETWEEN 1 AND 12),
    PRIMARY KEY (actor_id, utc_day)
);
