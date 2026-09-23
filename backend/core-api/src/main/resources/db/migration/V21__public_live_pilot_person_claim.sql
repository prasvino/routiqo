-- Pilot-wide, nonrefundable person contribution reservation. No account FK or raw identity.
-- A pilot is provisioned explicitly; this migration creates no active pilot.
CREATE TABLE public_live_pilot (
    pilot_id UUID PRIMARY KEY CHECK (pilot_id <> '00000000-0000-0000-0000-000000000000'),
    starts_at TIMESTAMPTZ NOT NULL CHECK (isfinite(starts_at)),
    ends_at TIMESTAMPTZ NOT NULL CHECK (isfinite(ends_at)),
    retain_until TIMESTAMPTZ NOT NULL CHECK (isfinite(retain_until)),
    CONSTRAINT public_live_pilot_fixed_window CHECK (
        ends_at = starts_at + INTERVAL '720 hours'
        AND retain_until = ends_at + INTERVAL '720 hours')
);

CREATE TABLE public_live_person_claim (
    pilot_id UUID NOT NULL REFERENCES public_live_pilot(pilot_id) ON DELETE RESTRICT,
    person_ref UUID NOT NULL CHECK (person_ref <> '00000000-0000-0000-0000-000000000000'),
    claim_request_id UUID NOT NULL CHECK
        (claim_request_id <> '00000000-0000-0000-0000-000000000000'),
    public_key VARCHAR(128) NOT NULL CHECK
        (public_key ~ '^[A-Za-z0-9:._-]{1,128}$'),
    claimed_at TIMESTAMPTZ NOT NULL CHECK (isfinite(claimed_at)),
    PRIMARY KEY (pilot_id, person_ref)
);

-- Moving a pilot interval or recycling its id would alter the lifetime of every claim.
CREATE FUNCTION reject_public_live_pilot_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Public LIVE pilot is immutable';
END;
$$;
CREATE TRIGGER public_live_pilot_immutable
    BEFORE UPDATE OR DELETE ON public_live_pilot
    FOR EACH ROW EXECUTE FUNCTION reject_public_live_pilot_mutation();
