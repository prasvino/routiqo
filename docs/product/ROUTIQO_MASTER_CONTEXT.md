# Routiqo --- Product & Engineering Master Context

> **Purpose:** This document is the master starting context for Codex.
> It describes the product vision, V1 scope, UX principles,
> privacy/safety model, AI strategy, architecture, technology stack,
> source-code organization, engineering rules, and implementation
> sequence for **Routiqo**.
>
> Codex should treat this document as the high-level source of truth.
> Where a detail is not specified, prefer the simplest
> production-quality implementation that preserves the principles in
> this document. Do not introduce major architecture, infrastructure,
> social, or privacy changes without documenting the decision.

------------------------------------------------------------------------

## 1. Product Definition

**Routiqo is a privacy-first, route-aware platform that turns a shared
journey into a useful real-time community.**

It is designed for both:

-   **Everyday movement:** office commutes, college commutes, airport
    transfers, recurring routes.
-   **Travel:** road trips, tourism, intercity journeys,
    pilgrimage/festival travel, weekend trips.

The fundamental product idea is:

> **The route is the social graph.**

Routiqo is not built around followers, influencers, likes, or an endless
social feed. It is built around the temporary context created when many
people are moving through the same route at approximately the same time.

A concise product proposition:

> **Google Maps tells you where the road is. Routiqo tells you what is
> happening around your journey.**

A more general definition:

> **Routiqo is a living, privacy-safe social and intelligence layer for
> people on the move.**

------------------------------------------------------------------------

## 2. Product Principles

1.  **Journey first, people second.** The primary question is "What is
    happening around my journey?", not "Which strangers are near me?"

2.  **Utility before social engagement.** The product must be useful
    even if most users never chat or post.

3.  **The map is the primary active-journey surface.** Chat enhances the
    map; it must not turn Routiqo into WhatsApp/Telegram/Discord.

4.  **Privacy is architecture, not a setting added later.** No
    public/social feature should require exposing one person's precise
    real-time location to another user.

5.  **Context is temporary.** Route rooms and traveller presence should
    naturally expire with the journey.

6.  **Low-friction participation.** Most travellers will be passive.
    Contributions should take seconds.

7.  **AI should create intelligence, not AI clutter.** AI should mostly
    operate invisibly by summarizing, extracting, ranking, predicting,
    moderating, and personalizing.

8.  **Offline and unreliable networks are normal.** The mobile
    experience must tolerate intermittent connectivity.

9.  **Start simple operationally.** Build a well-structured system that
    can be split later instead of prematurely creating many
    microservices.

------------------------------------------------------------------------

## 3. Target Use Cases

### 3.1 Daily commute

Example:

**OMR / Navalur → DLF Chennai**

A user opens Routiqo before or during the morning commute and can see:

-   Current journey.
-   Route and destination.
-   Approximate traveller density.
-   Important incidents ahead.
-   Rain/road/parking/community reports.
-   Useful places.
-   Temporary route conversation.
-   Personalized journey intelligence.

Example:

> **OMR → DLF · Morning Commute**\
> 386 travellers on this route\
> 28 people talking\
> Heavy congestion near Sholinganallur\
> Rain reported near Perungudi

Daily commuting is a core use case because it creates repeated usage and
predictable route density.

### 3.2 Long-distance journey

Example:

**Chennai → Madurai**

Routiqo provides:

-   Living route.
-   Traveller density.
-   Traffic/road events.
-   Fuel/food/restroom recommendations.
-   Attractions and useful stops.
-   Route conversation.
-   Journey intelligence.
-   Automatic Trip Journal after completion.

### 3.3 Festival / pilgrimage / high-density travel

Examples:

-   Pongal/Diwali travel.
-   Tiruvannamalai pilgrimage.
-   Temple/festival routes.
-   Large public events.

These periods can create concentrated route communities and are
strategically useful for solving the cold-start problem.

### 3.4 Discovery when not travelling

Routiqo must remain useful when the user is stationary:

-   Search destinations.
-   Weekend getaways.
-   Nearby places.
-   Popular routes.
-   Saved places.
-   Upcoming events/festivals.
-   Previous journeys and memories.
-   Recurring commute card.

It must **not** become an endless generic content feed.

------------------------------------------------------------------------

## 4. Core User Experience

The primary loop is:

``` text
Home / Discovery
       ↓
Choose destination / recurring commute
       ↓
Start Journey
       ↓
Living Route
       ↓
Route Intelligence + Useful Places
       ↓
Optional Route Conversation / Contribution
       ↓
Complete Journey
       ↓
Trip Journal / Memories
       ↓
Return for commute or next journey
```

### 4.1 Main navigation

Consumer applications should use approximately:

-   **Home**
-   **Explore**
-   **Trips**
-   **Profile**

Do **not** create a permanent Chat tab. Conversation is contextual to an
active journey.

------------------------------------------------------------------------

## 5. Home / Discovery

The Home screen should not permanently show a full-screen map.

Possible content:

-   Current city.
-   "Where do you want to go?"
-   Prominent **Start a Journey** CTA.
-   "Your Commute" for recurring routes.
-   Nearby destinations.
-   Weekend drives/getaways.
-   Saved places.
-   Upcoming travel/festivals.
-   Recent trip/memories.
-   Contextual recommendations.

Example:

> **Your commute**\
> OMR → DLF\
> Next usual departure · 8:15 AM\
> **See today's route**

Avoid conventional social terminology such as:

-   Followers
-   Likes
-   Influencers
-   Social feed
-   Trending posts

Prefer:

