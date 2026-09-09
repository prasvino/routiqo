# ADR0014: Initial route provider boundary

Status: accepted for validation layer, transport/UI pending,2026-09-09.

Follow the existing product preference for Mapbox. Normalize only validated GeoJSON route coordinates, distance and duration into a provider-independent result. Initially support two endpoints and driving/walking/cycling. Missing credentials, NoRoute and unavailable service must remain distinct; never invent route geometry or time.

Keep exact endpoints private and out of social presence, logs and automatic backup. No provider request is made by the current shared validators. Live transport must sit behind authenticated rate-limited access with a configured token, bounded responses and cancellation before it is exposed in the product.

Reference: https://docs.mapbox.com/api/navigation/directions/ (checked2026-09-09).
