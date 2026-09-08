# Observability
Foundation: minimal service health; no internal exceptions, environment details, secrets or locations in public health responses.
Future metrics cover request latency, socket subscriptions, queue failures, Redis/DB, moderation, external providers and client errors. Correlation IDs are not user tracking IDs.
Do not send raw GPS to analytics or error monitoring. Provider setup remains pending.

