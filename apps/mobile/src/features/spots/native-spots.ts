import {
  readSpotActivity,
  readSpotCatalog,
  readSpotContributionReceipt,
  readSpotReportReceipt,
  readSpotVoteResult,
  type QueuedSpotContribution,
  type SpotActivity,
  type SpotCatalog,
  type SpotContributionReceipt,
  type SpotReportReason,
  type SpotVote,
  type SpotVoteResult,
} from '@routiqo/shared';
import { NativeSessionRequired, type createNativeAccount } from '../../auth/native-account';
import { NativeHttpStatus } from '../../auth/safe-transport';
import { nativeAbortError } from '../../auth/abort-error';

type Account = ReturnType<typeof createNativeAccount>;
export type SpotsFailure =
  | 'session'
  | 'no-journey'
  | 'rate-limited'
  | 'unavailable'
  | 'invalid'
  | 'forbidden'
  | 'not-found'
  | 'conflict'
  | 'too-old'
  | 'contact-details';

export class NativeSpotsError extends Error {
  constructor(
    readonly code: SpotsFailure,
    readonly status?: number,
  ) {
    super(
      {
        session: 'Sign in to continue.',
        'no-journey': 'Spot updates start once your journey reaches the server.',
        'rate-limited': 'Updates paused briefly.',
        unavailable: 'Spot updates are unavailable right now.',
        invalid: 'Spot updates are unavailable right now.',
        forbidden: "You can't do that right now.",
        'not-found': 'That item is no longer here.',
        conflict: 'That was already done.',
        'too-old': "Too old to post; it wasn't sent.",
        'contact-details': "Links and phone numbers aren't allowed in posts.",
      }[code],
    );
    this.name = 'NativeSpotsError';
  }
}

const deadlineMs = 12_000;
const uuid = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/;

/** Writes map every refusal to a specific code; reads keep their coarser mapping. */
function classifyWrite(error: unknown): Error {
  if (error instanceof NativeHttpStatus) {
    const code = (
      {
        401: 'session',
        403: 'forbidden',
        404: 'not-found',
        409: 'conflict',
        410: 'too-old',
        422: 'contact-details',
        429: 'rate-limited',
        400: 'invalid',
        413: 'invalid',
        415: 'invalid',
      } as Record<number, SpotsFailure>
    )[error.status];
    return new NativeSpotsError(code ?? 'unavailable', error.status);
  }
  return classify(error);
}

function classify(error: unknown): Error {
  if (error instanceof NativeSpotsError) return error;
  if (error instanceof Error && error.name === 'AbortError') return error;
  if (error instanceof NativeSessionRequired) return new NativeSpotsError('session');
  if (error instanceof NativeHttpStatus) {
    const code: SpotsFailure =
      error.status === 401
        ? 'session'
        : error.status === 409
          ? 'no-journey'
          : error.status === 429
            ? 'rate-limited'
            : [400, 413, 415].includes(error.status)
              ? 'invalid'
              : 'unavailable';
    return new NativeSpotsError(code, error.status);
  }
  return new NativeSpotsError('unavailable');
}

/** Runs one Spots call with a deadline, cancellation and account/revision checks. */
async function guarded<T>(
  identity: Account,
  accountId: string,
  call: () => Promise<T>,
  signal?: AbortSignal,
  classifyError: (error: unknown) => Error = classify,
): Promise<T> {
  if (!uuid.test(accountId) || identity.activeAccount() !== accountId)
    throw new NativeSpotsError('session');
  if (signal?.aborted) throw nativeAbortError('Spot request cancelled.');
  const revision = identity.revision();
  const current = () => {
    if (identity.activeAccount() !== accountId || identity.revision() !== revision)
      throw new NativeSpotsError('session');
  };
  let stop!: (reason: Error) => void;
  const stopped = new Promise<never>((_, reject) => {
    stop = reject;
  });
  void stopped.catch(() => undefined);
  const onAbort = () => stop(nativeAbortError('Spot request cancelled.'));
  signal?.addEventListener('abort', onAbort, { once: true });
  const timer = setTimeout(() => stop(new NativeSpotsError('unavailable')), deadlineMs);
  try {
    const pending = call();
    void pending.catch(() => undefined);
    const result = await Promise.race([pending, stopped]);
    current();
    return result;
  } catch (error) {
    current();
    throw classifyError(error);
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', onAbort);
  }
}

export type SpotCatalogFetch =
  | { status: 'not-modified' }
  | { status: 'catalog'; etag: string; payload: unknown; catalog: SpotCatalog };

