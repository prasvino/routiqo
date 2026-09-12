# Routiqo --- UI/UX & Performance System for Codex

> **Purpose:** This document is the permanent UI/UX constitution for
> Routiqo. It complements `ROUTIQO_MASTER_CONTEXT.md` and
> `ROUTIQO_CODEX_ENGINEERING_GUARDRAILS.md`.
>
> Codex must use these principles when implementing or reviewing any
> user-facing experience. The objective is a **world-class consumer
> product that feels calm, premium, intuitive, accessible, and
> exceptionally fast**---not a generic AI-generated interface.

------------------------------------------------------------------------

## 1. Product Experience Goal

Routiqo should feel like **calm travel intelligence**.

The interface should be:

-   Lightning fast.
-   Easy on the eyes.
-   Simple to understand without instructions.
-   Premium but approachable.
-   Visually consistent.
-   Excellent for one-handed mobile use.
-   Useful with minimal interaction.
-   Accessible.
-   Reliable on mid-range devices and unreliable networks.
-   Information-efficient during active journeys.
-   Emotionally richer when browsing trips, places, and memories.

The product hierarchy remains:

``` text
Journey
  ↓
Map
  ↓
What's happening
  ↓
People
  ↓
Conversation
  ↓
Memories
```

Do not turn Routiqo into a generic social feed.

------------------------------------------------------------------------

## 2. Design Philosophy

### Discovery / stationary experience

When the user is not actively travelling, the UI may be more expressive:

-   Beautiful destination imagery.
-   Generous whitespace.
-   Strong typography.
-   Warm visual personality.
-   Subtle motion.
-   Inspiring destination and journey cards.

### Active journey experience

When travelling, prioritize utility:

-   Immediate readability.
-   Strong visual hierarchy.
-   Clear route and destination.
-   Large touch targets.
-   Minimal typing.
-   One-handed interaction.
-   High-value information only.
-   Minimal visual noise.
-   Stable map performance.

An active-journey screen must not become a design showcase at the
expense of usability.

------------------------------------------------------------------------

## 3. Design-System-First Development

Codex must not independently invent styling for every screen.

Use this hierarchy:

``` text
Routiqo Design Language
        ↓
Design Tokens
        ↓
Core Components
        ↓
Interaction Patterns
        ↓
Screen Compositions
```

Define and reuse:

-   Colors.
-   Typography.
-   Spacing.
-   Corner radii.
-   Elevation/shadows.
-   Iconography.
-   Motion.
-   Touch-target sizes.
-   Buttons.
-   Cards.
-   Chips.
-   Bottom sheets.
-   Inputs.
-   Navigation.
-   Loading skeletons.
-   Empty states.
-   Error states.
-   Map markers.
-   Traveller clusters.
-   Route incidents.
-   Route-update cards.
-   Journey cards.

Avoid arbitrary values scattered throughout components.

------------------------------------------------------------------------

## 4. Design Tokens

Maintain shared tokens under:

``` text
packages/design-tokens/
├── colors
├── typography
├── spacing
├── radius
├── shadows
├── motion
└── breakpoints
```

Mobile, consumer web, and admin should share the same design language.

Do not force React Native and Next.js to share every UI component. Share
tokens and appropriate primitives while allowing platform-native
implementations.

------------------------------------------------------------------------

## 5. Component Strategy

Prefer reusable semantic components such as:

``` text
<RoutiqoJourneyCard />
<RouteStatusChip />
<TravellerCluster />
<RouteIncidentCard />
<RouteBottomSheet />
<RouteUpdateCard />
<PrimaryCTA />
<PrivacyIndicator />
<JourneyBrief />
```

Components should have intentional variants rather than arbitrary
per-screen styling.

Every reusable component must consider:

-   Normal.
-   Pressed/active.
-   Disabled.
-   Loading.
-   Error where relevant.
-   Long text.
-   Accessibility text scaling.
-   Different screen sizes.

------------------------------------------------------------------------

## 6. Visual Style

Desired personality:

-   Warm.
-   Modern.
-   Premium.
-   Calm.
-   Travel-oriented.
-   Trustworthy.
-   Human.
-   Appropriate for an Indian audience without relying on stereotypes.