-   On Your Route
-   Route Updates
-   Traveller Tips
-   What's Ahead
-   Popular Nearby
-   Worth Stopping For
-   Places
-   Journey
-   Memories

------------------------------------------------------------------------

## 6. Starting a Journey

Support two conceptual journey types:

### Daily Commute

-   Start/origin area.
-   Destination area.
-   Typical departure time.
-   Typical days.
-   Preferred route where applicable.
-   Ability to start today's commute quickly.

### Trip / Travel

-   Origin.
-   Destination.
-   Route.
-   Optional stops/preferences.

The system should use precise GPS internally only as required for the
user's own journey/location processing. Social/public outputs must
follow the privacy model described later.

------------------------------------------------------------------------

## 7. Living Route Map

The active journey should transition to a map-first interface.

The user should immediately understand:

1.  Where am I?
2.  Where am I going?
3.  What is happening ahead?
4.  What useful places are around my route?
5.  How active is this route right now?

### Required map elements

-   Clear **YOU** marker.
-   Destination.
-   Route geometry.
-   Highway/road/town context.
-   Remaining distance/time where available.
-   Traveller density/clusters.
-   Route incidents/updates.
-   Useful places.
-   Route conversation activity.
-   Privacy status.
-   Journey status.

Example:

> **YOU · 42 km to destination**

Do not make the map decorative. It must provide real geographic context.

### Traveller presence

Prefer aggregate displays:

> **428 travellers on this route**

or:

> **23 travellers around this route segment**

instead of exposing individual moving strangers.

When zoomed out:

> **1.2K travellers**

When zooming in, progressively reveal smaller clusters, while still
respecting minimum anonymity thresholds and privacy rules.

------------------------------------------------------------------------

## 8. Route Updates

Route Updates are useful, structured information associated with a
route/location/time.

Initial categories:

-   Traffic
-   Accident
-   Road closure
-   Road hazard
-   Weather/rain
-   Flooding/waterlogging
-   Parking
-   Food
-   Fuel
-   Restroom
-   Attraction/place
-   Queue/crowd
-   Other useful update

Contribution must be extremely fast.

Example action sheet:

``` text
+ Add Update

Traffic
Warning
Rain
Food
Fuel
Parking
Place
Photo
Quick Update
```

Target: **5--10 seconds** for common contributions.

Structured updates are different from Route Chat.

------------------------------------------------------------------------

## 9. Temporary Route Rooms

Users travelling along sufficiently overlapping routes during a relevant
time window can participate in temporary route rooms.

Example:

> **OMR → DLF · Morning Commute**\
> 342 travellers today\
> 23 people talking

Characteristics:

-   Journey/route scoped.
-   Time scoped.
-   Ephemeral.
-   Automatically becomes inactive after the relevant travel period.
-   Not a permanent WhatsApp-style group.
-   Participation is optional.
-   Reading may be allowed under privacy/trust rules without exposing
    the reader's precise location.

### Initial communication features

V1:

-   Text.
-   Structured route updates.
-   Lightweight reactions.
-   Report/block.

Potential later feature after safety validation:

-   Short voice snippets (approximately 10--15 seconds).

Do **not** implement unrestricted live group calling in V1.

Do **not** implement unrestricted direct messages in V1.

Potential future connection flows should require mutual consent.

------------------------------------------------------------------------

## 10. Safety and Privacy Model

This is a foundational system requirement.

### Core rule

> **No feature should require exposing a person's precise real-time
> location to another user.**

### 10.1 Presence model

Avoid:

> "User X is exactly 800 metres away."

Prefer:

> "18 travellers are heading toward DLF."

or:

> "42 travellers around this route section."

The system should transform location into privacy-safe representations
such as:

-   Route segment.
-   Coarse geospatial cell.
-   Aggregated traveller cluster.
-   Direction.
-   Time-limited presence.

### 10.2 Exact endpoints

Never publicly expose exact home/work coordinates.

Display coarse areas such as:

> **OMR → DLF**

rather than exact residential/workplace coordinates.

Historical movement trails must not be publicly available.

### 10.3 User privacy controls

Provide clearly visible options such as:

-   **Route Only --- recommended**
-   Approximate presence, if supported safely.
-   Friends/travel-circle visibility, later.
-   **Ghost Mode**
-   Temporary visibility expiry.

Ghost Mode should be easy to activate from the active journey.

### 10.4 Anti-stalking design

The system must be designed to prevent:

-   Continuous following of a specific stranger.
-   Repeated precise location queries.
-   User lookup from coordinates.
-   Scraping nearby-user lists.
-   Historical route tracking.
-   Repeated unwanted contact.
-   Easy correlation of home/work endpoints.
-   Enumeration of route participants.

Implement:

-   Aggregation thresholds.
-   Location fuzzing/coarsening.
-   Temporal delays where appropriate.
-   Rate limits.
-   Anti-enumeration.
-   Abuse detection.
-   Block/report.
-   Visibility expiry.
-   Data minimization.

### 10.5 Moderation

Temporary stranger conversations require strong prevention, not only
reactive moderation.

Use multiple layers:

``` text
Input
  ↓
Authentication / trust checks
  ↓
Rate limiting
  ↓
Spam / abuse rules
  ↓
Automated moderation
  ↓
Risk decision
  ↓
Publish / restrict / review
```

Detect or control:

-   Harassment.
-   Sexual harassment.
-   Threats.
-   Stalking behavior.
-   Hate/abuse.
-   Spam.
-   Scams.
-   Personal-information solicitation.
-   Repeated unwanted interaction.
-   Dangerous misinformation where relevant.

Every message/update/profile interaction should provide easy **Report**
and **Block** actions.

### 10.6 Trust and reputation