/** Revalidates the cached catalog by ETag; a 200 is accepted only if it validates against its ETag. */
export function fetchNativeSpotCatalog(
  identity: Account,
  accountId: string,
  ifNoneMatch: string | null,
  signal?: AbortSignal,
): Promise<SpotCatalogFetch> {
  return guarded(
    identity,
    accountId,
    async () => {
      const result = await identity.verifiedSpotCatalog({
        accountId,
        ifNoneMatch,
        ...(signal ? { signal } : {}),
      });
      if (result.status === 'not-modified') return { status: 'not-modified' as const };
      let catalog: SpotCatalog;
      try {
        catalog = readSpotCatalog(result.catalog, result.etag);
      } catch {
        throw new NativeSpotsError('invalid');
      }
      return { status: 'catalog' as const, etag: result.etag, payload: result.catalog, catalog };
    },
    signal,
  );
}

/** Activity for the given Spot IDs. Only IDs are sent: never the route, endpoints or position. */
export function fetchNativeSpotActivity(
  identity: Account,
  accountId: string,
  spotIds: readonly string[],
  signal?: AbortSignal,
): Promise<SpotActivity> {
  const ids = [...spotIds].sort();
  if (
    ids.length < 1 ||
    ids.length > 20 ||
    new Set(ids).size !== ids.length ||
    ids.some((id) => !uuid.test(id))
  )
    return Promise.reject(new NativeSpotsError('invalid'));
  return guarded(
    identity,
    accountId,
    async () => {
      const raw = await identity.verifiedRequest('/api/v1/native/spots/activity', 'POST', {
        accountId,
        body: { spotIds: ids },
        ...(signal ? { signal } : {}),
      });
      try {
        return readSpotActivity(raw);
      } catch {
        throw new NativeSpotsError('invalid');
      }
    },
    signal,
  );
}

function write<T>(
  identity: Account,
  accountId: string,
  path: string,
  body: unknown,
  read: (raw: unknown) => T,
  signal?: AbortSignal,
): Promise<T> {
  return guarded(
    identity,
    accountId,
    async () => {
      const raw = await identity.verifiedRequest(path, 'POST', {
        accountId,
        body,
        ...(signal ? { signal } : {}),
      });
      try {
        return read(raw);
      } catch {
        throw new NativeSpotsError('invalid');
      }
    },
    signal,
    classifyWrite,
  );
}

function itemPath(ref: string, action: 'vote' | 'delete' | 'reports' | 'block-author'): string {
  if (!uuid.test(ref)) throw new NativeSpotsError('invalid');
  return `/api/v1/native/spots/items/${ref}/${action}`;
}

/** Sends one queued signal or post exactly as queued; replays are safe by its clientKey. */
export function submitNativeSpotContribution(
  identity: Account,
  accountId: string,
  entry: QueuedSpotContribution,
  signal?: AbortSignal,
): Promise<SpotContributionReceipt> {
  const common = {
    clientKey: entry.clientKey,
    spotId: entry.spotId,
    capturedAt: entry.capturedAt,
    journeyId: entry.journeyId,
  };
  return entry.kind === 'signal'
    ? write(
        identity,
        accountId,
        '/api/v1/native/spots/signals',
        { ...common, category: entry.category, value: entry.value },
        readSpotContributionReceipt,
        signal,
      )
    : write(
        identity,
        accountId,
        '/api/v1/native/spots/posts',
        { ...common, type: entry.type, text: entry.text },
        readSpotContributionReceipt,
        signal,
      );
}

export async function voteNativeSpotItem(
  identity: Account,
  accountId: string,
  ref: string,
  vote: SpotVote,
  signal?: AbortSignal,
): Promise<SpotVoteResult> {
  return write(identity, accountId, itemPath(ref, 'vote'), { vote }, readSpotVoteResult, signal);
}

export async function deleteNativeSpotPost(
  identity: Account,
  accountId: string,
  ref: string,
  signal?: AbortSignal,
): Promise<SpotContributionReceipt> {
  return write(
    identity,
    accountId,
    itemPath(ref, 'delete'),
    {},
    readSpotContributionReceipt,
    signal,
  );
}

/** `requestId` is kept by the caller for the same item and reason, so a retry replays safely. */
export async function reportNativeSpotItem(
  identity: Account,
  accountId: string,
  ref: string,
  requestId: string,
  reason: SpotReportReason,
  signal?: AbortSignal,
): Promise<{ receivedAt: string; receiptExpiresAt: string }> {
  if (!uuid.test(requestId)) return Promise.reject(new NativeSpotsError('invalid'));
  return write(
    identity,
    accountId,
    itemPath(ref, 'reports'),
    { requestId, reason },
    readSpotReportReceipt,
    signal,
  );
}

export async function blockNativeSpotAuthor(
  identity: Account,
  accountId: string,
  ref: string,
  signal?: AbortSignal,
): Promise<void> {
  return write(
    identity,
    accountId,
    itemPath(ref, 'block-author'),
    {},
    (raw) => {
      if (raw !== null) throw new Error('Unexpected body.');
    },
    signal,
  );
}
