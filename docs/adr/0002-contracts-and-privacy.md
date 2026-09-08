# ADR 0002: Generated contracts and closed integration boundaries
Status: accepted, 2026-09-06.
HTTP schemas are language-neutral OpenAPI; generate TypeScript types with openapi-typescript and use openapi-fetch. Java HTTP tests check response shapes; contract drift checked in CI.
Foundation exposes only health and curated discovery content. Do not add dev tokens or anonymous protected writes to make a demo work.
Local drafts are intentionally distinct from live journeys. No GPS is sent; no fake traveller counts.
Presence is a pure domain policy until authentication, distributed state and abuse protections are implemented. Unit tests alone do not authorize a public presence API.

