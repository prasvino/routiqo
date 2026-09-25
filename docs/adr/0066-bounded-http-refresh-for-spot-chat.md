# ADR 0066: Bounded HTTP refresh for Spot chat and festival rooms

Date: 2026-09-25
Status: accepted direction for the pilot; not implemented

Spot chat and the festival route room (see [PRODUCT.md](../PRODUCT.md)) use
bounded, authenticated, foreground HTTP refresh in the pilot, not WebSockets.

- While a Spot panel or festival room is open, the client refreshes every
  15–20 seconds. Otherwise it refreshes Spot content about once a minute. There
  is no background refresh.
- Each request asks for items after an opaque server cursor and returns a
  bounded page. One request is in flight at a time. Requests are cancelled on
  account, journey, visibility, Ghost Mode and offline changes, and retries back
  off.
- Every read rechecks authentication, room membership, blocks, restrictions,
  hidden content and expiry on the server. Reconnecting reauthorizes and
  refreshes; it never replays a contribution. Sending follows the journey outbox
  and offline rule in PRODUCT.md.
- A shared database rate gate bounds refresh cost across replicas. No refresh
  result depends on process-local state.

**Why.** Mobile data on the GST Road corridor drops in and out, and polling
degrades more gracefully than a socket that keeps reconnecting. The pattern
already exists: ADR 0022's bounded refresh and the ADR 0050 official-alert list.
Spot chat is short, asynchronous and expires quickly, so a 15–20 second delay
is acceptable for "how is the toll?". WebSockets would mean building out
`backend/realtime`, cross-replica fan-out and socket authorization before the
pilot, for little user benefit.

**Consequences.** Refresh load grows with open panels. Capacity checks before
the Diwali dry run and the Pongal launch must size the rate gate and database
for festival density. Messages appear with up to about 20 seconds of delay.
`backend/realtime` stays scaffold only.

**Revisit** after the Pongal pilot, and only if the festival room is used like a
live group chat (sustained message rates where refresh delay harms the
conversation). A socket design would need its own ADR covering subscription
authorization, block and Ghost Mode propagation across replicas, and reconnect
semantics.
