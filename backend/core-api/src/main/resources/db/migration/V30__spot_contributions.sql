-- ADR 0071 / POSTS_AND_SIGNALS_SPEC: public one-tap signals and short posts on Spots, votes,
-- per-room aliases, highlights and the contribution rate ledger. Every row tied to an account
-- cascades on account deletion. No coordinates are stored: content is tied to a Spot, not to the
-- contributor's position.

-- One opaque, stable reference per Spot, category and value, used as the summary's ref.
CREATE TABLE spot_signal_group (
    ref UUID PRIMARY KEY CHECK (ref <> '00000000-0000-0000-0000-000000000000'),
    spot_id UUID NOT NULL,
    category TEXT NOT NULL CHECK (category IN ('traffic', 'queue', 'food', 'fuel', 'restroom')),
    value TEXT NOT NULL CHECK (value ~ '^[a-z0-9_]{1,16}$'),
    UNIQUE (spot_id, category, value)
);

CREATE TABLE spot_signal (
    ref UUID PRIMARY KEY CHECK (ref <> '00000000-0000-0000-0000-000000000000'),
    actor_id UUID NOT NULL REFERENCES routiqo_account(id) ON DELETE CASCADE,
    journey_id UUID NOT NULL,
    spot_id UUID NOT NULL,
    catalog_version UUID NOT NULL,
    group_ref UUID NOT NULL REFERENCES spot_signal_group(ref),
    category TEXT NOT NULL CHECK (category IN ('traffic', 'queue', 'food', 'fuel', 'restroom')),
    value TEXT NOT NULL CHECK (value ~ '^[a-z0-9_]{1,16}$'),
    captured_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    effective_created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    max_expires_at TIMESTAMPTZ NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('ACTIVE', 'SUPERSEDED', 'EXPIRED_EARLY')),
    ended_at TIMESTAMPTZ,
    CONSTRAINT spot_signal_owned_journey FOREIGN KEY (journey_id, actor_id)
        REFERENCES journey(id, owner_id) ON DELETE CASCADE,
    CONSTRAINT spot_signal_times CHECK (
        effective_created_at <= received_at AND effective_created_at <= captured_at
        AND expires_at <= max_expires_at AND (state = 'ACTIVE') = (ended_at IS NULL))
);
-- One current signal per account, Spot and category.
CREATE UNIQUE INDEX spot_signal_one_active ON spot_signal (actor_id, spot_id, category)
    WHERE state = 'ACTIVE';
CREATE INDEX spot_signal_by_spot ON spot_signal (spot_id, expires_at) WHERE state = 'ACTIVE';
CREATE INDEX spot_signal_by_group ON spot_signal (group_ref) WHERE state = 'ACTIVE';
CREATE INDEX spot_signal_expiry ON spot_signal (expires_at);

CREATE TABLE spot_post (
    ref UUID PRIMARY KEY CHECK (ref <> '00000000-0000-0000-0000-000000000000'),
    actor_id UUID NOT NULL REFERENCES routiqo_account(id) ON DELETE CASCADE,
    journey_id UUID NOT NULL,
    spot_id UUID NOT NULL,
    catalog_version UUID NOT NULL,
    type TEXT NOT NULL CHECK (type IN ('traffic', 'place')),
    text TEXT NOT NULL CHECK (char_length(text) BETWEEN 1 AND 200),
    room_day DATE NOT NULL,
    alias TEXT NOT NULL CHECK (char_length(alias) BETWEEN 3 AND 40),
    captured_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    effective_created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    max_expires_at TIMESTAMPTZ NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('ACTIVE', 'DELETED', 'EXPIRED_EARLY')),
    ended_at TIMESTAMPTZ,
    -- Set once maintenance has considered the expired post for a highlight, so it is never retried.
    highlight_checked BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT spot_post_owned_journey FOREIGN KEY (journey_id, actor_id)
        REFERENCES journey(id, owner_id) ON DELETE CASCADE,
    CONSTRAINT spot_post_times CHECK (
        effective_created_at <= received_at AND effective_created_at <= captured_at
        AND expires_at <= max_expires_at AND (state = 'ACTIVE') = (ended_at IS NULL))
);
CREATE INDEX spot_post_by_spot ON spot_post (spot_id, effective_created_at DESC) WHERE state = 'ACTIVE';
CREATE INDEX spot_post_expiry ON spot_post (expires_at);
CREATE INDEX spot_post_highlight_pending ON spot_post (expires_at)
    WHERE type = 'place' AND state = 'ACTIVE' AND NOT highlight_checked;
CREATE INDEX spot_post_room ON spot_post (spot_id, room_day);

-- Exact-fingerprint idempotency for app-generated client keys; kept 48 hours.
CREATE TABLE spot_contribution_key (
    actor_id UUID NOT NULL REFERENCES routiqo_account(id) ON DELETE CASCADE,
    client_key UUID NOT NULL CHECK (client_key <> '00000000-0000-0000-0000-000000000000'),
    kind TEXT NOT NULL CHECK (kind IN ('SIGNAL', 'POST')),
    ref UUID NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (actor_id, client_key)
);
CREATE INDEX spot_contribution_key_expiry ON spot_contribution_key (created_at);
CREATE INDEX spot_contribution_key_by_ref ON spot_contribution_key (ref);

-- One vote per account per item (a post ref or a signal-summary group ref); later votes replace.
CREATE TABLE spot_vote (
    item_ref UUID NOT NULL,
    actor_id UUID NOT NULL REFERENCES routiqo_account(id) ON DELETE CASCADE,
    kind TEXT NOT NULL CHECK (kind IN ('STILL_TRUE', 'NO_LONGER_TRUE')),
    voted_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (item_ref, actor_id)
);
CREATE INDEX spot_vote_expiry ON spot_vote (voted_at);

-- A room is a Spot on one Asia/Kolkata calendar day; aliases never leave their room.
CREATE TABLE spot_alias (
    spot_id UUID NOT NULL,
    room_day DATE NOT NULL,
    actor_id UUID NOT NULL REFERENCES routiqo_account(id) ON DELETE CASCADE,
    alias TEXT NOT NULL CHECK (char_length(alias) BETWEEN 3 AND 40),
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (spot_id, room_day, actor_id),
    UNIQUE (spot_id, room_day, alias)
);

-- Traveller tips kept after a place post expires with at least two "Still true" votes.
CREATE TABLE spot_highlight (
    ref UUID PRIMARY KEY,
    spot_id UUID NOT NULL,
    source_post_ref UUID NOT NULL UNIQUE,
    actor_id UUID NOT NULL REFERENCES routiqo_account(id) ON DELETE CASCADE,
    text TEXT NOT NULL CHECK (char_length(text) BETWEEN 1 AND 200),
    still_true INTEGER NOT NULL CHECK (still_true >= 2),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at = created_at + INTERVAL '30 days')
);
CREATE INDEX spot_highlight_by_spot ON spot_highlight (spot_id, expires_at);

-- Rolling per-account contribution budgets (signals, posts, votes); purged after 24 hours.
CREATE TABLE spot_contribution_ledger (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id UUID NOT NULL REFERENCES routiqo_account(id) ON DELETE CASCADE,
    action TEXT NOT NULL CHECK (action IN ('SIGNAL', 'POST', 'VOTE')),
    spot_id UUID,
    category TEXT,
    charged_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX spot_contribution_ledger_by_actor ON spot_contribution_ledger (actor_id, action, charged_at);
CREATE INDEX spot_contribution_ledger_expiry ON spot_contribution_ledger (charged_at);