Maintain an internal trust/reputation model using signals such as:

-   Account age.
-   Verification state.
-   Successful journeys.
-   Useful contribution history.
-   Spam behavior.
-   Reports.
-   Blocks.
-   Enforcement history.

Do not necessarily expose a numeric score publicly.

New/untrusted accounts should receive stricter rate limits and feature
limits.

------------------------------------------------------------------------

## 11. Trip Journal and Memories

After meaningful travel, automatically generate a Trip Journal.

Potential contents:

-   Route.
-   Date/time.
-   Distance.
-   Duration.
-   Stops.
-   Photos.
-   Saved places.
-   Useful route discoveries.
-   Highlights.
-   Optional AI-written summary.

Example:

> **Chennai → Madurai**\
> 486 km · 7 stops · 24 photos

Daily commutes should **not** create a rich journal every day.

Instead, optionally create periodic commute summaries:

> **Your August Commute**\
> 18 journeys\
> 420 km\
> 7 route updates\
> 4 saved places

Trip Journals should be designed to be attractive and externally
shareable.

------------------------------------------------------------------------

## 12. AI --- Journey Intelligence

AI should not primarily appear as a generic chatbot.

The strategic concept is:

> **Routiqo converts collective, privacy-safe travel signals into
> personalized real-time journey intelligence.**

Potential input signals:

-   User's active route.
-   Approximate route position.
-   Direction.
-   Anonymous aggregate speeds.
-   Traveller density.
-   Structured route reports.
-   Route conversation.
-   Weather.
-   Traffic/public data integrations.
-   Places.
-   Historical route patterns.
-   User preferences/saved places.
-   Journey history where consented and appropriate.

### 12.1 Live route intelligence

Convert many weak signals into concise information.

Example raw signals:

``` text
23 travellers slowing sharply
11 traffic reports
7 messages mention accident
18 travellers rerouting
```

Output:

> **Possible lane obstruction near Sholinganallur**\
> Traffic has slowed sharply over the last several minutes. Multiple
> travellers report a blocked lane.

Never overstate uncertain crowd-derived information as verified fact.
Include confidence/provenance where appropriate.

### 12.2 Route chat understanding

AI can extract structured events from conversation.

Example messages:

-   "Accident near signal."
-   "Right lane blocked."
-   "Police came."
-   "Service road moving."

Potential structured result:

``` json
{
  "type": "ACCIDENT",
  "locationArea": "Sholinganallur Junction",
  "direction": "NORTHBOUND",
  "impact": "HEAVY",
  "confidence": "HIGH"
}
```

This can create/update an incident after appropriate corroboration and
safety checks.

### 12.3 Context-aware assistant

A future **Ask Routiqo** capability may answer questions such as:

-   "What's happening ahead?"
-   "Good breakfast without leaving my route?"
-   "Where is a clean restroom in the next 30 km?"
-   "Summarize the last 20 minutes."
-   "Is parking difficult near my destination?"

The assistant must already understand the active journey context.

### 12.4 Prediction

Potential capabilities:

-   Expected journey duration.
-   Unusual congestion.
-   Better departure windows.
-   Route-specific recurring patterns.
-   Parking/crowd patterns.
-   Event/festival congestion.

Example:

> Your DLF commute is likely slower than usual today. Leaving 15 minutes
> earlier may reduce the expected delay.

Predictions must include uncertainty and should not be presented as
guaranteed outcomes.

### 12.5 AI relevance ranking

Rank information based on:

-   Ahead vs behind.
-   Distance/time until relevant.
-   Severity.
-   Confidence.
-   Freshness.
-   Direction.
-   User preferences.
-   Route deviation.

The user should see the **few things that matter**, not hundreds of raw
events.

### 12.6 Place intelligence

Use privacy-safe aggregate journey behavior plus explicit community
signals to answer questions such as:

-   Popular breakfast stop for this route.
-   Reliable fuel stop.
-   Clean restroom.
-   Family-friendly stop.
-   Minimal route deviation.
-   Useful stop before a long highway stretch.

Do not infer sensitive personal traits from travel behavior.

### 12.7 Natural-language contributions

Allow users eventually to speak/type naturally:

> "Big pothole on the left after the toll."

AI can extract:

-   Category.
-   Approximate route segment.
-   Direction.
-   Severity.
-   Time.

Support multilingual India use cases, including Tamil, English,
Tanglish, Hindi, and other languages as the product expands.

### 12.8 Translation

Route conversations may offer user-controlled translation between
supported languages.

### 12.9 AI moderation

AI moderation is one layer in the safety system, combined with:

-   Deterministic rules.
-   Rate limits.
-   Trust/reputation.
-   User reports.
-   Human moderation/escalation.

### 12.10 AI architecture rule

Application modules must not directly depend on a specific AI vendor.

Use an internal abstraction such as:

``` java
public interface JourneyIntelligenceService {
    RouteSummary summarize(...);
    IncidentAnalysis analyze(...);
    JourneyPrediction predict(...);
    TravelRecommendation recommend(...);
}
```

Provider-specific integrations belong in infrastructure adapters.

------------------------------------------------------------------------

## 13. Cold Start and Launch Strategy

The biggest business/product risk is route density.

Bad experience:

> 3 travellers\
> No updates\
> No conversation

Therefore do not assume an India-wide launch immediately.

Initial validation should concentrate users into:

-   One major commute corridor.
-   Specific morning/evening windows.
-   One or more festival/event corridors.
-   Selected intercity routes.

Possible Chennai experiments include OMR and major employment corridors.

The key validation question:

> **After experiencing Routiqo during a commute/journey, do users
> voluntarily open it again on their next journey?**

