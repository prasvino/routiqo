# Routiqo Direction Brief — for Claude Code

> Adopted 2026-09-25. The maintained product source of truth derived from this brief is [PRODUCT.md](PRODUCT.md); this file is kept unchanged below as the dated input.

Sep 25, 2026 · Prasanna

## How to use this in Claude Code

This brief resets Routiqo's product direction. It is the source of truth for vision, scope and guardrails; existing docs must be brought in line with it. Paste the whole brief into Claude Code with the instruction below.

> Read this brief. Documentation only: do not create, edit or delete any code, config or tests. Start in plan mode.
>
> 1. Inventory every spec, guardrail, ADR, roadmap, README, CLAUDE.md/AGENTS.md and other .md file in the repo.
> 2. For each, say whether it aligns, needs updating, is superseded, or is unaffected by this brief.
> 3. List every conflict you find, especially privacy rules that assume passive location tracking.
> 4. Propose a change plan and wait for my approval.
>
> After approval: add a single top-level product doc (e.g. docs/PRODUCT.md) based on this brief, update the other docs to match, and move superseded material to docs/archive/ with a note on why. Keep the privacy intent; simplify only where the new model removes the risk. End with a short summary of what changed and any open questions.

## Vision

**Routiqo tells you what your journey is like right now, from people who were just there.**

Google Maps answers where things are and how long it takes. Routiqo answers what it is actually like on the road ahead: how bad the toll queue is, which highway eatery is good today, whether the bus is crowded, whether the temple queue is short. It is a live, human layer on top of the journey, not a navigation competitor.

It connects people in the moment. Travellers on the same route or at the same spot share short, temporary updates and help each other. The information flows forward: people ahead help you, you pass the spot, and you help the people behind.

**Target situations**

- **Festival and long-weekend exodus:** lakhs of people leaving Chennai on the same few highways (Diwali, Pongal).
- **Tourist trips:** live, honest conditions at destinations instead of old or paid reviews.
- **Bus travel:** status and crowding on buses between hubs such as Koyambedu and Kilambakkam.
- **Daily office commute:** recurring OMR-style commutes, later, as a retention loop.

**What Routiqo is not:** turn-by-turn navigation, a permanent social network with followers, or a tracker of where individual people are.

## Product principles

1. **Relevance to my journey first.** Everything is ordered by what lies ahead of me on my route, not by popularity or recency alone.
2. **Fresh or gone.** Live content decays automatically. Nothing stale is shown as current.
3. **Show places and posts, not people.** The map shows spots and what is happening there, never individual strangers.
4. **Active input before passive tracking.** Value comes from what people choose to post or tap, not from collecting their movements.
5. **Useful with few users.** Every screen must be worth opening at pilot density. Design honest empty states; never fake activity.
6. **Temporary and anonymous by default.** Rooms and chats are short-lived; people post under a per-room alias.
7. **Effortless contribution.** One tap beats typing. Voice notes beat long text while travelling.
8. **Few concepts.** Users learn three ideas: Journey, Spots, Ask Ahead. New features fit inside them or wait.
9. **Ship to learn.** Real users on a real corridor beat hardening against problems that only appear at scale.

## Core model: Journey, Spots, Ask Ahead

Routiqo has three user-facing concepts. Earlier names (Living Route, LIVE, Live Moments, Same Situation, Quick Signals, Route Chat, Route Updates) are merged into these.

| Concept | What it is | User sees |
| --- | --- | --- |
| Journey | My trip or commute, from start to finish | Map as hero; me; the Spots ahead ordered by distance |
| Spots | A place on the route: toll, eatery, fuel station, bus stand, temple, junction | Latest signals, posts and a short temporary chat |
| Ask Ahead | A question pinned to a Spot ahead of me | Answers from people who recently passed that Spot |

### Journey

