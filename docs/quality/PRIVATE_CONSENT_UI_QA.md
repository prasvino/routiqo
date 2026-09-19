# Private consent UI verification — 2026-09-19

Scope: actual `LiveConsentPanel` component and shared Routiqo CSS/tokens, rendered
in an isolated loopback test fixture. Transport was explicitly simulated; this is
not Google sign-in, enabled backend verification or a production route. The fixture
is local ignored tooling under `.patch-work/consent-ui`, not shipped app code.

## Rendered and interaction evidence

- Desktop at 1280 px and narrow layouts at 390 px and 320 px inspected in the
  browser. Disclosures and buttons wrap; document width equals viewport width at
  both narrow sizes. Consent buttons measure at least 44 CSS px high.
- Initial state explicitly says settings have not been checked. Check exposes
  Allow only after a simulated active/off read. Allow and Stop acknowledgements
  were exercised; Stop remains available after an off response.
- Simulated offline transition clears confirmation, disables both actions and
  displays a connection explanation. No stored/offline consent queue exists.
- Keyboard Tab reaches Stop from Check; computed focus outline is a visible
  solid 2.4 px ink outline. The original label/focus colors failed the scoped
  contrast check and were replaced with existing tokens: label/body text measure
  4.54:1 against white and button text 10.01:1 against its background. Buttons have native accessible names and status
  announcements use a live region. Final narrow screenshot inspected after fixes.
- Scoped UI-quality review retained the existing interface style, shortened the
  purpose disclosure and added the missing retained-contribution explanation.
  No whole-app redesign, extra animation or third-party visual asset was added.
- The actual production Trips preview settles into the existing sign-in-disabled
  state without exposing consent controls. No console errors or warnings appeared
  in that check. The privacy page now describes the private choice and uncertain
  writes consistently with the component.

## Automated and independent verification

32 focused consent/workspace React tests passed, including exact long generations,
unknown-state stop, cancellation, stale results, visibility, offline, completed
journeys and delayed/failed account verification. Full integration passed 329
TypeScript tests across 43 files, all workspace typechecks, repository lint and
formatting, generated-contract drift and secret scan. Web production build passed.

Independent security review requested an explicit foreground/visibility recheck
when accepting results plus reactive disabled states. Both were implemented and
covered by hidden/blurred late-response tests; final review approved the change.
Keyboard QA also found focus falling to the page body when Allow disappeared.
The final scoped handoff restores Stop only when no other control gained focus;
tests and a browser Enter-key verification cover this without stealing focus.
The final race test also resolves an old aborted enable response after a newer
successful stop, proving it cannot replace the displayed stopped acknowledgement.
The 32 focused tests passed again after this stronger ordering check.

## Limits

No real OAuth, backend flag activation, private receipt submission, public LIVE
publication or global Ghost Mode was exercised. Real authenticated end-to-end,
screen-reader, browser text-zoom and device verification remain release work.