Avoid common low-quality or AI-generated UI patterns:

-   Excessive gradients.
-   Glassmorphism everywhere.
-   Excessive shadows.
-   Too many floating cards.
-   Random pill-shaped controls.
-   Too many colors.
-   Giant hero elements that reduce useful content.
-   Decorative animation on everything.
-   Excessive map movement.
-   Dense social-media-style feeds.
-   Inconsistent spacing/radii.
-   Novel controls when standard interaction patterns are clearer.

Every decorative element must justify its visual cost.

------------------------------------------------------------------------

## 7. Information Hierarchy

Each screen must have an obvious primary purpose.

For the Living Route, the user should immediately understand:

1.  Where am I?
2.  Where am I going?
3.  What is happening ahead?
4.  What matters to me now?
5.  How active is this route?

Do not display ten elements with equal visual importance.

Prioritize:

``` text
Safety / critical journey information
        ↓
Immediate route intelligence
        ↓
Navigation/context
        ↓
Useful places
        ↓
Community activity
        ↓
Secondary actions
```

------------------------------------------------------------------------

## 8. One-Handed Mobile UX

Routiqo is frequently used while moving. Important actions should
generally be reachable in the lower portion of the screen.

Preferred pattern:

``` text
+--------------------------+
|                          |
|           MAP            |
|                          |
|    journey information   |
|                          |
+--------------------------+
|      Bottom Sheet        |
|  What's Ahead | Places   |
|  Updates      | Chat     |
|                    [+]   |
+--------------------------+
```

Avoid requiring frequent top-left reach.

Primary journey actions should use large, forgiving touch targets.

------------------------------------------------------------------------

## 9. Fast Contributions

Route contributions should require very little effort.

Example:

``` text
+
↓
Traffic
↓
Heavy
↓
Submit
```

Common updates should ideally require only a few taps and no keyboard.

Use structured input before free-form text where it improves speed,
moderation, and data quality.

Target common contribution completion time:

> **Approximately 5--10 seconds.**

------------------------------------------------------------------------

## 10. Map UX

The map is the primary active-journey surface and must remain readable
and performant.

Show:

-   Strong YOU marker.
-   Route geometry.
-   Destination.
-   Relevant geographic context.
-   Privacy-safe traveller clusters.
-   Important incidents.
-   Useful places.
-   Route status.

Do not expose exact stranger locations.

Use progressive disclosure as zoom changes.

Avoid marker overload. Aggregate/clustering is preferred.

High-frequency presence changes must not cause full-map rerenders.

The map should remain interactive while realtime updates arrive.

------------------------------------------------------------------------

## 11. Performance Is Part of UX

Performance is a design requirement, not only an engineering concern.

Codex must avoid:

-   Unnecessary rerenders.
-   Large global state updates.
-   High-frequency GPS data propagating through the whole UI.
-   Unbounded lists.
-   Original-size images used as thumbnails.
-   Expensive synchronous work on interaction paths.
-   Excessive animation/layout work.
-   Re-rendering all map markers for every presence update.

Preferred location flow:

``` text
GPS
 ↓
Location Service
 ↓
Derived Route/Presence State
 ↓
Only UI That Needs It
```

Avoid:

``` text
GPS every second
 ↓
Global App State
 ↓
Whole Application Rerender
```

------------------------------------------------------------------------

## 12. Mobile Performance Practices

Use appropriate techniques such as:

-   Isolate transient/high-frequency state.
-   Memoize expensive derived UI where measurement supports it.
-   Virtualize potentially large lists.
-   Optimize image dimensions and caching.
-   Coalesce/batch realtime updates.
-   Lazy-load non-critical content.
-   Avoid blocking the JS thread.
-   Use platform/native-optimized animation paths.
-   Keep navigation responsive while data loads.
-   Use local cached state where appropriate.
-   Avoid unnecessary network requests.
-   Prefetch only when it has clear UX value.

Performance optimizations must be measured rather than applied blindly.

------------------------------------------------------------------------

## 13. Performance Targets

Treat these as initial experience targets; refine them using
measurements on representative devices.

