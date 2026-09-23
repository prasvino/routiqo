-- Bounded owner-only keyset scan for retained Stop handles across devices.
CREATE INDEX public_signal_intent_owner_recovery
    ON public_signal_intent (actor_id, shared_at DESC, command_id DESC);
