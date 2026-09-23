export interface ProviderAlert {
  id: string;
  event: string;
  area: string;
  severity: 'Minor' | 'Moderate' | 'Severe' | 'Extreme';
  issuer: string;
  sourceUrl: string;
  issuedAt: string;
  expiresAt: string;
}

export interface ProviderAlerts {
  region: string;
  scope: string;
  source: string;
  alerts: ProviderAlert[];
}

export class ProviderAlertsError extends Error {
  constructor(readonly status: number) {
    super('Official alerts could not be checked.');
  }
}

const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;
const capUrl =
  /^https:\/\/sachet\.ndma\.gov\.in\/cap_public_website\/FetchXMLFile\?identifier=[0-9]{8,24}$/;
const maximumBytes = 32 * 1024;

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function short(value: unknown, maximum: number): value is string {
  return typeof value === 'string' && value.length > 0 && value.length <= maximum;
}

function validAlert(value: unknown): value is ProviderAlert {
  if (!record(value)) return false;
  if (
    typeof value.id !== 'string' ||
    !/^[0-9]{8,24}$/.test(value.id) ||
    !short(value.event, 120) ||
    !short(value.area, 140) ||
    !short(value.issuer, 80) ||
    !short(value.sourceUrl, 160) ||
    !capUrl.test(value.sourceUrl) ||
    !['Minor', 'Moderate', 'Severe', 'Extreme'].includes(String(value.severity)) ||
    !short(value.issuedAt, 40) ||
    !short(value.expiresAt, 40)
  )
    return false;
  const issued = Date.parse(value.issuedAt);
  const expires = Date.parse(value.expiresAt);
  return Number.isFinite(issued) && Number.isFinite(expires) && expires > issued;
}

function validate(value: unknown): ProviderAlerts {
  if (
    !record(value) ||
    value.region !== 'Chennai district area' ||
    value.scope !== 'District-wide alerts; not road conditions' ||
    value.source !== 'NDMA SACHET' ||
    !Array.isArray(value.alerts) ||
    value.alerts.length > 10 ||
    !value.alerts.every(validAlert)
  )
    throw new Error('Official alert response is invalid.');
  return value as unknown as ProviderAlerts;
}

export async function readBrowserProviderAlerts(
  accountId: string,
  journeyId: string,
  signal?: AbortSignal,
): Promise<ProviderAlerts> {
  if (!uuid.test(accountId) || !uuid.test(journeyId))
    throw new Error('Journey context is invalid.');
  const controller = new AbortController();
  let rejectDeadline!: (error: Error) => void;
  const deadline = new Promise<never>((_resolve, reject) => {
    rejectDeadline = reject;
  });
  void deadline.catch(() => undefined);
  const abort = () => {
    controller.abort();
    rejectDeadline(new DOMException('Aborted', 'AbortError'));
  };
  signal?.addEventListener('abort', abort, { once: true });
  const timer = setTimeout(() => {
    controller.abort();
    rejectDeadline(new Error('Official alert request timed out.'));
  }, 30_000);
  const race = <T>(operation: Promise<T>): Promise<T> => {
    void operation.catch(() => undefined);
    return Promise.race([operation, deadline]);
  };
  let reader: ReadableStreamDefaultReader<Uint8Array> | null = null;
  try {
    if (signal?.aborted) throw new DOMException('Aborted', 'AbortError');
    const response = await race(
      fetch(`/api/v1/journeys/${journeyId}/provider-alerts`, {
        method: 'GET',
        headers: { 'X-Routiqo-Account': accountId },
        credentials: 'same-origin',
        cache: 'no-store',
        redirect: 'error',
        signal: controller.signal,
      }),
    );
    if (response.status !== 200) {
      void response.body?.cancel().catch(() => undefined);
      throw new ProviderAlertsError(response.status);
    }
    if (
      !response.headers.get('content-type')?.toLowerCase().startsWith('application/json') ||
      !response.body
    ) {
      void response.body?.cancel().catch(() => undefined);
      throw new Error('Official alert response is invalid.');
    }
    reader = response.body.getReader();
    const decoder = new TextDecoder('utf-8', { fatal: true });
    let raw = '';
    let bytes = 0;
    while (true) {
      const part = await race(reader.read());
      if (controller.signal.aborted) throw new DOMException('Aborted', 'AbortError');
      if (part.done) break;
      bytes += part.value.byteLength;
      if (bytes > maximumBytes) throw new Error('Official alert response is too large.');
      raw += decoder.decode(part.value, { stream: true });
    }
    raw += decoder.decode();
    return validate(JSON.parse(raw) as unknown);
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', abort);
    if (reader) {
      try {
        void reader.cancel().catch(() => undefined);
      } catch {
        // A failed cleanup cannot extend the request lifetime.
      }
      try {
        reader.releaseLock();
      } catch {
        // A pending read may retain its lock until cancellation settles.
      }
    }
  }
}
