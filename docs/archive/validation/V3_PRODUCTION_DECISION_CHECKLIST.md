> **Archived 2026-09-25.** Threshold, cohort, differential-privacy or V3 community-summary material. The pilot publishes no aggregate traveller output; revisit only if aggregate counts return after a separate privacy review. See [docs/archive/README.md](/docs/archive/README.md) and [docs/PRODUCT.md](/docs/PRODUCT.md). Kept as a historical record; not current requirements.

# V3 community traffic summary — production decision checklist

Status: V3 implementation and staging evaluation authorized; **production activation not approved**. This checklist separates engineering evidence from acceptance of ADR 0055's different privacy contract. The existing ADR 0053/0054 code/docs remain preserved and disconnected.

The [real staging trial handoff](V3_STAGING_TRIAL_PENDING.md) assigns the remaining engineering and external inputs. The V3 moderator workflow and controlled grant administration are staged separately under [ADR 0056](../adr/0056-v3-moderator-staging-boundary.md) and [ADR 0057](../adr/0057-v3-operator-grant-administration.md); implementing them does not close real OAuth/MFA, named operator, supervision or privacy-review gates below.

- [ ] Owner reviews the completed staging release packet and explicitly accepts the V3 disclosure: participation may be inferred; accepted Share is only for consideration; withdrawal after the publication snapshot may be too late; authorized safety suppression and saved outside copies remain possible. This is not person-level differential privacy.
- [ ] Product/privacy and security review approve the exact Share, Stop, Ghost Mode, deletion, blocking and moderation wording, plus purpose/versioned consent and no migration of V18/V22 records.
- [ ] Real authenticated staging flow passes Share/Stop/recovery, publisher, active-journey reader, report/moderation and cleanup with reviewed regional catalog, Google OAuth, separate admin authentication and configuration. Confirm all production V3 flags remain disabled until approval.
- [ ] PostgreSQL concurrency/failover and HTTP security tests pass for duplicate/debit, snapshot boundaries, two publishers, rollback/retry, authority withdrawal, guessed references, block/reader policy, expiry, suppression, cleanup and restore without resurrection.
- [ ] Browser/device QA passes active-journey consent, uncertain write, fresh-device recovery, offline/reconnect, account switching, large text, keyboard/screen reader, narrow screens and reduced motion. No V3 automatic submission or durable offline public feed.
- [ ] Consented Chennai/OMR pilot data and independent road-condition ground truth establish output coverage, age, wrong-value/false-reassurance rate, repeated-commuter cap behavior, collusion/abuse risk and truthful empty states. [Synthetic exploration](V3_COMMUNITY_UTILITY_2026-09-23.md) does not close this item.
- [ ] Operations approve bounded candidate/projection/debit/report/audit and backup retention, cleanup monitoring, publisher deadlines, suppression authority, incident response and rollback/failover runbook. No expired or suppressed projection can reappear.

If any decision remains open, leave the production flags off and retain provider-sourced alerts as the separately gated LIVE option. Record approval evidence and flag changes here before production exposure.
