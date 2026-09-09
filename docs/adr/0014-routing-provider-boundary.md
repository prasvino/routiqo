# ADR0014: Initial route provider boundary

Status: accepted; backend and browser transport implemented, UI and live configuration pending, 2026-09-09.

Follow the existing product preference for Mapbox. Normalize only validated GeoJSON route coordinates, distance and duration into a provider-independent result. Initially support two endpoints and driving/walking/cycling. Missing credentials, NoRoute and unavailable service must remain distinct; never invent route geometry or time.

Keep exact endpoints private and out of social presence, logs and automatic backup. The opt-in `routing` profile adds an authenticated POST endpoint with the existing session, exact origin, CSRF and account-context guards. A database-backed per-account limit permits 20 requests per minute, in addition to the existing peer limit. The provider uses a fixed Mapbox host, refuses redirects, bounds responses at 1 MiB and times out. External calls occur outside database transactions. Provider errors omit their URL, body and token.

The same-origin web proxy and explicit browser calculator preserve account binding, no-store and size limits. The browser validates normalized geometry and supports caller cancellation without retrying or persisting exact endpoints. No route selection UI is mounted yet, no provider credentials are configured and no live request has been verified. Native navigation and offline map packages remain separate work.

Reference: https://docs.mapbox.com/api/navigation/directions/ (checked2026-09-09).
