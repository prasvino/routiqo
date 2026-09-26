-- ADR 0072 / POSTS_AND_SIGNALS_SPEC: reports on Spot posts and signal summaries, using the ADR 0064
-- mechanics. A reporter row (7 days) holds the account; the reporter-free group row (30 days from the
-- latest report) holds only per-reason counts for the moderator queue. Account deletion removes the
-- reporter rows and deliberately leaves the group counts.

CREATE TABLE spot_report (
    reporter_id UUID NOT NULL REFERENCES routiqo_account(id) ON DELETE CASCADE,
    request_id UUID NOT NULL CHECK (request_id <> '00000000-0000-0000-0000-000000000000'),
    item_ref UUID NOT NULL,
    item_kind TEXT NOT NULL CHECK (item_kind IN ('POST', 'SUMMARY')),
    reason TEXT NOT NULL CHECK (reason IN ('false_alarm', 'abuse', 'spam', 'personal_data', 'unsafe')),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at = created_at + INTERVAL '7 days'),
    review_sequence BIGINT GENERATED ALWAYS AS IDENTITY,
    PRIMARY KEY (reporter_id, request_id),
    UNIQUE (reporter_id, item_ref)
);
CREATE INDEX spot_report_quota ON spot_report (reporter_id, created_at);
CREATE INDEX spot_report_expiry ON spot_report (expires_at);

CREATE TABLE spot_report_group (
    item_ref UUID PRIMARY KEY,
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
    CONSTRAINT spot_report_group_counted CHECK (
        false_alarm + abuse + spam + personal_data + unsafe > 0)
);
-- Severity first for the step 4 moderator queue: unsafe and abuse before the rest, then oldest.
CREATE INDEX spot_report_group_queue ON spot_report_group (
    ((unsafe + abuse) > 0) DESC, latest_sequence);
CREATE INDEX spot_report_group_expiry ON spot_report_group (expires_at);
