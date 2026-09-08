# Upcoming local plans

## Goal and scope
Surface the next planned departure on Home and order Trips by upcoming departures. Support one-time trips and weekly commutes using existing local storage on web and SQLite on mobile. No new server writes, permissions, providers, or notifications.

## Scheduling rules
- Dates and times use the device's current local timezone, as existing plans do. Show this limitation in the interface; travel across timezones changes interpretation.
- A commute starts no earlier than its first-departure date and repeats only on selected weekdays. A passed departure rolls to the next selected day.
- A trip whose departure has passed remains editable under earlier plans; never imply it was started or completed.
- A departure in the current minute remains upcoming until that minute ends.
- Refresh while open once per minute and when web visibility/native app activity resumes.
- For daylight-saving gaps, skip nonexistent local times; do not silently move a 02:30 departure to 03:30. Repeated local times use the platform's earlier offset. No background alarms are created.
- Stable sort by departure then plan ID; do not mutate storage while deriving schedules.

## Interface
Visual thesis: a quiet route row within existing ivory/teal surfaces.
Content: next departure, route, local-time explanation, edit action; chronological plan list with earlier plans retained.
Interaction: edit reuses existing accessible dialog, focus returns on close, no new decorative motion.

## Reliability, privacy and acceptance
Bounded by the existing 100-plan limit. Pure scheduling helper shared across apps, no location/network access and no new retention. Test weekday/year rollover, future start dates, exact-minute boundary, passed trips, sorting, and no mutation. Verify web save/edit, recurring next departure and phone layout. Native runtime verification remains deferred until Android tooling is available.
