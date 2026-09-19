# Private Quick Signal interface — implementation contract

Status: private browser controls implemented, 2026-09-19. Verification evidence
and remaining release gates are recorded in BUILD_STATUS.md and
`docs/quality/PRIVATE_QUICK_SIGNAL_UI_QA.md`. ADR 0045 supplies choices and expected-context issuance;
ADRs 0046/0047 supply terminal command stopping. Public LIVE remains separately
gated by publication and safety. No flag activation or synthetic catalog fallback.

## Scope and sequence

Private structured contributions use the confirmed owned journey and existing
browser clients. JourneyWorkspace owns bounded recovery and mounts the controls;
the route planner publishes only a fresh-bind contribution authority. Keep Home,
Explore, Trips and Profile; no new navigation destination or global session store.

## New-contribution authority

Require the current confirmed account, active journey, current consent, online
foreground interaction and a successful fresh bind acknowledgment for the exact
current route selection. JourneyWorkspace owns the existing consent getter/epoch;
RoutePlanner/LiveRouteBindingPanel own selection epochs and acknowledgments.
Add a narrow acknowledgment/getter handshake. A context GET may recover a compare-
and-set expectation but cannot establish contribution authority or association
with the route currently drawn. After reload/selection changes, prepare again.

Invalidate authority synchronously on endpoint/mode/alternative/result changes,
a new bind attempt, consent change, identity/lifecycle change and expiry, before
effects or late replies can act. Fetch labeled choices only through an explicit
check. Require the snapshot's exact context/revision/consent tuple to match both
current authorities before rendering choices or submitting. Labels are plain
text; never display opaque identifiers as product choices. Offer only values in
the server-authorized category set.

Each submission requires a fresh unchecked acknowledgment that the user is
stopped or a passenger and can interact safely. Capture input before awaiting.
Issue through the expected-context endpoint once, register a returned command
before acceptance, recheck synchronous authority and accept at most once. Never
fall back to anchor-only issuance, automatically reissue or replay offline inputs.

## Recovery ownership and bounds

JourneyWorkspace owns a memory-only command collection outside its active-journey/
consent rendering branch. It survives contribution-panel remounts, Ghost, route
replacement and completion while the workspace remains mounted. It does not
promise cross-page/reload recovery. Disclose that leaving/reloading loses local
stop handles while server evidence may remain until expiry. Warn before ordinary
in-app departure with unresolved/accepted commands, reusing existing navigation
protection patterns. Browser unload protection is best effort. An empty memory
list never proves there are no outstanding server commands.

Bound the collection to five known commands, including terminal notices. This
is a local memory/interaction bound, not a server quota or anti-Sybil guarantee.
Block new issuance at capacity; never silently evict recovery handles. Terminal
notices can be dismissed. Removing an accepted/uncertain handle needs a separate
explicit acknowledgment that it only clears this page and does not stop server
work. Stop is the primary recovery action. Any unresolved acceptance/stop blocks
further issuance until stopped or explicitly forgotten.

Keep minimal account/journey/command identity, phase and operation token for
unresolved commands. Retain only the existing minimized receipt metadata when
confirmed; no labels, precise routes, submitted values or fingerprints in the
recovery collection. Do not discard the only accepted-command handle merely
because another contribution starts.

Remove confirmed receipt metadata at retainUntil, checked before rendering and
on foreground return, with a maximum 24 hours of local elapsed retention from
capture. Timer suspension must not make expired metadata reappear. Keep only the
bounded command/journey handle and an explicit local-metadata-expired/unconfirmed
phase if stop recovery is still needed. Local clock changes/expiry never mean
stopped, never accepted or server data erased. Handles last only for the workspace
lifetime or until explicit dismissal/forgetting.

Same-account authentication refresh hides/disables recovery until confirmation;
account change/sign-out clears it. Verify the current account before a recovery
request; the existing account header/server authorization is the final boundary.
No localStorage, IndexedDB, journey outbox, backup, journal or analytics copies.

## Cancellation and uncertain results

Use separate cancellation scopes. Consent/route/foreground invalidation cancels
new issue/accept work, including CSRF bootstrap, so a late bootstrap cannot start
a POST. It must not cancel a same-account stop merely because sharing/route changed.
Stop still obeys identity and transport availability.

One new submission is in flight at a time. A user may stop the currently accepting
command: abort its transport, increment its operation token, then send stop once.
Late acceptance cannot overwrite stopping/stopped state. Abort alone is not stop.

- Lost issuance has no recoverable command ID. Do not fabricate one or start
  acceptance; an unused grant expires normally. No automatic issuance retry.
- Once the command is known, failed/aborted acceptance stays uncertain. The first
  UI offers explicit Stop/Retry stop instead of replaying an old observation.
  Never replay acceptance merely to manufacture a receipt.
- Stop success confirms no further new acceptance for that command. A null
  receipt does not prove no past acceptance; a terminal receipt preserves times.
- A failed stop, including conflict after cleanup, is unconfirmed. Do not describe
  it as cancellation, deletion or evidence that the earlier request never arrived.
- An accepted private receipt is not a publication acknowledgment. Hide old
  contribution content after scope changes while retaining minimized recovery.

No mount/focus/reconnect requests, background reporting, automatic retry, driving
prompts or fabricated public activity. No clock-based removal may masquerade as
confirmed server revocation.

## Acceptance before completion

Test the real integration, not only a reducer: explicit checks, fresh-bind-only
authority, tuple/category/expiry mismatches, safe-intent reset, single-flight exact
issuance/acceptance, and identity/consent/route changes around every await. Cover
known grant then lost acceptance, stop before delayed acceptance, late replies,
Ghost/completion recovery, panel remount, navigation warning, temporary same-account
refresh versus account change, capacity/forgetting and receipt-metadata expiry
without loss of stop authority. Assert no storage/outbox or publication claims.

Exercise keyboard/focus, screen-reader feedback, small screens, large text,
reduced motion, offline/hidden states and uncertain outcomes with rendered local
fixtures clearly separated from production. Independent integration review and
relevant full checks/builds are required. Real OAuth/provider/catalog/device
verification and public publication approval remain separate gates.
