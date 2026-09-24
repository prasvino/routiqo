import type { createNativeAccount } from '../../auth/native-account';
import { NativeHttpStatus } from '../../auth/safe-transport';
import { NativeSessionRequired } from '../../auth/native-account';

type Account = ReturnType<typeof createNativeAccount>;
const uuid = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/;
const nil = '00000000-0000-0000-0000-000000000000';
const maximumGeneration = 9223372036854775807n;
const deadlineMs = 12_000;
const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);
const exact = (value: Record<string, unknown>, keys: string[]) =>
  Object.keys(value).length === keys.length && keys.every((key) => Object.hasOwn(value, key));

export interface NativeLiveConsent {
  journeyId: string;
  generation: string;
  sharing: boolean;
  journeyActive: boolean;
}
export interface NativeConsentIntent {
  expectedGeneration: string;
  sharing: boolean;
}
function identity(value: string): string {
  if (typeof value !== 'string' || value.length !== 36 || value === nil || !uuid.test(value))
    throw new Error('Invalid native consent identity.');
  return value;
}
function generation(value: unknown): string {
  if (
    typeof value !== 'string' ||
    /^(0|[1-9][0-9]*)$/.exec(value)?.[0] !== value ||
    value.length > 19 ||
    BigInt(value) > maximumGeneration
  )
    throw new Error('Invalid native consent generation.');
  return value;
}
function intent(value: unknown): NativeConsentIntent {
  if (
    !record(value) ||
    !exact(value, ['expectedGeneration', 'sharing']) ||
    typeof value.sharing !== 'boolean'
  )
    throw new Error('Invalid native consent intent.');
  return { expectedGeneration: generation(value.expectedGeneration), sharing: value.sharing };
}
export function readNativeLiveConsent(
  value: unknown,
  expectedJourneyId: string,
): NativeLiveConsent {
  identity(expectedJourneyId);
  if (
    !record(value) ||
    !exact(value, ['journeyId', 'generation', 'sharing', 'journeyActive']) ||
    typeof value.journeyId !== 'string' ||
    identity(value.journeyId) !== expectedJourneyId ||
    typeof value.sharing !== 'boolean' ||
    typeof value.journeyActive !== 'boolean' ||
    (value.sharing && !value.journeyActive)
  )
    throw new Error('Native consent response is invalid.');
  return {
    journeyId: value.journeyId,
    generation: generation(value.generation),
    sharing: value.sharing,
    journeyActive: value.journeyActive,
  };
}
function safeFailure(error: unknown): Error {
  if (error instanceof NativeHttpStatus || error instanceof NativeSessionRequired) return error;
  if (error instanceof Error && error.name === 'AbortError')
    return new DOMException('Consent request cancelled.', 'AbortError');
  return new Error('Native consent request could not be completed.');
}
async function request(
  account: Account,
  accountId: string,
  journeyId: string,
  input: NativeConsentIntent | undefined,
  signal?: AbortSignal,
): Promise<NativeLiveConsent> {
  identity(accountId);
  identity(journeyId);
  const snapshot = input === undefined ? undefined : intent(input);
  if (signal?.aborted) throw new DOMException('Consent request cancelled.', 'AbortError');
  if (account.activeAccount() !== accountId) throw new NativeSessionRequired();
  const revision = account.revision();
  const current = () => {
    if (account.activeAccount() !== accountId || account.revision() !== revision)
      throw new Error('Native consent account changed.');
  };
  const controller = new AbortController();
  let rejectStop!: (reason: Error) => void;
  const stopped = new Promise<never>((_, reject) => {
    rejectStop = reject;
  });
  void stopped.catch(() => undefined);
  let active = true;
  const stop = (reason: Error) => {
    if (!active) return;
    active = false;
    controller.abort();
    rejectStop(reason);
  };
  const onAbort = () => stop(new DOMException('Consent request cancelled.', 'AbortError'));
  signal?.addEventListener('abort', onAbort, { once: true });
  const timer = setTimeout(() => stop(new Error('Native consent request timed out.')), deadlineMs);
  try {
    if (signal?.aborted) onAbort();
    current();
    const pending = account.verifiedRequest(
      `/api/v1/native/journeys/${journeyId}/consent`,
      snapshot === undefined ? 'GET' : 'POST',
      {
        accountId,
        ...(snapshot === undefined ? {} : { body: snapshot }),
        signal: controller.signal,
      },
    );
    void pending.catch(() => undefined);
    const raw = await Promise.race([pending, stopped]);
    if (!active) throw new DOMException('Consent request cancelled.', 'AbortError');
    current();
    const consent = readNativeLiveConsent(raw, journeyId);
    if (snapshot !== undefined) {
      const observed = BigInt(consent.generation);
      const expected = BigInt(snapshot.expectedGeneration);
      if (
        snapshot.sharing
          ? !consent.sharing || !consent.journeyActive || observed !== expected + 1n
          : consent.sharing ||
            (consent.journeyActive &&
              observed <= expected &&
              !(expected === maximumGeneration && observed === maximumGeneration))
      )
        throw new Error('Native consent acknowledgement is invalid.');
    }
    current();
    return consent;
  } catch (error) {
    if (error instanceof Error && error.message === 'Native consent request timed out.')
      throw error;
    throw safeFailure(error);
  } finally {
    active = false;
    clearTimeout(timer);
    signal?.removeEventListener('abort', onAbort);
  }
}
export function readNativeConsent(
  identity: Account,
  accountId: string,
  journeyId: string,
  signal?: AbortSignal,
): Promise<NativeLiveConsent> {
  return request(identity, accountId, journeyId, undefined, signal);
}
export function submitNativeConsent(
  identity: Account,
  accountId: string,
  journeyId: string,
  input: NativeConsentIntent,
  signal?: AbortSignal,
): Promise<NativeLiveConsent> {
  return request(identity, accountId, journeyId, input, signal);
}