Important metrics:

-   Journey activation rate.
-   Repeat journey usage.
-   D1/D7/D30 retention by commuter/traveller cohort.
-   Sessions per commute/trip.
-   Route intelligence views.
-   Percentage of users who read updates.
-   Percentage contributing.
-   Route-room participation.
-   Useful-report confirmation rate.
-   Safety/report/block rates.
-   Empty-route rate.
-   Time to first useful signal.
-   AI alert usefulness/dismissal.
-   Trip Journal engagement/sharing.

Routiqo must provide value when the overwhelming majority of users are
passive.

------------------------------------------------------------------------

# ENGINEERING

## 14. Final Technology Stack

### Consumer mobile

-   **React Native**
-   **Expo**
-   **TypeScript**

Use native Swift/Kotlin modules only when required for
performance/platform capabilities.

### Consumer web / desktop browser

-   **Next.js**
-   **React**
-   **TypeScript**

Use for:

-   Discovery.
-   Trip planning.
-   Route exploration where appropriate.
-   Journals.
-   Shared/public journey pages.
-   Account/profile.
-   Destination/place content.

### Admin/moderation web

-   **Next.js**
-   **React**
-   **TypeScript**

Keep separate from the consumer web app.

### Backend

-   **Java 25 LTS**
-   **Spring Boot**
-   Spring MVC for normal APIs.
-   Virtual threads where appropriate.
-   Avoid making the entire application reactive unless a measured
    requirement justifies it.

### Primary database

-   **PostgreSQL**
-   **PostGIS**

### Realtime / ephemeral state

-   **Redis**
-   **WebSockets**

### Object/media storage

-   **Amazon S3**
-   CDN via **CloudFront**

Clients should upload media directly to object storage using signed URLs
rather than proxying large media through the Java API.

### Maps

-   **Mapbox** is the preferred initial map platform because the Living
    Route requires substantial custom map visualization.

### Mobile local persistence

-   **SQLite**
-   Offline-first sync/queue design.

### API contracts

-   **OpenAPI**
-   Generate TypeScript clients rather than manually duplicating DTOs.

### Event streaming

-   **Kafka later**, when event volume and asynchronous fan-out justify
    it.

Define event contracts before Kafka is introduced.

### Search

-   **OpenSearch later** when search requirements justify it.

### Analytics

-   **ClickHouse later** for high-volume journey/event analytics if
    required.

### Cloud

Preferred unconstrained production target:

-   **AWS**
-   Docker.
-   Terraform.
-   Start with simple managed/container infrastructure.
-   Introduce EKS/Kubernetes only when operational scale/team needs
    justify it.

### Observability

-   OpenTelemetry.
-   Prometheus/Grafana where appropriate.
-   Centralized structured logging.
-   Error/crash monitoring for clients.
-   Distributed tracing as services grow.

### Push notifications

-   FCM for Android.
-   APNs for iOS.

------------------------------------------------------------------------

## 15. Architectural Style

### 15.1 Monorepo

Use a **monorepo**.

Reasons:

-   Mobile/web/admin/backend evolve together.
-   API contract changes often affect several applications.
-   Easier atomic changes.
-   Easier integration testing.
-   Better whole-system context for Codex.
-   Shared engineering documentation and contracts.
-   Simpler early-stage ownership.

### 15.2 Backend

Start with:

> **A domain-oriented modular monolith for core business functionality,
> plus separately deployable realtime and worker applications.**

Do not create many microservices in V1.

The architecture should allow future extraction when real
scaling/organizational boundaries appear.

### 15.3 Why realtime is separate

Realtime has different scaling characteristics from HTTP APIs:

-   Long-lived WebSocket connections.
-   Presence.
-   Route subscriptions.
-   Live traveller counts.
-   Route-room messages.
-   Reactions.
-   Live incident notifications.

It should be independently deployable/scalable while initially remaining
Java.

### 15.4 Workers

Use separate workers for asynchronous work such as:

-   AI analysis.
-   Moderation.
-   Push notifications.
-   Trip Journal generation.
-   Media-processing orchestration.
-   Route-event aggregation.
-   Ephemeral-room cleanup.
-   Privacy expiry.
-   Analytics export.

------------------------------------------------------------------------

## 16. Monorepo Structure

Use this target layout:

``` text
routiqo/
├── apps/
│   ├── mobile/                 # React Native + Expo
│   ├── web/                    # Consumer Next.js application
│   └── admin/                  # Admin/moderation Next.js application
│
├── packages/
│   ├── api-client/             # Generated TypeScript API client
│   ├── design-tokens/          # Brand colors/spacing/type/radii
│   ├── shared/                 # Shared TS utilities/domain helpers
│   ├── ui/                     # Share only sensible cross-app primitives
│   ├── validation/
│   └── config/                 # Shared TS/lint/build configuration
│
├── backend/
│   ├── core-api/               # Java Spring Boot modular monolith
│   ├── realtime/               # Java WebSocket/presence gateway
│   └── workers/                # Java async/background processing
│
├── contracts/
│   ├── openapi/
│   ├── events/
│   └── schemas/
│
├── infrastructure/
│   ├── terraform/
│   ├── docker/
│   ├── local/
│   └── monitoring/
│
├── docs/
│   ├── product/
│   ├── architecture/
│   ├── privacy/
│   ├── security/
│   ├── ai/
│   └── adr/
│
├── tests/
│   ├── integration/
│   ├── contract/
│   ├── e2e/
│   └── load/
│
├── scripts/
├── .github/
│   └── workflows/
│
├── AGENTS.md
├── README.md
├── Makefile
├── pnpm-workspace.yaml
├── turbo.json
└── docker-compose.yml
```