- Start a journey (trip or recurring commute); the map shows the route and the Spots ahead.
- A Spot panel lists what matters next: "Chengalpattu Toll, 12 km ahead, slow, 3 reports in 20 min."
- Offline-first: actions queue and sync later.
- **Route guides:** after finishing, a journey plus its journal notes and best Spot tips can be published as a route guide (e.g. Chennai to Ooty). Others use it to plan. This gives value even with few live users.

### Spots

- Spots are seeded in advance for the pilot corridor and can later be suggested by users.
- A Spot becomes "live" when it gets fresh activity and fades back when activity stops (this replaces Live Moments).
- **Content types:** one-tap signals (e.g. Moving / Slow / Stopped; Good / Avoid), short text posts, voice notes, photos later.
- **Post-passing prompt:** when the phone detects I passed a Spot, it asks one tap, e.g. "How was Chengalpattu Toll? Under 5 min / 5–15 min / Over 15 min." Detection happens on the device; only the answer is sent.
- **Still true?** Others can confirm a post; confirmation extends its life, silence lets it expire.
- **Lifespan by type:** traffic and queue signals last about 1–2 hours; food, fuel and restroom posts about 24 hours; festival route rooms last for the event window. Expired content becomes **highlights** (top tips per Spot), not a full chat archive.
- **Identity:** a random alias per room (e.g. "Blue Auto"); no follower graph; no private DMs in the pilot.

### Ask Ahead

- I ask a question on a Spot ahead ("How bad is the toll?").
- It is shown in that Spot and offered to people who recently passed it or posted there.
- Answers are one-tap where possible and summarised ("5 replies: mostly 10–20 min").
- If nobody answers, say so honestly and show the latest signals instead.

### Later, not now

- **Pulse:** an AI summary per route once posts are too many to read.
- **Aggregate counts** (travellers ahead/behind, travel waves, cohorts): only once density makes them meaningful and privacy review approves them.
- Daily commute rooms, meetups, bus-stop connect between strangers.

## Data rules and privacy guardrails

The pilot runs on **active input** only. Most of the earlier privacy machinery (12-person thresholds, collusion defences, consent that cannot retract a published summary) existed because summaries were generated from people's location data. Posts and taps that people choose to send remove most of that risk.

| Data | Source | Pilot | Rule |
| --- | --- | --- | --- |
| Posts, voice notes, one-tap signals | User chooses to send | Yes | Tied to a Spot, not to the user's position; expires by type |
| Ask Ahead questions and answers | User chooses to send | Yes | Pinned to a Spot; expires with it |
| Spot passage ("I passed Spot X") | Detected on device during an active journey | Yes, opt-in | Sent only with an answer or to receive Ask Ahead questions; coarse time; deleted within 24 hours |
| Journey route and plans | User's own journey | Yes | Private to the user unless published as a route guide |
| Continuous location of users | Passive tracking | No | Not collected on the server in the pilot |
| Aggregate counts (ahead/behind, waves, cohorts) | Derived from passive location | No | Requires a separate privacy review before any build |

**Guardrails that stay**

- Never show or expose an individual's precise location, identity or movement to others.
- Ghost Mode stops all sending, including Spot passage.
- Route guides strip exact home, office and start/end addresses before publishing.
- Users can delete their own posts; account deletion removes their content.
- Report and moderation apply to posts, voice notes and chat; moderators can hide content quickly.
- Aliases are per room, so posts cannot be linked across rooms by other users.
- No private DMs between strangers in the pilot, for safety.

**Guardrails to simplify or retire**

- Presence-cluster rules, 12-person thresholds and collusion defences for auto-generated summaries: archive; revisit only if aggregate counts return.
- Heavy per-journey consent flows: replace with one clear opt-in for Spot passage and a simple Ghost Mode.

**New risks to cover**

- Spam, fake reviews and promotional posts by businesses at Spots.
- Abuse and harassment in anonymous chat, including Tamil and Tanglish content.
- False alarms (e.g. fake "accident" posts); "Still true?" and expiry are the first defence.

