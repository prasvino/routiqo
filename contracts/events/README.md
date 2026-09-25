# Event contracts
No externally published domain events exist yet. Before implementing journey/route writes, define versioned schemas with eventId, occurrence time, resource scope and minimal payload. Raw GPS never belongs in public/social events.
Initial planned contracts (names provisional, see docs/PRODUCT.md): JourneyStarted, JourneyCompleted, SpotPostCreated, SpotSignalRecorded, SpotContentExpired, AskAheadCreated, RoomExpired. Spot passage is never a published event: it is an opt-in answer with a coarse time, deleted within 24 hours, and must not be replayed as an individual trail. The pilot publishes no presence or traveller-count events.