Rule:

> `apps/` = deployable TypeScript client applications.\
> `packages/` = reusable TypeScript libraries.\
> `backend/` = deployable Java applications.\
> `contracts/` = language-neutral API/event schemas.

Use:

-   **pnpm workspaces + Turborepo** for TypeScript
    applications/packages.
-   **Gradle Kotlin DSL** for new Java projects.
-   A top-level `Makefile` or equivalent simple commands to orchestrate
    both ecosystems.

Do not force Java builds into Turborepo.

------------------------------------------------------------------------

## 17. Mobile Source Organization

Prefer feature-oriented organization:

``` text
apps/mobile/
├── app/
├── src/
│   ├── features/
│   │   ├── auth/
│   │   ├── home/
│   │   ├── explore/
│   │   ├── journey/
│   │   ├── map/
│   │   ├── location/
│   │   ├── presence/
│   │   ├── route-updates/
│   │   ├── route-chat/
│   │   ├── places/
│   │   ├── journal/
│   │   ├── privacy/
│   │   └── profile/
│   ├── components/
│   ├── services/
│   ├── storage/
│   ├── hooks/
│   └── utils/
├── assets/
└── tests/
```

Use a reliable server-state library such as TanStack Query and keep
client-only state minimal. Choose a lightweight state solution only
where needed.

Do not build a giant global store for all application data.

------------------------------------------------------------------------

## 18. Java Core API Organization

Organize by business domain, **not** one global
controller/service/repository hierarchy.

Target domains:

``` text
com.routiqo
├── identity
├── user
├── journey
├── route
├── location
├── privacy
├── presence
├── place
├── update
├── chat
├── moderation
├── journal
├── notification
├── media
└── intelligence
```

Within a domain:

``` text
journey/
├── api/
├── application/
├── domain/
└── infrastructure/
```

Example:

``` text
journey/
├── api/
│   └── JourneyController.java
├── application/
│   ├── StartJourneyUseCase.java
│   └── CompleteJourneyUseCase.java
├── domain/
│   ├── Journey.java
│   ├── JourneyRepository.java
│   └── JourneyStatus.java
└── infrastructure/
    ├── JpaJourneyRepository.java
    └── JourneyEntity.java
```

Domain code should not depend directly on web controllers, databases,
Redis, Mapbox, or AI vendors.

------------------------------------------------------------------------

## 19. Location and Presence Architecture

Location is one of the most important subsystems.

Do not implement:

``` text
GPS → database → expose coordinates
```

Conceptual flow:

``` text
Phone GPS
    ↓
Client sampling / battery-aware collection
    ↓
Secure location ingestion
    ↓
Map/route matching
    ↓
Privacy transformation
    ↓
Approximate route segment / coarse spatial representation
    ↓
Ephemeral presence in Redis
    ↓
Aggregation / clustering
    ↓
WebSocket updates
    ↓
"128 travellers on this route"
```

### Data handling principles

-   Raw precise GPS is highly restricted.
-   Retain precise data only when genuinely necessary and for the
    minimum period required.
-   Do not use the relational database as a high-frequency GPS-update
    store.
-   Redis should hold short-lived presence.
-   PostgreSQL/PostGIS stores durable journey/route/domain state.
-   Social APIs should receive privacy-safe representations, not raw
    stranger coordinates.
-   Define retention/deletion policies explicitly before production.

------------------------------------------------------------------------

## 20. Realtime Architecture

`backend/realtime` handles:

-   WebSocket connections.
-   Authentication of socket sessions.
-   Journey/route subscriptions.
-   Route-room subscriptions.
-   Traveller-count updates.
-   Aggregated presence.
-   Route incidents.
-   Chat broadcasting.
-   Reactions.
-   Live journey alerts.

Redis can initially support:

-   Presence.
-   Pub/sub or streams where suitable.
-   Counters.
-   Rate limits.
-   Ephemeral room state.

Do not assume Redis Pub/Sub alone is the forever architecture. When
reliability/event replay/fan-out requirements demand it, introduce Kafka
or another durable event backbone.

------------------------------------------------------------------------

## 21. Data Architecture

### PostgreSQL/PostGIS owns durable domain state

Examples:

-   Users/accounts.
-   Journeys.
-   Routes.
-   Route segments.
-   Places.
-   Route updates.
-   Reports.
-   Journals.
-   Moderation records.
-   Privacy settings.
-   Saved places.

Use one database/cluster initially. Do not create a database per domain.

Logical schemas may be used where they improve clarity.

### Redis owns short-lived/high-frequency state

Examples:

-   Active journey presence.
-   Route traveller counts.
-   Ephemeral room membership.
-   Rate-limit counters.
-   Hot caches.
-   Short-lived aggregation state.

### S3 owns media

Examples:

-   Photos.
-   Voice snippets if introduced.
-   Generated journal media/assets.

Use signed direct uploads.

------------------------------------------------------------------------

## 22. API and Contract Strategy

Every public API must be represented in OpenAPI.

Flow:

``` text
Java API
   ↓
OpenAPI contract
   ↓
Generated TypeScript client
   ↓
Mobile / Web / Admin
```

Avoid manually maintaining duplicate request/response models in
TypeScript.

Use versioning and compatibility discipline.

### Event contracts

Define events even before Kafka is deployed.

Examples:

-   `JourneyStarted`
-   `JourneyCompleted`
-   `LocationSegmentChanged`
-   `RouteUpdateCreated`
-   `IncidentDetected`
-   `MessageCreated`
-   `UserReported`
-   `MediaUploaded`
-   `NotificationRequested`