## Pilot: Pongal exodus, Chennai southbound

The first real test is the Pongal travel rush in mid-January 2027, when huge numbers leave Chennai on the same few roads at the same time. That density solves cold start better than any daily commute.

**Corridor:** Chennai to Trichy and Madurai along GST Road, via Chengalpattu, Tindivanam, Villupuram, Ulundurpet and Perambalur, plus the Kilambakkam bus terminus.

**In scope**

- Android app on physical devices (primary); web for route guides and planning.
- Journey with map and Spots ahead; offline-first.
- About 50–100 seeded Spots: tolls, major eateries, fuel stations, restrooms, bus stands.
- One-tap signals, short posts, voice notes, post-passing prompts, "Still true?".
- Ask Ahead.
- A festival route room for the corridor for the event window.
- Route guides from completed journeys.
- Report, moderation and a small on-call moderator rota during the rush.

**Out of scope for the pilot**

Aggregate traveller counts, travel waves, Pulse, private DMs, meetups, commute rooms, photos, push notifications beyond journey essentials.

**Path to the pilot**

| Phase | Goal | Gate to move on |
| --- | --- | --- |
| 1. Foundations | Google sign-in, hosted maps and routing, Android tested on real devices | App installs and runs a full journey on 3+ Android phones |
| 2. Spots and posts | Seeded Spots, signals, posts, voice notes, expiry, moderation | 10 testers complete a real highway trip and post |
| 3. Ask Ahead and route guides | Questions on Spots, post-passing prompts, publish a guide | Questions get answered on a test trip |
| 4. Dry run | A normal long weekend before Pongal with friends and early users | No blocking bugs; moderation works |
| 5. Pongal pilot | Public launch on the corridor | Measure against success signals below |

## What happens to the existing build

Most built work is kept; it moves from being the product to supporting the Journey. Docs should reflect this new role for each item.

| Existing feature | Decision | New role |
| --- | --- | --- |
| Plans, trips, recurring commutes | Keep | Starting point of a Journey |
| Journeys with offline sync and recovery | Keep, core | Backbone of the Journey experience |
| Journals and journey history | Keep | Source material for route guides |
| Monthly commute summaries | Keep, low priority | Retention feature for commuters after the pilot |
| Private Quick Signals | Evolve | Become public one-tap signals on Spots |
| Official alerts pilot | Keep, optional | Shown on Spots when available |
| Curated Explore destinations | Demote | Replaced over time by route guides |
| Bookmarks and account backup | Keep | Supporting features |
| Google sign-in, account deletion | Keep, finish | Required for posting and moderation |
| Moderator queue and grants | Keep, simplify | Moderate posts, voice notes and chat; lighter access process for pilot |
| Community traffic summaries (passive, threshold-gated) | Archive | Revisit only with aggregate counts later |
| Presence clusters, collusion defences, per-journey consent flows | Archive | Replaced by the active-input rules above |
| Hosted maps and routing (Valhalla, Photon, MapLibre) | Keep, finish | Required for Journey and Spots |
| Android app | Promote to primary | Pilot runs on Android |

## Success signals and open questions

**The pilot works if:**

- Most active journeys see at least one fresh post or signal on a Spot ahead.
- A meaningful share of Ask Ahead questions get an answer within 15 minutes.
- Post-passing prompts get a high tap rate, showing contribution is effortless.
- Users say Routiqo told them something Google Maps did not.
- People open Routiqo again on their return journey.

Exact targets to be set before the dry run.

**Open questions**

- [ ] How do we get the first few hundred users on the corridor: bus operators, travel groups, IT company groups, social media?
- [ ] Which Spots to seed, and who curates them?
- [ ] Tamil, English or both in the UI at launch?
- [ ] Should Spot passage require sign-in, or allow anonymous one-tap signals?
- [ ] How do we handle businesses posting about their own Spots?
- [ ] When, if ever, do aggregate counts come back, and under what privacy review?
