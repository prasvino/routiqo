# Private route preparation controls

Status: implemented, tested and independently reviewed, 2026-09-19. This is a private browser prerequisite, not
the public LIVE list or Quick Signal submission. Server flags remain disabled.

## Scope and purpose

Extend the existing route planner for a verified account's confirmed active
journey, after its private contribution consent is explicitly confirmed on.
Keep ordinary route planning usable without participation. Reuse the owner-only
GET/bind clients and ADR 0034 API; no new endpoint, catalog, backend authority,
provider activation, persistence, automatic request or consent enablement.

Preparing a private route sends the selected endpoints and travel mode to the
configured routing service again. Binding calculates a fresh route; an alternative
index is not a stable route identity and the resulting route may differ from the
displayed estimate. State this at the action. No public location, physical presence,
Live Moment, crowd count or safe-road claim. Do not expose opaque anchor/context
IDs, revisions or coordinates as product labels. Useful anchor labels and signal
controls remain a separate contract and implementation phase.

## Authority and input capture

The consent panel publishes a memory-only, account/journey-scoped confirmation
only after a successful active/on response, with no unresolved mutation or
terminal-completion latch. Every consent operation start, cancellation and clear
invalidates that authority synchronously. The parent maintains a monotonically
changing local epoch and a live getter alongside reactive state; stable callbacks
must not cause effect-driven invalidation loops. This is UI invalidation, never
independent server authentication or authorization.

Parent identity verification, busy work, outbox/lifecycle changes and scope changes
invalidate the same authority. Route operations compare captured account, journey,
exact consent generation and epoch with the current getter before starting and
before accepting results. Prop/effect timing alone is insufficient. A pending
Stop must invalidate an in-flight bind before its response can update readiness.

Copy endpoints and mode associated with the successful route calculation, plus
the selected alternative index. Do not read mutable form inputs after asynchronous
work starts. Alternative changes notify invalidation synchronously. Form edits,
swap/clear, new calculation, result replacement and alternative changes clear
private preparation state and cancel its request. Account/journey changes cannot
reuse another scope's observation, response or enabled action.

## Explicit interaction and recovery

1. Initially unknown. **Check private route** performs only GET and records the
   observed exact context ID or explicit null. Missing observation is distinct
   from a confirmed null expectation. There is no request on mount/reconnect/focus.
2. A GET response is a last-observed server context and CAS expectation, never
   proof that the displayed inputs were prepared: it contains no route-input
   fingerprint. An earlier lost POST may still commit after the GET.
3. **Prepare private route** is one explicit POST using that observed expectation
   and the copied route choice. No automatic read, fresh-expectation retry or
   offline queue. The server remains responsible for ownership, current consent,
   exact context, newest-attempt fencing, provider bounds and budgets.
4. A validated bound response acknowledges this request only; it is not public
   participation or a guarantee against changes elsewhere. Its expiry bounds the
   local acknowledgement. A subsequent read must not invent route association.
5. `no_route` and `no_eligible_anchors` preserve prior server context. Report the
   outcome and require explicit Check before another attempt; never say the
   previous context was removed. Failures/conflicts/cancellation likewise clear
   local readiness and require explicit recovery. GET never proves cancellation.

One synchronous request guard, AbortController and invalidated operation token
protect the component. Cancel/clear on offline, blur/hidden, scope/authority/input
changes and unmount; ignore adapters that resolve despite abort. Foreground and
scope must be rechecked at result acceptance, not only when sending. Reconnect
does not restore readiness or issue traffic. No browser storage or outbox entry.

Reject future/expired context timestamps for local readiness and recheck expiry
immediately before a write and on response acceptance. A bounded expiry timeout
may only clear the display/expectation; it never renews or fetches. Context null
is a checked observation, not an indefinitely valid authorization; lifecycle and
server checks still apply. Do not infer a grant or eligible signal from a context.

## Presentation and verification

Use existing typography/tokens and native controls with visible focus, at least
44px targets, wrapping at 320px and polite status/error announcements. Keep
private purpose and fresh-calculation disclosure near the action. Preserve focus
when controls change without stealing it after the user moves elsewhere.

Tests cover explicit-null versus unknown, exact ID, copied coordinates/mode,
alternative and form invalidation, account/journey/consent epochs, a pending Stop,
late/aborted results, one flight, completed/outbox/verification gates, timestamp
expiry and no network on timers/reconnect/focus. Cover all bound/empty/failure
outcomes without turning old observations into current-route claims. Existing
planner directions must remain available through network loss.

Use a labelled isolated mock-transport fixture for rendered component QA when
real authentication/providers are unavailable. Never add a production bypass or
claim fixture evidence as real-provider end-to-end verification. Run affected
React tests, full TypeScript checks and web build; no backend change is required.
Independent security review precedes completion. Record external OAuth, catalog,
regional service and device verification as remaining gates.