Schemas belong under `contracts/events`.

------------------------------------------------------------------------

## 23. Offline-First Mobile

Travel frequently occurs with poor connectivity.

Mobile should use SQLite/local persistence for:

-   Active journey state.
-   Essential route state where licensing/provider terms permit.
-   Pending contributions.
-   Pending media metadata.
-   User preferences.
-   Sync queue.

Concept:

``` text
User action
   ↓
Local durable state
   ↓
Sync queue
   ↓
Network available?
   ├── No → retain/retry safely
   └── Yes → sync → reconcile
```

Use idempotency keys for retried writes.

Never lose an active journey merely because connectivity drops.

------------------------------------------------------------------------

## 24. Media Architecture

Do not proxy large uploads through the Spring API.

Preferred flow:

``` text
Client
  ↓ request signed upload
Core API
  ↓ signed URL
Client
  ↓ direct upload
S3
  ↓ event/worker
Processing / moderation / metadata
  ↓
CDN
```

Validate file type/size and perform security/moderation checks as
required.

------------------------------------------------------------------------

## 25. Authentication and Authorization

Design for:

-   Secure mobile/web authentication.
-   Short-lived access tokens.
-   Refresh-token rotation or equivalent secure session model.
-   Device/session management.
-   Apple/Google/phone login can be introduced based on launch
    requirements.
-   Role-based authorization for admin/moderators.
-   Object-level authorization for journeys, journals, reports, etc.
-   WebSocket authentication/authorization.

Never trust route/journey/user IDs supplied by the client without
authorization checks.

------------------------------------------------------------------------

## 26. Admin / Moderation Application

`apps/admin` should eventually support:

-   Report queues.
-   User enforcement.
-   Message/update review.
-   Safety incidents.
-   Route incident review.
-   Place management.
-   Feature flags.
-   Festival/event configuration.
-   Basic operational analytics.
-   Moderation audit history.

Do not mix admin capabilities into the consumer web application.

------------------------------------------------------------------------

## 27. Design System

The existing Lovable prototype is a **visual/UX reference**, not
production source code.

Codex should recreate the design cleanly rather than attempting to
reverse-engineer minified Lovable bundles.

Create design tokens:

``` text
packages/design-tokens/
├── colors
├── spacing
├── typography
├── radius
├── shadows
└── motion
```

Share tokens across mobile/web/admin.

Do not force all React Native and web UI components into a universal
component abstraction. Share only components/primitives where it
genuinely improves maintainability.

The desired visual personality is:

-   Warm.
-   Travel-oriented.
-   Modern.
-   Premium but approachable.
-   Indian/Tamil context where appropriate without becoming
    stereotypical.
-   Map/active journey screens more utility-dense than discovery/journal
    screens.

------------------------------------------------------------------------

## 28. Testing Strategy

Every feature requires appropriate automated verification.

### Backend

-   Unit tests.
-   Domain tests.
-   Repository integration tests.
-   API integration tests.
-   Security/authorization tests.
-   Privacy invariants.
-   WebSocket/realtime tests.

### Frontend

-   Unit/component tests where valuable.
-   Navigation/feature tests.
-   API contract tests.
-   Critical E2E flows.

### System

-   Contract tests.
-   End-to-end tests.
-   Load tests.
-   Failure/retry/offline tests.
-   Abuse/rate-limit tests.
-   Location privacy tests.

Critical flows include:

1.  Sign in.
2.  Create/start journey.
3.  Location permission denied.
4.  Location permission granted.
5.  Active route.
6.  Receive aggregate presence.
7.  Submit route update.
8.  Join/read route room.
9.  Report/block.
10. Ghost Mode.
11. Connectivity loss/recovery.
12. End journey.
13. Generate/view journal.

------------------------------------------------------------------------

## 29. Observability

Use structured logs with correlation IDs.

Instrument:

-   HTTP requests.
-   WebSocket sessions.
-   Database queries.
-   Redis.
-   AI calls.
-   Background jobs.
-   Push notification workflows.
-   External map/provider calls.

Monitor:

-   API latency/error rates.
-   Active WebSockets.
-   Presence-update rate.
-   Redis latency/memory.
-   Database latency/connections.
-   AI cost/latency/failure.
-   Moderation queues.
-   Mobile crash rate.
-   Journey start/completion.
-   Route-room health.

Do not log precise location or sensitive tokens indiscriminately.

------------------------------------------------------------------------

## 30. Infrastructure Evolution

### V1

Keep production architecture intentionally small:

``` text
React Native Mobile       Next.js Web/Admin
          \                    /
           \                  /
             Spring Boot API
                   |
        +----------+----------+
        |          |          |
   PostgreSQL    Redis        S3
    + PostGIS
        |
   Java Realtime Gateway

External:
- Mapbox
- Push providers
- AI provider
- CDN
```

Workers may run as a separate deployment as asynchronous requirements
appear.

### Growth stage

When measured requirements justify it:

``` text
Clients
   ↓
Edge/WAF/Load Balancing
   ↓
+----------+-----------+-----------+
| Core API | Realtime  | Workers   |
+----------+-----------+-----------+
             ↓
           Kafka
             ↓
+------------+------------+-------------+
| PostGIS    | Redis      | OpenSearch  |
+------------+------------+-------------+
                                ↓
                           ClickHouse
```

Possible future service extraction:

-   Journey.
-   Presence.
-   Chat.
-   Notifications.
-   Intelligence.
-   Media.

Do not split a module merely because it exists. Split only for
measurable scaling, reliability, deployment, security, or organizational
reasons.