### Interaction

-   User actions should produce visible feedback immediately.
-   Typical input response should feel below roughly 100 ms where
    practical.
-   Navigation should feel immediate even if destination data is still
    loading.

### Animation/map

-   Target smooth 60 FPS during normal scrolling, transitions, and map
    interaction on supported devices.
-   Presence updates must not visibly stall map gestures.
-   Avoid large frame drops during route updates.

### Loading

Prefer:

``` text
Immediate screen shell
       ↓
Skeleton/cached content
       ↓
Fresh data
```

over:

``` text
Blank screen
       ↓
Spinner
       ↓
Everything appears
```

### Images

-   Request/display appropriately sized images.
-   Use thumbnails for cards/lists.
-   Cache intelligently.
-   Lazy-load offscreen media.

### Realtime

-   Coalesce high-frequency events where appropriate.
-   Update only affected UI.
-   Do not rerender the entire active-journey tree for every event.

------------------------------------------------------------------------

## 14. Target Devices

Do not optimize only for flagship iPhones or desktop browsers.

Primary performance validation should include:

-   Representative mid-range Android device.
-   Smaller Android screen.
-   Modern iPhone.
-   Slow/intermittent mobile network.
-   Lower-memory conditions where practical.

India-focused launch quality should be judged heavily on Android
performance.

------------------------------------------------------------------------

## 15. Loading, Empty, Error and Offline States

A screen is not complete after its happy path looks good.

Every applicable feature must consider:

-   Initial loading.
-   Refreshing.
-   Empty.
-   Partial data.
-   Error.
-   Offline.
-   Reconnecting.
-   Permission denied.
-   Stale cached data.
-   Long text.
-   Accessibility text scaling.

Routiqo-specific states include:

``` text
0 travellers
3 travellers
300 travellers
3,000+ travellers

No incidents
One incident
Many incidents

GPS unavailable
GPS permission denied
Weak location signal

Journey paused
Journey completed

Ghost Mode enabled

Realtime disconnected
Realtime reconnecting

No route updates
No route conversation
```

Empty states should remain useful and should not make Routiqo feel
broken during cold-start conditions.

------------------------------------------------------------------------

## 16. Perceived Performance

Use UX techniques that make real latency less disruptive without hiding
failures.

Prefer:

-   Immediate button feedback.
-   Optimistic UI only where safe.
-   Skeletons.
-   Cached/stale-while-refresh content.
-   Progressive rendering.
-   Background synchronization.
-   Stable layouts that do not jump.

Do not use artificial delays merely to make animations visible.

Do not show blocking spinners for operations that can safely continue in
the background.

------------------------------------------------------------------------

## 17. Motion

Motion should communicate:

-   State change.
-   Spatial relationship.
-   Priority.
-   Success.
-   Navigation.

Good examples:

-   Natural bottom-sheet motion.
-   Subtle journey-start transition.
-   Gentle traveller-count update.
-   Brief incident-arrival attention cue.
-   Spatial cluster expansion.
-   Clear Ghost Mode privacy transition.

Avoid:

-   Constant bouncing.
-   Decorative looping animation.
-   Excessive parallax.
-   Large motion during active travel.
-   Animating every component on entry.
-   Motion that competes with safety-critical information.

Respect reduced-motion accessibility preferences.

------------------------------------------------------------------------

## 18. Accessibility

Accessibility is a quality requirement.

Support:

-   Dynamic text/font scaling.
-   Strong contrast.
-   Screen-reader labels.
-   Logical focus/navigation order.
-   Keyboard navigation on web.
-   Sufficient touch targets.
-   Reduced-motion preferences.
-   Meaning beyond color alone.
-   Accessible form validation.
-   Meaningful labels for map controls.

Target approximately 44--48 dp minimum touch areas for important mobile
interactions where practical.

Test large text rather than merely claiming support.

------------------------------------------------------------------------

## 19. Typography and Readability

Typography must create hierarchy without excessive font sizes or
weights.

Prioritize:

-   Easy scanning.
-   Comfortable line length.
-   Clear numeric information.
-   Strong distinction between critical route status and secondary
    metadata.

During active journeys, prioritize legibility over decorative
typography.

