# Routiqo product

Status: product source of truth from 2026-09-25. Derived from
[`direction-brief.md`](direction-brief.md), which is kept as the dated input.
Where any other document disagrees with this one on vision, scope or product
guardrails, this document wins; record the conflict and fix the other document.
Only [`quality/BUILD_STATUS.md`](quality/BUILD_STATUS.md) records verified
behaviour. Nothing described here is implemented unless BUILD_STATUS says so.

## Vision

**Routiqo tells you what your journey is like right now, from people who were
just there.**

Google Maps answers where things are and how long it takes. Routiqo answers what
it is actually like on the road ahead: how bad the toll queue is, which highway
eatery is good today, whether the bus is crowded, whether the temple queue is
short. It is a live, human layer on top of the journey, not a navigation
competitor.

Travellers on the same route or at the same spot share short, temporary updates
and help each other. Information flows forward: people ahead help you, you pass
the spot, and you help the people behind.

Target situations, in priority order:

1. **Festival and long-weekend exodus** — lakhs of people leaving Chennai on the
   same few highways (Pongal, Diwali). This is the pilot.
2. **Tourist trips** — live, honest conditions at destinations instead of old or
   paid reviews.
3. **Bus travel** — status and crowding between hubs such as Koyambedu and
   Kilambakkam.
4. **Daily office commute** — recurring OMR-style commutes, later, as a
   retention loop.

Routiqo is **not** turn-by-turn navigation, a permanent social network with
followers, or a tracker of where individual people are.

## Product principles

1. **Relevance to my journey first.** Order everything by what lies ahead on my
   route, not by popularity or recency alone.
2. **Fresh or gone.** Live content decays automatically. Nothing stale is shown
   as current.
3. **Show places and posts, not people.** The map shows Spots and what is
   happening there, never individual strangers.
4. **Active input before passive tracking.** Value comes from what people choose
   to post or tap, not from collecting their movements.
5. **Useful with few users.** Every screen must be worth opening at pilot
   density. Design honest empty states; never fake activity.
6. **Temporary and anonymous by default.** Rooms and chats are short-lived;
   people post under a per-room alias.
7. **Effortless contribution.** One tap beats typing. Voice notes beat long text
   while travelling.
8. **Few concepts.** Users learn three ideas: Journey, Spots, Ask Ahead. New
   features fit inside them or wait.
9. **Ship to learn.** Real users on a real corridor beat hardening against
   problems that only appear at scale.

## Core model: Journey, Spots, Ask Ahead

| Concept | What it is | User sees |
| --- | --- | --- |
| Journey | My trip or commute, from start to finish | Map as hero; me; the Spots ahead ordered by distance |
| Spots | A place on the route: toll, eatery, fuel station, bus stand, temple, junction | Latest signals, posts and a short temporary chat |
| Ask Ahead | A question pinned to a Spot ahead of me | Answers from people who recently passed that Spot |

### Journey

- Start a journey (one-time trip or recurring commute). Existing plans, trips
  and commutes are the starting point. The map shows the route and the Spots
  ahead.
- A Spot panel lists what matters next, e.g. "Chengalpattu Toll, 12 km ahead,
  slow, 3 reports in 20 min".
- Offline-first: actions queue and sync later (see *Offline rule* below). The
  existing journey outbox, idempotency and recovery remain the backbone.
- **Route guides:** after finishing, a journey plus its journal notes and best
  Spot tips can be published as a route guide (e.g. Chennai to Ooty). Others use
  it to plan. This gives value even with few live users. Publishing strips exact
  home, office and start/end addresses.
- Daily commutes and one-time trips stay distinct, and so do rich trip journals
  and periodic commute summaries.

### Spots

- Spots are seeded in advance for the pilot corridors (about 150–200 across the
  trunk and branches; see *Pilot*) and can later be suggested by users. The existing curated anchor catalog is the starting
  point for Spot seeds.
- A Spot becomes "live" when it gets fresh activity and fades back when activity
  stops.
- **Content types:** one-tap signals (e.g. Moving / Slow / Stopped;
  Good / Avoid; queue bands), short text posts, voice notes. Photos later.
- **Post-passing prompt:** when the phone detects I passed a Spot, it offers one
  tap, e.g. "How was Chengalpattu Toll? Under 5 min / 5–15 min / 15–30 min / Over 30 min".
  Detection happens on the device; only the answer is sent. See *Driver safety*.
- **Still true?** Others can confirm a post; confirmation extends its life,
  silence lets it expire. Each confirmation resets the remaining life to half the
  base life, never beyond the maximum below. One confirmation per account per
  post; authors cannot confirm their own posts.
