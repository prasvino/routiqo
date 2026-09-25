# Signal abuse budgets

Status: implemented and reviewed; no public publication approval.

> **Direction brief (2026-09-25):** Kept. Budgets must extend to text posts, voice uploads, Ask Ahead questions and answers, "Still true?" confirmations and reports, with stricter limits for new accounts. Recheck the per-hour and cooldown values against festival-exodus density on the Pongal corridor. See [PRODUCT.md](../../PRODUCT.md).

New successful acceptance must satisfy the existing five-per-fixed-minute budget,
plus at most 20 accepted signals in the rolling preceding hour per actor and a
60-second cooldown per actor/anchor/category. At exactly 60 seconds/one hour the
older charge leaves its respective window. Retained exact retries and withdrawal
do not consume another charge. Changing journey, context, session, category or
withdrawal must not reset the actor-hour budget. This is abuse friction, not proof
of an independent person or physical presence.

Use the existing account-before-journey transaction authority. Reserve all budgets
in the same transaction as grant consumption, replacement and receipt insertion;
denial or later failure rolls everything back. Check server time after locks;
any retained budget timestamp in the future denies conservatively.

V13 adds a private bounded actor acceptance ledger, at most 20 slots per actor,
holding only server acceptance time, opaque anchor and category. It is independent
of receipts, grants, journeys and consent so withdrawal, short receipt retention,
completion and grant cleanup cannot reset it. Account deletion cascades the ledger.
Reuse an expired slot, never evict a live charge. No IP/device fingerprint, precise
geometry or public ledger API is introduced. SQL constraints bound slots, category,
identity and finite time; indexed account lookup is bounded to 20 rows.

Ledger entries logically expire one hour after acceptance. Add bounded leaf-only
physical cleanup using the existing maintenance pattern (100 rows, five-second
transaction, exact identity/time recheck, SKIP LOCKED); default-off expiry job
attempts one batch independently of other categories. Delayed physical removal
does not extend a quota. Do not log ledger contents. Retention is at most the
rolling budget window logically, with documented physical cleanup lag.

Acceptance: boundary instants, hour rollover bursting, slot replacement cooldown,
independent actors/categories, cross-journey persistence, concurrent adapters at
the final allowance, exact replay under exhaustion, withdrawal/purge non-reset,
atomic rollback, future-clock denial, bounded cleanup and account deletion.
Existing replacement tests must advance clock appropriately; never weaken the
new limit to preserve a synthetic rapid replacement fixture.

Cutover: an empty V13 ledger does not account for pre-V13 writes; receipts are not
a complete backfill source. Stop all acceptance writers for at least a full hour
after the last old acceptance, migrate/verify, then enable only enforcing versions.
No mixed-version acceptance rollout. Fresh databases are exempt only when they
have never accepted signals. Keep writes off during rollback. This run tests only
disposable databases and leaves real transports disabled.