Do not use very small text for information the user may need while
moving.

------------------------------------------------------------------------

## 20. Navigation

Navigation must remain predictable.

Consumer mobile primary navigation should approximately follow:

``` text
Home | Explore | Trips | Profile
```

Do not create a permanent Chat tab. Route conversation is contextual to
an active journey.

Avoid deep navigation hierarchies.

The user should always understand:

-   Where they are.
-   Whether a journey is active.
-   How to return to the active journey.

------------------------------------------------------------------------

## 21. Visual QA Is Mandatory

Codex must not declare a substantial UI feature complete based only on
compilation/tests.

Required loop:

``` text
Implement
   ↓
Run
   ↓
Navigate through real UI
   ↓
Capture screenshots
   ↓
Inspect visually
   ↓
Exercise interactions
   ↓
Compare with Routiqo design system/reference
   ↓
Fix
   ↓
Repeat
```

Functional QA and visual QA are separate responsibilities.

For every major screen, inspect:

-   Alignment.
-   Spacing.
-   Hierarchy.
-   Typography.
-   Overflow.
-   Clipping.
-   Touch targets.
-   Loading transitions.
-   Keyboard behavior.
-   Bottom-sheet behavior.
-   Map overlays.
-   Safe areas.
-   Small-screen behavior.
-   Long-content behavior.

A UI feature is incomplete until visual QA has passed.

------------------------------------------------------------------------

## 22. Recommended Codex Skills

Keep the UI skill set small and purposeful.

Recommended:

### 1. Playwright

Use for:

-   Browser functional QA.
-   User-flow testing.
-   Responsive validation.
-   Screenshot-based visual review.
-   Interaction verification.

### 2. Screenshot capability/skill

Use for:

-   Capturing rendered states.
-   Visual comparison.
-   Layout inspection.

### 3. One high-quality UI/design-system skill

Use one---not many overlapping design skills---to reinforce:

-   Visual hierarchy.
-   Design-system discipline.
-   Accessibility.
-   Responsive design.
-   Component consistency.

### 4. Routiqo-specific UI skill

Create a project-specific skill such as:

``` text
.codex/
└── skills/
    └── routiqo-ui/
        ├── SKILL.md
        └── references/
            ├── DESIGN_SYSTEM.md
            ├── UX_PRINCIPLES.md
            ├── MOBILE_PERFORMANCE.md
            ├── MAP_UX.md
            └── VISUAL_QA.md
```

The custom skill should direct Codex to this document and the approved
Routiqo visual references.

Avoid installing many overlapping UI skills because conflicting
instructions can reduce consistency.

------------------------------------------------------------------------

## 23. Visual Reference Library

Keep approved screenshots/reference designs under a predictable
location, for example:

``` text
docs/design/reference/
├── home-approved.png
├── journey-approved.png
├── route-update-sheet-approved.png
├── route-chat-approved.png
├── journal-approved.png
└── ...
```

The existing Lovable prototype should be treated as **visual/UX
reference**, not production architecture or source code.

Instruction to Codex:

> Preserve the approved Routiqo design language and interaction intent.
> Recreate it using production-quality components and architecture; do
> not blindly copy prototype implementation details.

As screens mature, replace early references with approved Routiqo
production screenshots.

------------------------------------------------------------------------

## 24. UI Review Matrix

Before accepting a major screen, review at minimum:

  Dimension          Check
  ------------------ -----------------------------------------------
  Visual hierarchy   Primary action/content is immediately obvious
  Consistency        Uses established tokens/components
  Performance        Smooth on target devices
  Accessibility      Text, contrast, touch, screen-reader behavior
  Responsiveness     Small/large screens work
  Loading            Immediate useful feedback
  Empty state        Still understandable/useful
  Error state        Recoverable and clear
  Offline            Appropriate degraded experience
  Long text          No clipping/layout breakage
  Realtime           Updates without visual instability
  Privacy            No accidental location/user exposure
  One-hand use       Key mobile actions reachable
  Motion             Purposeful and restrained

------------------------------------------------------------------------

## 25. UI Performance Architecture

High-frequency systems must be isolated from ordinary application state.

