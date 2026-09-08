# Event contracts
No externally published domain events exist yet. Before implementing journey/route writes, define versioned schemas with eventId, occurrence time, resource scope and minimal payload. Raw GPS never belongs in public/social events.
Initial planned contracts: JourneyStarted, JourneyCompleted, RouteUpdateCreated, RoomExpired. Presence updates are ephemeral and must not be replayed as historical individual trails.