------------------------------------------------------------------------

## 31. CI/CD and Developer Experience

The repository should be easy for a new developer or Codex to run.

Target:

``` bash
git clone ...
make bootstrap
make dev
```

Local dependencies should run through Docker Compose where practical:

-   PostgreSQL/PostGIS.
-   Redis.
-   S3-compatible local storage such as MinIO.
-   Supporting development tools.

CI should be path-aware.

Examples:

-   Mobile-only change → mobile checks.
-   Backend change → Java build/tests.
-   Contract change → backend + affected clients + contract tests.
-   Infrastructure change → Terraform validation/plan checks.

Before merging:

-   Formatting.
-   Linting.
-   Type checking.
-   Unit tests.
-   Relevant integration tests.
-   Dependency/security scanning.
-   Build verification.

------------------------------------------------------------------------

## 32. Engineering Rules for `AGENTS.md`

Create a root `AGENTS.md` containing at least these invariants:

1.  Product name is **Routiqo**.
2.  Mobile is React Native + Expo + TypeScript.
3.  Consumer web/admin use Next.js + TypeScript.
4.  Backend uses Java 25 LTS + Spring Boot.
5.  Core backend is a domain-oriented modular monolith.
6.  Do not introduce a new microservice without an ADR and concrete
    justification.
7.  Organize backend code by business domain, not one global
    technical-layer hierarchy.
8.  PostgreSQL/PostGIS owns durable domain/geospatial state.
9.  Redis owns ephemeral presence/hot realtime state.
10. Precise user location must never be exposed to strangers/public
    APIs.
11. Social presence must be privacy transformed, aggregated,
    fuzzed/coarsened, and time limited as appropriate.
12. Exact home/work locations must not be exposed through social
    features.
13. Route rooms are ephemeral.
14. Unrestricted DMs are not part of V1.
15. Live group calling is not part of V1.
16. Every public API is defined in OpenAPI.
17. TypeScript API clients are generated from contracts.
18. Media uploads use signed direct-to-object-storage flows.
19. Mobile must tolerate intermittent connectivity.
20. Retried writes must be idempotent where applicable.
21. AI vendor integrations must sit behind internal interfaces.
22. AI-generated incident claims must represent uncertainty/provenance
    appropriately.
23. Safety uses layered controls: rate limits + rules + automated
    moderation + reports + human escalation.
24. New features require tests.
25. Security/authorization must never be bypassed for convenience.
26. Never log secrets, tokens, or unnecessary precise location data.
27. Prefer the simplest implementation that satisfies current
    requirements.
28. Kafka/OpenSearch/ClickHouse/Kubernetes are not V1 defaults.
29. Architectural decisions with lasting consequences should be recorded
    under `docs/adr`.
30. A task is not complete until relevant automated verification passes.

------------------------------------------------------------------------

# IMPLEMENTATION

## 33. Recommended Build Sequence

Codex should **not** attempt to build the entire product in one giant
change.

### Phase 0 --- Foundation

Create:

-   Monorepo.
-   Tooling.
-   `AGENTS.md`.
-   Architecture docs.
-   ADR structure.
-   OpenAPI setup.
-   Docker Compose development environment.
-   Basic CI.
-   Design tokens.
-   Mobile/web/admin shells.
-   Spring Boot applications.

### Phase 1 --- Identity and profile

Implement:

-   Authentication foundation.
-   User profile.
-   Privacy defaults.
-   Device/session management.

### Phase 2 --- Home and discovery

Implement:

-   Home.
-   Destination search shell.
-   Saved places foundation.
-   Commute card.
-   Start Journey CTA.
-   Explore foundation.

### Phase 3 --- Journey domain

Implement:

-   Journey creation.
-   Daily commute model.
-   Trip model.
-   Start/end journey.
-   Active journey state.
-   Journey history.

### Phase 4 --- Map and routing

Implement:

-   Mapbox.
-   Route display.
-   YOU marker.
-   Destination.
-   Route/town/road context.
-   Basic place markers.

### Phase 5 --- Privacy-safe location and presence

Implement:

-   Location permission handling.
-   Sampling.
-   Backend ingestion.
-   Route matching.
-   Privacy transformation.
-   Redis presence.
-   Aggregated traveller count.
-   Ghost Mode.
-   Expiry.
-   Privacy tests.

This phase must be completed carefully before exposing social presence.

### Phase 6 --- Realtime

Implement:

-   WebSocket gateway.
-   Authenticated connections.
-   Journey/route subscriptions.
-   Presence counts.
-   Live route events.
-   Reconnect/recovery.

### Phase 7 --- Route Updates

Implement:

-   Structured contribution.
-   Categories.
-   Map rendering.
-   Freshness/expiry.
-   Confirmation/corroboration.
-   Reporting.

### Phase 8 --- Route Rooms

Implement:

-   Ephemeral room lifecycle.
-   Membership/subscriptions.
-   Text.
-   Reactions.
-   Rate limiting.
-   Block/report.
-   Moderation pipeline.
-   Safety telemetry.

Do not add DMs/live calls.

### Phase 9 --- Places

Implement:

-   Places along route.
-   Save place.
-   Route deviation/relevance.
-   Useful-stop UI.

### Phase 10 --- Trip Journal

Implement:

-   Completed trip data.
-   Stops/photos.
-   Journal UI.
-   Shareable summary foundation.
-   Periodic commute summaries separately.

### Phase 11 --- Journey Intelligence V1

Start with high-value, constrained AI:

1.  Route-room/update summarization.
2.  Natural-language → structured route update extraction.
3.  Relevance ranking.
4.  Multilingual understanding/translation where useful.
5.  Moderation assistance.

