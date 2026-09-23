-- Internal V2 foundation only. No existing V18 intent is copied or converted.
-- A manifest is provisioned explicitly and cannot change after its first insertion.
CREATE TABLE public_live_pilot_manifest (
    pilot_id UUID PRIMARY KEY REFERENCES public_live_pilot(pilot_id) ON DELETE RESTRICT,
    catalog_version UUID NOT NULL CHECK (catalog_version <> '00000000-0000-0000-0000-000000000000'),
    anchor_ids UUID[] NOT NULL CHECK (cardinality(anchor_ids) BETWEEN 1 AND 10),
    CHECK (array_position(anchor_ids, NULL) IS NULL),
    CHECK (array_position(anchor_ids, '00000000-0000-0000-0000-000000000000'::UUID) IS NULL)
);

CREATE FUNCTION validate_public_live_manifest_window() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM public_live_pilot p
        WHERE p.pilot_id = NEW.pilot_id
          AND mod(extract(epoch FROM p.starts_at), 300) = 0
          AND mod(extract(epoch FROM p.ends_at), 300) = 0
    ) THEN
        RAISE EXCEPTION 'Public LIVE pilot requires aligned windows';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER public_live_manifest_window
    BEFORE INSERT ON public_live_pilot_manifest
    FOR EACH ROW EXECUTE FUNCTION validate_public_live_manifest_window();

CREATE FUNCTION reject_public_live_manifest_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Public LIVE manifest is immutable';
END;
$$;
CREATE TRIGGER public_live_manifest_immutable
    BEFORE UPDATE OR DELETE ON public_live_pilot_manifest
    FOR EACH ROW EXECUTE FUNCTION reject_public_live_manifest_mutation();

-- The claim remains the sole pilot/person/key reservation. This row contains only
-- account-scoped recovery identity and a pointer to the private command. Deleting
-- the account removes this link while retaining the nonrefundable V21 claim/key.
CREATE TABLE public_live_frozen_share_v2 (
    pilot_id UUID NOT NULL,
    person_ref UUID NOT NULL,
    owner_actor_id UUID NOT NULL REFERENCES routiqo_account(id) ON DELETE CASCADE,
    journey_id UUID NOT NULL CHECK (journey_id <> '00000000-0000-0000-0000-000000000000'),
    command_id UUID NOT NULL CHECK (command_id <> '00000000-0000-0000-0000-000000000000'),
    PRIMARY KEY (pilot_id, person_ref),
    UNIQUE (pilot_id, owner_actor_id, command_id),
    FOREIGN KEY (pilot_id, person_ref) REFERENCES public_live_person_claim(pilot_id, person_ref)
        ON DELETE CASCADE
);

CREATE FUNCTION protect_public_live_frozen_share_v2() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'Frozen public Share is immutable';
    END IF;
    IF EXISTS (SELECT 1 FROM routiqo_account a WHERE a.id = OLD.owner_actor_id)
       AND NOT EXISTS (
        SELECT 1 FROM public_live_pilot p
        WHERE p.pilot_id = OLD.pilot_id AND p.retain_until <= clock_timestamp()
    ) THEN
        RAISE EXCEPTION 'Frozen public Share is retained';
    END IF;
    RETURN OLD;
END;
$$;
CREATE TRIGGER public_live_frozen_share_v2_protected
    BEFORE UPDATE OR DELETE ON public_live_frozen_share_v2
    FOR EACH ROW EXECUTE FUNCTION protect_public_live_frozen_share_v2();
