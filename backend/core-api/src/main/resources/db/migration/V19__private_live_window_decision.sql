-- Internal evaluation record only. No HTTP reader or public delivery is connected.
-- The candidate release protocol is unapproved (ADR 0051).
CREATE TABLE private_live_window_decision (
    anchor_id UUID NOT NULL,
    category VARCHAR(24) NOT NULL CHECK (category IN
        ('QUEUE','TRAFFIC','PARKING','FOOD_QUEUE','RESTROOM')),
    window_start TIMESTAMPTZ NOT NULL,
    decision VARCHAR(16) NOT NULL CHECK (decision IN ('CANDIDATE','NO_RELEASE')),
    signal_value VARCHAR(40),
    decided_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (anchor_id, category, window_start),
    CONSTRAINT private_live_decision_value CHECK (
        (decision = 'CANDIDATE' AND signal_value IS NOT NULL)
        OR (decision = 'NO_RELEASE' AND signal_value IS NULL)),
    CONSTRAINT private_live_decision_window CHECK (
        isfinite(window_start) AND isfinite(decided_at) AND isfinite(expires_at)
        AND window_start = date_trunc('minute', window_start)
        AND EXTRACT(MINUTE FROM window_start)::INT % 5 = 0
        AND decided_at >= window_start + INTERVAL '5 minutes'
        AND expires_at = window_start + INTERVAL '15 minutes')
);
CREATE INDEX private_live_decision_expiry ON private_live_window_decision(expires_at);
