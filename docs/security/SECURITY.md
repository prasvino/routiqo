# Security
Authentication, object authorization, input bounds, secret hygiene and moderation are correctness requirements.
Foundation has no consumer auth bypass: protected HTTP paths are denied; admin reveals no records. Tokens and precise location are absent from fixtures/logs.
CI must enforce types, contracts, security/privacy tests and secret scanning. Review new providers and dependencies in ADRs.