- **No longer true:** two confirmations from distinct accounts expire a post
  early. This is the cheapest defence against false alarms.
- **Lifespan by type** (decided 2026-09-25; tune after the dry run):

  | Content | Base life | Maximum with "Still true?" |
  | --- | --- | --- |
  | Traffic signal (Moving / Slow / Stopped); toll or bus-stand queue signal | 60 min | 2 h |
  | Traffic or incident text/voice post | 90 min | 3 h |
  | Food, fuel, restroom posts; Good / Avoid | 24 h | 36 h |
  | Ask Ahead question (answers inherit it) | 45 min, or until the asker passes the Spot | not extended |
  | Festival route room | the event window | not extended |
  | After expiry | top tips per Spot, ranked by confirmations, become **highlights**; no full chat archive | — |

- **Identity:** a random alias per room (e.g. "Blue Auto"). No follower graph.
  No private DMs in the pilot. See *Aliases* under the decisions below.

### Ask Ahead

- I ask a question on a Spot ahead ("How bad is the toll?").
- It is shown in that Spot and offered to people who recently passed it or
  posted there (opted-in Spot passage only).
- Answers are one-tap where possible and summarised ("5 replies: mostly
  10–20 min").
- If nobody answers, say so honestly and show the latest signals instead.
- The asker never learns who was offered the question. Recipient selection is
  bounded and non-deterministic, respects blocks and does not repeatedly target
  the same person.

### Later, not now

- **Pulse:** an AI summary per route once posts are too many to read. AI stays
  behind adapters and never invents conditions.
- **Aggregate traveller counts** (travellers ahead/behind, travel waves,
  cohorts): only once density makes them meaningful **and** a separate privacy
  review approves them.
- Daily commute rooms, meetups, bus-stop connect between strangers.
- Photos, push notifications beyond journey essentials.

### Glossary: earlier names

Earlier documents use names that are merged into the three concepts. Use the new
names in new work; old names remain in archived docs, ADRs and code identifiers.

| Earlier name | Now |
| --- | --- |
| Living Route, LIVE list | Journey (map with Spots ahead) |
| Live Moment | A Spot that is "live" because of fresh activity |
| Quick Signal (private) | Public one-tap signal on a Spot |
| Route Update, Traveller Tip | Spot post (text or voice) |
| Route Chat, Route Room | Short temporary Spot chat; festival route room |
| Same Situation, presence cluster, cohort | Retired for the pilot (aggregate counts, later) |
| Anchor, route anchor | Spot |
| Community traffic summary (V3) | Archived |

## Data rules and privacy

The pilot runs on **active input** only.

| Data | Source | Pilot | Rule |
| --- | --- | --- | --- |
| Posts, voice notes, one-tap signals | User chooses to send | Yes | Tied to a Spot, not to the user's position; expires by type |
| Ask Ahead questions and answers | User chooses to send | Yes | Pinned to a Spot; expires with it |
| Spot passage ("I passed Spot X") | Detected on device during an active journey | Yes, opt-in | Sent only with an answer or to receive Ask Ahead questions; coarse time; deleted within 24 hours |
| Journey route and plans | User's own journey | Yes | Private to the user unless published as a route guide |
| Continuous location of users | Passive tracking | **No** | Not collected on the server in the pilot |
| Aggregate counts (ahead/behind, waves, cohorts) | Derived from location | **No** | Requires a separate privacy review before any build |

### Why the earlier privacy machinery is retired

Earlier designs (presence leases, 10/12-person thresholds, 12/10/80% agreement
rules, differential privacy, collusion defences, irreversible Share) had two
different sources, and the new model removes each for a different reason:

- **Passive presence** (the old master context and presence spec) planned to
  ingest GPS on the server and publish traveller counts. The pilot does not
  collect continuous location, so the aggregation rules that protected it have
  nothing to protect.
- **Published summaries of chosen Quick Signals** (ADRs 0038, 0053–0055). Here
  the risk was that whether a summary appeared revealed whether one hidden
  person had reported. The new model does not hide contributors behind an
  aggregate: posts and signals are openly public, attributed to a per-room alias,
  deletable by their author, and expire. There is no hidden participation to
  infer.

What remains to protect, and is covered below: linking one alias to a real
person or across rooms, the Spot-passage record, Ask Ahead recipients, and
content in posts that reveals more than the author intended (home, number
plates, faces later).

### Guardrails that stay

- Never show or expose an individual's precise location, identity or movement to
  others. No stranger GPS, exact home/work/start/end endpoints, movement
  history, participant lists or coordinate-based lookup of people.
- Ghost Mode stops **all** sending, including Spot passage and queued posts, and
  takes priority over reconnect and outbox replay.
- Route guides strip exact home, office and start/end addresses before
  publishing.
- Users can delete their own posts; account deletion removes their content
  (posts, voice notes, signals, answers, route guides). Do not claim remote
  erasure of copies already delivered to disconnected devices.
- Report and moderation apply to posts, voice notes and chat; moderators can hide
  content quickly. Every item has Report and Block.
- Aliases are per room, so other users cannot link posts across rooms. Aliases
  are not stable public handles and never embed account identifiers.
- No private DMs between strangers in the pilot.
- Blocking applies to REST and realtime delivery, room subscription, Ask Ahead
  recipient selection and replay.
- Rate-limit posts, voice uploads, signals, "Still true?", questions, answers
  and reports; new accounts get stricter limits.
- Authenticate protected access and authorize each object, action and
  subscription on the server.
- Do not log secrets, tokens or precise location. Spot passage is logged only as
  outcome codes.
- Honest freshness: expiry uses server time; client clocks and retries cannot
  refresh content; nothing expired is shown as current.
- Never fake activity: no invented posts, synthetic crowds or AI-generated
  conditions. Label test fixtures and keep them out of production.
- Report counts ("3 reports in 20 min", "5 replies") are allowed.
  **Traveller counts** ("128 travellers on this route") are not, until aggregate
  counts pass their own review.
- Agents do not request a real user location during QA.
- Never claim anonymity. Per-room aliases reduce linkability; they do not make
  people anonymous, and copy must say so plainly (from ADR 0065).
- Abuse and Sybil resistance must not collect more identity data: no government
  ID or Aadhaar, permanent device fingerprints, long-term location history or
  hidden cross-session tracking (from ADR 0065).
- Default-off flags fail closed: anything other than an exact `true` leaves a
  capability off, with tests proving it (from ADR 0065).
- Empty is acceptable; wrong, privacy-invasive or manipulable information is not.
- No security or privacy control may be silently weakened; a change needs an
  explicit decision recorded in an ADR or this document.

### Guardrails retired or simplified

- Presence-cluster rules, 10/12-person thresholds, 12/10/80% rules, differential
  privacy and collusion defences for auto-generated summaries: archived in
  [`archive/`](archive/README.md); revisit only if aggregate counts return.
- Heavy per-journey consent flows (consent generations, intent ordering,
  route-binding consent, per-send acknowledgements): replaced by **one clear
  opt-in for Spot passage** and a simple Ghost Mode. Posting is an explicit
  action and needs no separate consent step.

### New risks to cover

- Spam, fake reviews and promotional posts by businesses at Spots.
- Abuse and harassment in anonymous chat, including Tamil and Tanglish content.
- False alarms (e.g. fake "accident" posts); "Still true?" and expiry are the
  first defence, moderation the second.
- Voice-note content (abuse, personal data) needs the same report/hide path as
  text.
- Ask Ahead or Spot passage revealing that a specific person passed a place.

### Driver safety

One tap is not automatically safe for a driver. The post-passing prompt is a
quiet, dismissible card, never a blocking modal or sound while moving. It is
shown when the phone reports the vehicle stopped or slow, or to a user who has
said they are a passenger, and expires if not answered. Contributions are never
required. Voice notes are recorded only by explicit action.

### Offline rule

Posts, signals and answers made offline are queued in the journey outbox with
their capture time and an idempotency key. On sync, the server accepts them only
if the content type's lifetime has not elapsed since capture, and shows them with
their capture time, never as new. Ghost Mode, sign-out and account deletion clear
queued social items. Read-only Spot content cached offline is labelled stale and
dropped at expiry.

## Pilot: Diwali 2026 dry run, Pongal 2027 launch

Decided 2026-09-25:

- **Diwali 2026 (outbound rush 5–7 November) is the dry run (Phase 4)** with friends and early testers, Android
  only, on a reduced scope (below). Six weeks is not enough to launch publicly
  with working moderation.
- **Pongal 2027 (tentatively 8–14 January; to be finalised) is the public launch (Phase 5)** with the full
  pilot scope.

**Corridors.** Most southbound Chennai festival traffic shares one trunk, so
density concentrates there:

| Corridor | Route | Diwali dry run | Pongal launch |
| --- | --- | --- | --- |
| Trunk | Chennai (incl. Kilambakkam terminus) → Chengalpattu → Tindivanam → Villupuram → Ulundurpet → Perambalur → Trichy (GST Road) | Yes | Yes |
| Branch | Trichy → Thanjavur | Yes | Yes |
| Branch | Trichy → Madurai → Tirunelveli | Yes | Yes |
| Branch | Madurai → Tuticorin | Yes | Yes |
| Separate trunk | Chennai → Vellore → Krishnagiri → Salem → Coimbatore | No | Yes |

About 150–200 seeded Spots across trunk and branches (more for Coimbatore at
Pongal). Seeding is weighted to the trunk; branch Spots cover major tolls, bus
stands and highway eateries only.

**Diwali dry-run scope:** Spots ahead on the Android Journey map, one-tap
signals, short text posts, "Still true?" / "No longer true", per-type expiry,
Report/Block and moderator hide, official alerts on the corridor districts.
**Not in the dry run:** voice notes, Ask Ahead, post-passing prompts, Spot
chat and the festival room, route guides. A second, smaller dry run on a
December long weekend exercises those before Pongal.

**Pongal launch scope**

- Android app on physical devices (primary); web for route guides and planning.
- Journey with map and Spots ahead; offline-first.
- About 150–200 seeded Spots (plus the Coimbatore trunk): tolls, major eateries,
  fuel stations, restrooms, bus stands.
- One-tap signals, short posts, voice notes, post-passing prompts, "Still true?".
- Ask Ahead.
- A festival route room per corridor for the event window.
- Route guides from completed journeys.
- Report, moderation and a small on-call moderator rota during the rush.

**Out of scope for the pilot:** aggregate traveller counts, travel waves, Pulse,
private DMs, meetups, commute rooms, photos, push notifications beyond journey
essentials.

### Path to the pilot

| Phase | Goal | Gate to move on |
| --- | --- | --- |
| 1. Foundations | Google sign-in, hosted maps and routing, Android tested on real devices | App installs and runs a full journey on 3+ Android phones |
| 2. Spots and posts | Seeded Spots, signals, posts, voice notes, expiry, moderation | 10 testers complete a real highway trip and post |
| 3. Ask Ahead and route guides | Questions on Spots, post-passing prompts, publish a guide | Questions get answered on a test trip |
| 4. Dry run | Diwali 2026 with friends and early users on the reduced scope; a December long weekend for voice, Ask Ahead, prompts, chat and guides | No blocking bugs; moderation works |
| 5. Pongal pilot | Public launch on the corridors | Measure against success signals below |

Phases overlap in time: the Diwali dry run needs Phase 1 and the reduced
Phase 2 scope by about 1 November 2026; Phase 3 and the rest of Phase 2 follow
before the December dry run.

New capabilities ship default-off behind flags until their phase gate passes.
The release checklist is [`../todo.md`](../todo.md); the engineering sequence is
[`development/BUILD_PLAN.md`](development/BUILD_PLAN.md).

## What happens to the existing build

Most built work is kept; it moves from being the product to supporting the
Journey.

| Existing feature | Decision | New role |
| --- | --- | --- |
| Plans, trips, recurring commutes | Keep | Starting point of a Journey |
| Journeys with offline sync and recovery | Keep, core | Backbone of the Journey experience |
| Journals and journey history | Keep | Source material for route guides |
| Monthly commute summaries | Keep, low priority | Retention feature for commuters after the pilot |
| Private Quick Signals | Evolve | Become public one-tap signals on Spots |
| Signal storage, idempotent commands, abuse budgets, expiry maintenance, stop/withdraw | Keep | Infrastructure for Spot signals and posts; withdraw becomes "delete my post" |
| Curated anchor catalog and route-anchor matching | Keep, rename | Seeded Spots and "Spots ahead" |
| Official alerts pilot | Keep, optional | Shown on Spots when available |
| Curated Explore destinations | Demote | Replaced over time by route guides |
| Bookmarks and account backup | Keep | Supporting features |
| Google sign-in, account deletion | Keep, finish | Required for posting and moderation |
| Moderator queue, restrictions, blocks and grants | Keep, simplify | Moderate posts, voice notes and chat; lighter access process for the pilot |
| Community traffic summaries (V3, threshold-gated) | Archive | Revisit only with aggregate counts later |
| Presence, cohort publication, public LIVE protocol, DP | Archive | Replaced by the active-input rules above |
| Per-journey consent and private route preparation flows | Archive | Replaced by one Spot-passage opt-in and Ghost Mode |
| Hosted maps and routing (Valhalla, Photon, MapLibre) | Keep, finish | Required for Journey and Spots |
| Android app | Promote to primary | Pilot runs on Android |

Archived code paths stay default-off. No stored private report, consent or
receipt becomes public through a migration.

## Success signals

The pilot works if:

- Most active journeys see at least one fresh post or signal on a Spot ahead.
- A meaningful share of Ask Ahead questions get an answer within 15 minutes.
- Post-passing prompts get a high tap rate, showing contribution is effortless.
- Users say Routiqo told them something Google Maps did not.
- People open Routiqo again on their return journey.

Exact targets are set before the dry run. Measure with privacy-preserving
aggregates; do not optimise time spent, message volume or notification count.

## Open questions

- [ ] How do we get the first few hundred users on the corridor: bus operators,
      travel groups, IT company groups, social media?
- [ ] Which Spots to seed, and who curates them?
- [ ] Tamil, English or both in the UI at launch?
- [ ] Should Spot passage require sign-in, or allow anonymous one-tap signals?
- [ ] How do we handle businesses posting about their own Spots?
- [ ] When, if ever, do aggregate counts come back, and under what privacy
      review?
- [ ] Who seeds and verifies ~150–200 Spots before 1 November?
- [ ] Finalise Pongal travel dates (tentatively 8–14 January 2027) and the festival room windows.

## Decisions (2026-09-25)

Recorded after the brief was adopted; each is a default to revisit with
evidence from the dry runs.

1. **Spot chat and festival room transport:** bounded foreground HTTP refresh,
   not WebSockets, for the pilot — every 15–20 s while a Spot panel or room is
   open, about once a minute otherwise, never in the background, one request in
   flight, cursor-based. Highway connectivity is patchy and the pattern already
   exists (ADR 0022, official alerts). Revisit WebSockets only if the festival
   room behaves like a live group chat. See
   [ADR 0066](adr/0066-bounded-http-refresh-for-spot-chat.md).
2. **Spot-passage detection on Android:** only during an active journey, a
   location foreground service with its visible notification, balanced
   accuracy, about every 100 m or 30 s. Started while the app is in use, so no
   "allow all the time" background-location permission is requested. The phone
   matches the next ~20 Spots ahead on the route; a pass is entering about
   150 m of a Spot and then continuing past it. "Stopped or slow" is speed under
   about 10 km/h for 20 s or more; the prompt card waits for that or for the next
   app open, and disappears after about 30 min. Battery target: about 3–4% extra
   per hour, measured on the Phase 1 phones; reduce frequency if exceeded.
3. **Aliases:** server-generated per room and account, random (never derived
   from the account ID), from a curated English and Tamil-friendly word list
   with no offensive pairs; stable for the room's life, deleted when it expires.
   Collisions within a room get a number ("Blue Auto 2"). Blocking a post or
   alias blocks the underlying account everywhere without revealing which
   account it is. Moderators can see the account behind an alias; every lookup
   is audited.
4. **Lifetimes and "Still true?":** as in the Spots lifespan table above,
   including "No longer true" (two distinct accounts expire a post early) and
   highlights ranked by confirmations.
5. **Timeline, corridors and official alerts:** Diwali 2026 dry run, Pongal 2027
   launch, trunk plus branches (see *Pilot*). Official alerts stay optional but
   are extended to the corridor districts before Diwali, because November is
   north-east monsoon season in Tamil Nadu and rain and cyclone warnings matter
   more then.
6. **Tabs:** keep four tabs — Home, Guides, Trips, Profile — with Explore renamed
   Guides when route guides ship (Phase 3). The active Journey is a full-screen
   map mode opened from Home with a persistent "Back to journey" bar, not a
   tab. No chat tab. Same structure on Android and web; on web the Journey is
   secondary to planning and guides.

## Related documents

- Engineering entry point: [`../AGENTS.md`](../AGENTS.md)
- Engineering guardrails:
  [`development/ROUTIQO_CODEX_ENGINEERING_GUARDRAILS.md`](development/ROUTIQO_CODEX_ENGINEERING_GUARDRAILS.md)
- Engineering context (stack, modules, offline, media, testing):
  [`architecture/ENGINEERING_CONTEXT.md`](architecture/ENGINEERING_CONTEXT.md)
- UI/UX system: [`design/ROUTIQO_UI_UX_SYSTEM.md`](design/ROUTIQO_UI_UX_SYSTEM.md)
- Privacy: [`privacy/LOCATION_PRIVACY.md`](privacy/LOCATION_PRIVACY.md),
  [`privacy/DATA_RETENTION_AND_DELETION.md`](privacy/DATA_RETENTION_AND_DELETION.md),
  [`privacy/ANTI_STALKING.md`](privacy/ANTI_STALKING.md)
- Security: [`security/SECURITY.md`](security/SECURITY.md),
  [`security/THREAT_MODEL.md`](security/THREAT_MODEL.md)
- Archived material and why: [`archive/README.md`](archive/README.md)