Then add:

-   Event inference/corroboration.
-   Predictions.
-   Personalized departure insights.
-   Context-aware Ask Routiqo.
-   Place intelligence.

### Phase 12 --- Offline hardening

Implement:

-   SQLite persistence.
-   Sync queue.
-   Retry.
-   Idempotency.
-   Connectivity transitions.
-   Offline journey continuity.

### Phase 13 --- Admin and moderation

Implement production moderation workflows before broad public launch.

### Phase 14 --- Production hardening

-   Load tests.
-   Security review.
-   Privacy review.
-   Abuse testing.
-   Observability.
-   Backup/recovery.
-   Retention/deletion.
-   Cost monitoring.
-   App-store readiness.
-   Operational runbooks.

------------------------------------------------------------------------

## 34. V1 Feature Boundary

### Must have

-   Mobile app.
-   Basic consumer web presence/application where useful.
-   Authentication.
-   Home/Discovery.
-   Daily commute + trip journeys.
-   Living Route.
-   YOU marker.
-   Privacy-safe traveller count/presence.
-   Route Updates.
-   Temporary Route Room text/reactions.
-   Block/report.
-   Ghost Mode.
-   Places/saved places foundation.
-   Journey completion/history.
-   Basic Trip Journal.
-   AI summarization/extraction/relevance/moderation assistance.
-   Offline-safe active journey basics.
-   Admin moderation foundation.
-   Analytics/observability.

### Not V1

-   Unrestricted DMs.
-   Live group audio/video calls.
-   AR.
-   Scavenger hunts.
-   Complex gamification.
-   Influencer/follower system.
-   Like-driven social feed.
-   Public exact user dots.
-   Public location history.
-   Large microservice fleet.
-   Kafka merely for architecture aesthetics.
-   Kubernetes merely for architecture aesthetics.
-   Complex recommendation ML before sufficient data exists.

------------------------------------------------------------------------

## 35. Product Success Criterion

The product should not depend on users wanting to talk to strangers.

The hierarchy is:

> **Utility → participation → community.**

A successful Routiqo experience can be:

1.  User starts their commute.
2.  Routiqo says there are hundreds of travellers sharing the corridor.
3.  It surfaces three useful things happening ahead.
4.  The user avoids a delay or finds a useful stop.
5.  They never send a chat message.
6.  They open Routiqo again tomorrow.

That is already a successful core loop.

Community features should strengthen that utility rather than replace
it.

------------------------------------------------------------------------

## 36. Long-Term Moat

If Routiqo achieves meaningful adoption, its strongest asset may become
its privacy-safe **journey intelligence dataset**:

``` text
Route
Time
Direction
Aggregate speed
Congestion
Stops
Weather
Community reports
Places
Events
Traveller density
Journey outcomes
```

Combined with AI, this can produce increasingly useful:

-   Real-time incident detection.
-   Route intelligence.
-   Predictions.
-   Contextual place recommendations.
-   Crowd/festival insights.
-   Personalized journey briefs.

The network effect is:

``` text
More travellers
    ↓
More privacy-safe signals
    ↓
Better journey intelligence
    ↓
More useful journeys
    ↓
More returning travellers
```

The reverse cold-start loop is equally important and must be actively
managed through corridor/event-focused launches.

------------------------------------------------------------------------

## 37. Codex Working Method

Codex should work incrementally.

For each substantial task:

1.  Read `AGENTS.md` and relevant docs.
2.  Inspect existing code before changing architecture.
3.  State/record acceptance criteria.
4.  Implement the smallest coherent change.
5.  Add/update tests.
6.  Run relevant formatting/lint/type/build/test checks.
7.  Review the diff for security/privacy regressions.
8.  Fix failures.
9.  Update OpenAPI/docs/ADR when required.
10. Report what changed, verification performed, and any unresolved
    risks.

Do not silently weaken privacy, security, testing, or module boundaries
to make a feature easier to implement.

------------------------------------------------------------------------

## 38. Immediate Codex Starting Task

After reading this document, Codex should **not immediately implement
every feature**.

The first task should be to create or validate:

1.  The target monorepo structure.
2.  Root `AGENTS.md`.
3.  `README.md`.
4.  Architecture overview.
5.  Initial ADRs:
    -   Monorepo.
    -   Java/Spring Boot backend.
    -   Modular monolith.
    -   Separate realtime gateway.
    -   PostgreSQL/PostGIS.
    -   Redis presence.
    -   Privacy-safe location architecture.
    -   OpenAPI contract-first boundary.
6.  TypeScript workspace with pnpm/Turborepo.
7.  Java Gradle workspace.
8.  Local Docker Compose with PostGIS + Redis + object-storage emulator.
9.  Skeleton mobile/web/admin applications.
10. Skeleton core-api/realtime/workers applications.
11. Initial CI.
12. A staged implementation plan based on Section 33.

Before adding production functionality, ensure the repository can be
cloned, bootstrapped, built, tested, and run locally with simple
documented commands.

------------------------------------------------------------------------

# Final Product Statement

**Routiqo is a privacy-first living route platform that helps people
understand what is happening around their journey by combining maps,
aggregate traveller presence, community updates, temporary route
conversations, useful places, and AI-powered journey intelligence.**

It serves both the **daily commute** and the **occasional trip**.

Routiqo should feel useful even when the user never interacts with
another traveller.

The core experience is:

> **See your route. Understand what is happening ahead. Benefit from the
> collective experience of people travelling with you. Contribute when
> useful. Stay private. Remember meaningful journeys.**

The engineering system must preserve that product promise at every
layer.
