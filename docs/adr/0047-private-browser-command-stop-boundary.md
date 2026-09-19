# ADR 0047: Private browser command stop boundary

Date: 2026-09-19

Status: accepted; implementation tested and independently reviewed.

## Decision

Expose ADR 0046 through a distinct owner command-stop leaf under the existing
signal API flag, independently of the choice flag. Reuse current browser security
and a separate bounded stop-request quota so new-submission exhaustion does not
consume that account quota. Shared peer limits remain.

The minimal stopped result optionally includes an existing terminal receipt;
it never claims that no historical acceptance occurred. Unknown cleanup state
stays generic and lost responses stay uncertain. Client/proxy behavior is defined
in `../features/live/BROWSER_SIGNAL_STOP_API_SPEC.md`.

## Consequences

Disabling new choice interactions does not independently remove command stopping.
No automatic retry, new grant, receipt manufacture, public output or physical
erasure is introduced. Existing withdrawal stays compatible. User-facing recovery
controls still require a separate lifecycle and accessibility implementation.
