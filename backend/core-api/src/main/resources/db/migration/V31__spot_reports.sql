-- ADR 0072 / POSTS_AND_SIGNALS_SPEC: reports on Spot posts and signal summaries (ADR 0064
-- mechanics) and room-scoped Block. A reporter row (7 days) holds the account; the reporter-free
-- group row (30 days from its latest report) holds only per-reason counts for the moderator queue.
-- Account deletion removes reporter rows and hidden-alias rows and deliberately keeps group counts.

-- A reported item is one incident: a post, or a signal summary over the signals current when it
-- was reported. window_start is the post's creation time, or the earliest current signal's.
CREATE TABLE spot_report (
    reporter_id UUID NOT NULL REFERENCES routiqo_account(id) ON DELETE CASCADE,
    request_id UUID NOT NULL CHECK (request_id <> '00000000-0000-0000-0000-000000000000'),
    item_ref UUID NOT NULL,
    item_kind TEXT NOT NULL CHECK (item_kind IN ('POST', 'SUMMARY')),
    window_start TIMESTAMPTZ NOT NULL,
    reason TEXT NOT NULL CHECK (reason IN ('false_alarm', 'abuse', 'spam', 'personal_data', 'unsafe')),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at = created_at + INTERVAL '7 days'),
    review_sequence BIGINT GENERATED ALWAYS AS IDENTITY,
    PRIMARY KEY (reporter_id, request_id),
    UNIQUE (reporter_id, item_ref, window_start)
);
CREATE INDEX spot_report_quota ON spot_report (reporter_id, created_at);
CREATE INDEX spot_report_expiry ON spot_report (expires_at);

CREATE TABLE spot_report_group (
    item_ref UUID NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    spot_id UUID NOT NULL,
    item_kind TEXT NOT NULL CHECK (item_kind IN ('POST', 'SUMMARY')),
    false_alarm INTEGER NOT NULL DEFAULT 0 CHECK (false_alarm >= 0),
    abuse INTEGER NOT NULL DEFAULT 0 CHECK (abuse >= 0),
    spam INTEGER NOT NULL DEFAULT 0 CHECK (spam >= 0),
    personal_data INTEGER NOT NULL DEFAULT 0 CHECK (personal_data >= 0),
    unsafe INTEGER NOT NULL DEFAULT 0 CHECK (unsafe >= 0),
    latest TIMESTAMPTZ NOT NULL,
    latest_sequence BIGINT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at = latest + INTERVAL '30 days'),
    PRIMARY KEY (item_ref, window_start),
    CONSTRAINT spot_report_group_counted CHECK (
        false_alarm + abuse + spam + personal_data + unsafe > 0)
);
-- Severity first for the step 4 moderator queue: unsafe and abuse before the rest, then oldest.
CREATE INDEX spot_report_group_queue ON spot_report_group (
    ((unsafe + abuse) > 0) DESC, latest_sequence);
CREATE INDEX spot_report_group_expiry ON spot_report_group (expires_at);

-- Posts and signals kept as evidence: fixed 30 days from the first report naming them; later
-- reports never extend it.
CREATE TABLE spot_report_evidence (
    ref UUID PRIMARY KEY,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX spot_report_evidence_expiry ON spot_report_evidence (expires_at);

-- Block hides the blocked post's alias in its room only (a Spot on one Kolkata day), so blocking
-- never reveals the same author's posts or votes elsewhere. No account ID of the author is kept.
CREATE TABLE spot_hidden_alias (
    blocker_id UUID NOT NULL REFERENCES routiqo_account(id) ON DELETE CASCADE,
    spot_id UUID NOT NULL,
    room_day DATE NOT NULL,
    alias TEXT NOT NULL CHECK (char_length(alias) BETWEEN 3 AND 40),
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (blocker_id, spot_id, room_day, alias)
);
CREATE INDEX spot_hidden_alias_expiry ON spot_hidden_alias (room_day);
