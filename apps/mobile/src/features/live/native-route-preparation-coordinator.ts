import type { NativeRouteSelection } from './native-route-context';

export interface NativePreparationConsent {
  accountId: string;
  journeyId: string;
  generation: string;
  epoch: number;
  sessionEpoch: number;
}
export interface NativePreparationSelection {
  selection: NativeRouteSelection | null;
  epoch: number;
  sessionEpoch: number;
}
const copySelection = (selection: NativeRouteSelection): NativeRouteSelection => ({
  mode: selection.mode,
  origin: [selection.origin[0], selection.origin[1]],
  destination: [selection.destination[0], selection.destination[1]],
  alternativeIndex: selection.alternativeIndex,
});

/** Memory-only synchronous authority bridge between two independent native controls. */
export function createNativeRoutePreparationCoordinator() {
  let consentEpoch = 0;
  let selectionEpoch = 0;
  let consent: NativePreparationConsent | null = null;
  let selection: NativeRouteSelection | null = null;
  let selectionSessionEpoch = -1;
  const listeners = new Set<() => void>();
  const notify = () => {
    for (const listener of [...listeners]) listener();
  };
  return {
    subscribe(listener: () => void) {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    consent(accountId: string, journeyId: string): NativePreparationConsent | null {
      return consent?.accountId === accountId && consent.journeyId === journeyId
        ? { ...consent }
        : null;
    },
    selection(): NativePreparationSelection {
      return {
        selection: selection ? copySelection(selection) : null,
        epoch: selectionEpoch,
        sessionEpoch: selectionSessionEpoch,
      };
    },
    epochs() {
      return { consent: consentEpoch, selection: selectionEpoch };
    },
    setConsent(
      accountId: string,
      journeyId: string,
      generation: string | null,
      sessionEpoch: number,
    ) {
      if (
        generation === null &&
        consent &&
        (consent.accountId !== accountId || consent.journeyId !== journeyId)
      )
        return;
      consentEpoch++;
      consent =
        generation === null
          ? null
          : {
              accountId,
              journeyId,
              generation,
              epoch: consentEpoch,
              sessionEpoch,
            };
      notify();
    },
    setSelection(value: NativeRouteSelection | null, sessionEpoch: number) {
      selectionEpoch++;
      selection = value ? copySelection(value) : null;
      selectionSessionEpoch = sessionEpoch;
      notify();
    },
    clear() {
      consentEpoch++;
      selectionEpoch++;
      consent = null;
      selection = null;
      notify();
    },
  };
}
