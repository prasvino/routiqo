-- V3 canonical decisions are independent of owner rows and never cascade with a candidate.
CREATE TABLE community_traffic_decision_v3 (
    schema_version SMALLINT NOT NULL DEFAULT 3 CHECK (schema_version = 3),
    catalog_version UUID NOT NULL,
    anchor_id UUID NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    outcome VARCHAR(12) NOT NULL CHECK (outcome IN ('NO_OUTPUT', 'PUBLISHED')),
    traffic_value VARCHAR(24) CHECK (traffic_value IN
        ('TRAFFIC_MOVING', 'TRAFFIC_SLOW', 'TRAFFIC_VERY_SLOW', 'TRAFFIC_STOPPED')),
    decided_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (schema_version, catalog_version, anchor_id, window_start),
    CHECK ((outcome = 'NO_OUTPUT' AND traffic_value IS NULL)
        OR (outcome = 'PUBLISHED' AND traffic_value IS NOT NULL)),
    CHECK (window_start = date_trunc('minute', window_start)
        AND extract(minute FROM window_start)::INTEGER % 5 = 0
        AND expires_at = window_start + INTERVAL '15 minutes'
        AND decided_at >= window_start + INTERVAL '5 minutes')
);
CREATE INDEX community_traffic_decision_v3_expiry
    ON community_traffic_decision_v3(expires_at, anchor_id, window_start);

CREATE TABLE community_traffic_projection_v3 (
    ref UUID PRIMARY KEY,
    schema_version SMALLINT NOT NULL DEFAULT 3 CHECK (schema_version = 3),
    catalog_version UUID NOT NULL,
    anchor_id UUID NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    area_label VARCHAR(80) NOT NULL,
    traffic_value VARCHAR(24) NOT NULL CHECK (traffic_value IN
        ('TRAFFIC_MOVING', 'TRAFFIC_SLOW', 'TRAFFIC_VERY_SLOW', 'TRAFFIC_STOPPED')),
    expires_at TIMESTAMPTZ NOT NULL,
    suppressed_at TIMESTAMPTZ,
    FOREIGN KEY (schema_version, catalog_version, anchor_id, window_start)
        REFERENCES community_traffic_decision_v3(schema_version, catalog_version, anchor_id, window_start)
        ON DELETE RESTRICT,
    UNIQUE (schema_version, catalog_version, anchor_id, window_start),
    CHECK (expires_at = window_start + INTERVAL '15 minutes')
);
CREATE INDEX community_traffic_projection_v3_read
    ON community_traffic_projection_v3(anchor_id, expires_at)
    WHERE suppressed_at IS NULL;
CREATE INDEX community_traffic_projection_v3_expiry
    ON community_traffic_projection_v3(expires_at, ref);

CREATE TABLE community_traffic_report_v3 (
    actor_id UUID NOT NULL REFERENCES routiqo_account(id) ON DELETE CASCADE,
    request_id UUID NOT NULL,
    ref UUID NOT NULL,
    reason VARCHAR(16) NOT NULL CHECK (reason IN ('INACCURATE', 'UNSAFE', 'SPAM')),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (actor_id, request_id),
    UNIQUE (actor_id, ref),
    CHECK (expires_at = created_at + INTERVAL '720 hours')
);
CREATE INDEX community_traffic_report_v3_expiry
    ON community_traffic_report_v3(expires_at, actor_id, request_id);

CREATE TABLE community_traffic_suppression_audit_v3 (
    operator_id UUID NOT NULL,
    request_id UUID NOT NULL,
    ref UUID NOT NULL,
    reason VARCHAR(24) NOT NULL CHECK (reason IN ('INACCURATE', 'UNSAFE', 'SPAM', 'POLICY')),
    occurred_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (operator_id, request_id),
    UNIQUE (ref),
    CHECK (expires_at = occurred_at + INTERVAL '720 hours')
);
CREATE INDEX community_traffic_suppression_audit_v3_expiry
    ON community_traffic_suppression_audit_v3(expires_at, operator_id, request_id);

ALTER TABLE moderation_operator_grant DROP CONSTRAINT moderation_operator_grant_permission_check;
ALTER TABLE moderation_operator_grant ADD CONSTRAINT moderation_operator_grant_permission_check
    CHECK (permission IN ('restrict', 'restore', 'traffic_suppress'));
