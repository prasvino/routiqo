# Browser public Share V2 — consent and recovery contract

Status: design copy for the user-selected irreversible Share direction; **not implemented or approved for activation**. Existing V18 `public-intent` and its Stop control remain separate private prerequisites. No legacy action, response or local state may be silently upgraded into V2.

ADR 0055's proposed community-summary V3 would use different accepted-for-consideration and after-snapshot Stop language. This V2 finality copy cannot be reused for that path; neither action is active.

## Before Share

Show this disclosure immediately next to the deliberate action, with the exact pilot end and retained-claim deletion date supplied by the server:

> Share this one traffic report for Routiqo Live. If Routiqo accepts it, your report becomes a final input to a privacy-protected group calculation for this pilot. You cannot remove that input by stopping contributions, turning on Ghost Mode, ending your journey or deleting your account. Your account and exact location will not appear in a Live Moment. Your report may never produce a public result. Routiqo will keep the minimal anti-duplication record until **[reviewed date]**.

The user must separately confirm: **“I understand an accepted Share cannot be undone.”** Keep the stopped/passenger confirmation as a separate interaction safeguard. Neither box is prechecked or remembered for another signal. Do not submit automatically, offline, on reconnect, during navigation, or after a stale account/journey/context/consent change. A private consent toggle is not this approval.

The application must not show this action until the product/privacy/legal disclosure and retention decision is approved, the V2 service and owner recovery are implemented, the stable person authority is operated, and the protocol/release gates pass. A disabled V2 flag is not a substitute for truthful V18 copy when that older control is separately tested.

## Acknowledgement and uncertainty

- `accepted_final`: the exact V2 command was committed with a one-person pilot claim. Explain that Stop/Ghost/deletion cannot remove this frozen input. Do not claim that a public moment was or will be published. The response need not expose the internal public key or person reference.
- `not_accepted`: no V2 input or pilot slot committed. Give a bounded reason suitable for the owner without exposing another person, contributor state or internal moderation data. Do not convert an unknown result into this state.
- `uncertain`: the network ended without a trustworthy response. Preserve the exact request identity, show that Share may already be final, and offer an explicit owner-only recovery check. Never submit a different request automatically. An exact authenticated retry must recover the same result without another claim.

Owner recovery is account-bound and no-store, survives reload while the account exists, and can recover an accepted action after the journey or private signal expires. It may cease after account deletion; the retained pseudonymous pilot claim remains, as disclosed. A stale client using V18 purpose `public-live-moment-v1` cannot reach V2 or receive a false `stopped` acknowledgement for a frozen input.

## Stop, Ghost Mode and deletion

Stop private contributions, Ghost Mode, completion, restriction and account deletion prevent new V2 Share and clear private or viewer-local state according to their own contracts. For an already accepted V2 Share, show **“Further sharing stopped. Your earlier accepted pilot input remains final.”** Do not offer a button labeled “Stop public consideration” that appears to retract it. A later public result remains the same for other authorized readers until fixed expiry; previously captured copies cannot be recalled.

Account deletion disclosure must distinguish erased account/journey/private data from the time-limited pseudonymous person claim and frozen traffic key retained for the pilot. The exact legal and backup retention terms require approval before V2 is exposed.

## Acceptance tests

Exercise first Share, exact retry, conflicting retry, lost response, owner recovery after reload/journey completion/private Stop, account switch, Ghost Mode, deletion, expired receipt, restricted contributor, offline transitions, and browser back/navigation. Verify narrow screens, large text, keyboard/screen reader announcements, and no hidden or automatic writes. Confirm the current V18 UI never displays V2 finality or calls its endpoint.
