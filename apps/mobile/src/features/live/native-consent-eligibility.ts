export interface NativeConsentScope {
  accountId: string;
  journeyId: string;
}
export interface NativeConsentEligibilityInput {
  accountId: string | null;
  activeJourneyId: string | null;
  restoring: boolean;
  busy: boolean;
  deletionCleanupPending: boolean;
  pendingJourneyActions: number;
}
/** Retain a mounted same-account controller during restoration, but never allow dispatch there. */
export function nativeConsentEligibility(
  previous: NativeConsentScope | null,
  input: NativeConsentEligibilityInput,
): { scope: NativeConsentScope | null; available: boolean } {
  const scope =
    input.accountId && input.activeJourneyId
      ? { accountId: input.accountId, journeyId: input.activeJourneyId }
      : input.restoring
        ? previous
        : null;
  return {
    scope,
    available: Boolean(
      scope &&
      input.accountId === scope.accountId &&
      input.activeJourneyId === scope.journeyId &&
      !input.restoring &&
      !input.busy &&
      !input.deletionCleanupPending &&
      input.pendingJourneyActions === 0,
    ),
  };
}
