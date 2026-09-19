# Authorization
Foundation denies all undeclared protected paths, independent of client-provided IDs.
Future authorization applies at object/action/subscription level using verified actor identity. A valid socket is not permission to subscribe to arbitrary rooms. Room location claims are not proof of membership. Admin access requires separate policy and audit.
Tests must cover cross-user journey access, expired rooms, block enforcement and revoked sessions.

## Internal contribution moderation (ADR 0041)

The audited restriction boundary requires two current enabled accounts and an
unexpired database permission for the exact RESTRICT or RESTORE action. The
operator identity is a trusted internal caller assertion; this does not implement
administrative authentication. No consumer endpoint may call it and no operator
grant is seeded. Strong authentication, step-up and grant administration remain
release prerequisites.

Grants are action-scoped across distinct enabled targets, not case-assignment or
target-scoped permission. Both actions require exact target revisions. Retained
retries still require the current action grant and return only the original receipt.
The same transaction commits the effect, audit and independent operator debit.
Signal ingestion has read-only access to restriction state. Grant administration
must lock the enabled operator account before the grant, matching the action
boundary's ordered account-pair-first protocol.