Conceptually:

``` text
Location / Realtime Events
          ↓
Specialized services/stores
          ↓
Derived/coalesced state
          ↓
Affected map/route components only
```

Do not make every GPS or WebSocket event update a giant application-wide
state object.

Separate:

-   Server state.
-   Durable local state.
-   UI state.
-   High-frequency transient state.

Choose state-management tools based on these roles rather than placing
everything into one store.

------------------------------------------------------------------------

## 26. UX Metrics

As Routiqo matures, measure experience rather than relying only on
opinion.

Useful metrics include:

-   App startup time.
-   Time to interactive.
-   Journey-start completion time.
-   Time to first useful route signal.
-   Route-map frame performance.
-   Crash-free sessions.
-   UI/API error rate.
-   Route contribution completion time.
-   Contribution abandonment.
-   Permission-denial impact.
-   Realtime reconnect success.
-   User dismissal of journey alerts.
-   Repeat journey usage.
-   Accessibility-related defects.

Performance regressions should be visible in engineering telemetry where
practical.

------------------------------------------------------------------------

## 27. Feature UI Specification

Before building a significant UI feature, document:

``` markdown
# Feature UI Specification

## User Goal
What is the user trying to accomplish?

## Primary Action
What should be most visually obvious?

## Information Hierarchy
What is primary, secondary and optional?

## States
Loading, normal, empty, error, offline, reconnecting, etc.

## Interaction
Tap/swipe/scroll/map/bottom-sheet behavior.

## One-Handed Use
Can the important actions be reached comfortably?

## Accessibility
Text scaling, labels, contrast, reduced motion.

## Performance
Potential rerender/list/image/map/realtime risks.

## Privacy
What user/location information appears?

## Reference
Approved screenshot/design reference.

## Acceptance Criteria
Concrete functional + visual + performance definition of done.
```

------------------------------------------------------------------------

## 28. Codex UI Definition of Done

A UI task is complete only when:

1.  Functional acceptance criteria pass.
2.  It uses the established Routiqo design system.
3.  No unnecessary one-off visual primitives were introduced.
4.  Loading/empty/error/offline states are handled where applicable.
5.  Accessibility requirements are considered and tested.
6.  Small and representative device sizes are checked.
7.  High-frequency updates do not create unnecessary rerenders.
8.  Images/media are appropriately optimized.
9.  Privacy rules are preserved.
10. Visual QA was performed on the rendered application.
11. Relevant screenshots were inspected.
12. Automated tests/checks pass.
13. No obvious UX defect remains that would be embarrassing in
    production.

------------------------------------------------------------------------

# 29. Final Rule

Routiqo should never optimize for a beautiful static screenshot at the
expense of the actual journey experience.

The desired outcome is:

> **Beautiful when viewed. Obvious when used. Calm while travelling.
> Instant when touched. Reliable when connectivity is poor. Private by
> design.**

World-class UI is produced by the combination of:

``` text
Strong product hierarchy
        +
Consistent design system
        +
Platform-appropriate components
        +
Performance-aware architecture
        +
Accessibility
        +
Real-device testing
        +
Mandatory visual QA
        +
Continuous iteration
```

Do not ask Codex merely to "make it beautiful." Give it these
constraints, make it run the real product, inspect what it built,
measure performance, and iterate until both the visual and interaction
quality meet Routiqo standards.

## Routiqo Live first-release composition (planned)

Canonical scope: `docs/features/live/ROUTIQO_LIVE_SPEC.md`. Place a glanceable LIVE
list inside the active journey; preserve the four primary tabs. Prioritize situation,
reported condition, coarse freshness and uncertainty, followed by optional structured
contribution. Use text/icon labels as well as color. No avatar-heavy feed, count
badges, endless scroll, fake activity or new map query surface. Show loading,
insufficient evidence, stale/offline, expiry and submission uncertainty distinctly.
Do not announce freshness every second or send prompts merely to drive engagement.
No proactive driving prompts; one-tap input is not automatically safe for drivers.
List/map later share the same projection and selection semantics. Apply existing
keyboard, touch target, large-text, reduced-motion and screen-reader standards.
