import type { components, paths } from '@routiqo/api-client';
export type GoogleChallenge = components['schemas']['GoogleChallenge'];
export type BrowserSession = components['schemas']['BrowserSession'];
export type BrowserAccount =
  paths['/api/v1/auth/session']['get']['responses'][200]['content']['application/json'];
export interface AuthAvailability {
  enabled: boolean;
  clientId: string | null;
}
const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null;
const uuid = (value: unknown): value is string =>
  typeof value === 'string' &&
  /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(value);
const instant = (value: unknown): value is string =>
  typeof value === 'string' && Number.isFinite(Date.parse(value));
export class BrowserAuthError extends Error {
  constructor(public status: number) {
    super(
      status === 429
        ? 'Too many sign-in attempts. Wait a minute and try again.'
        : status === 401 || status === 403
          ? 'Your sign-in attempt expired. Please start again.'
          : 'Sign-in is temporarily unavailable. Check your connection and try again.',
    );
  }
}
async function request(path: string, body?: unknown, csrf?: string): Promise<Response> {
  try {
    const response = await fetch(`/api/v1/auth/${path}`, {
      method: body === undefined ? 'GET' : 'POST',
      credentials: 'same-origin',
      cache: 'no-store',
      headers:
        body === undefined
          ? {}
          : { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf ?? '' },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      signal: AbortSignal.timeout(12000),
    });
    if (!response.ok && !(path === 'session' && response.status === 401))
      throw new BrowserAuthError(response.status);
    return response;
  } catch (error) {
    if (error instanceof BrowserAuthError) throw error;
    throw new BrowserAuthError(503);
  }
}
export async function authAvailability(): Promise<AuthAvailability> {
  const value: unknown = await (await request('config')).json();
  if (
    !record(value) ||
    typeof value.enabled !== 'boolean' ||
    (value.enabled &&
      (typeof value.clientId !== 'string' ||
        !value.clientId.endsWith('.apps.googleusercontent.com')))
  )
    throw new BrowserAuthError(503);
  return { enabled: value.enabled, clientId: value.enabled ? (value.clientId as string) : null };
}
export async function browserAccount(): Promise<BrowserAccount | null> {
  const response = await request('session');
  if (response.status === 401) return null;
  const value: unknown = await response.json();
  if (!record(value) || !uuid(value.accountId)) throw new BrowserAuthError(503);
  return { accountId: value.accountId };
}
export async function browserCsrf(): Promise<string> {
  const value: unknown = await (await request('csrf')).json();
  if (
    !record(value) ||
    typeof value.token !== 'string' ||
    value.token.length < 20 ||
    value.token.length > 1024
  )
    throw new BrowserAuthError(503);
  return value.token;
}
export async function beginGoogleLogin(csrf: string): Promise<GoogleChallenge> {
  const value: unknown = await (await request('google/challenge', {}, csrf)).json();
  if (
    !record(value) ||
    !uuid(value.id) ||
    typeof value.nonce !== 'string' ||
    !/^[A-Za-z0-9_-]{43}$/.test(value.nonce) ||
    !instant(value.expiresAt)
  )
    throw new BrowserAuthError(503);
  return { id: value.id, nonce: value.nonce, expiresAt: value.expiresAt };
}
export async function exchangeGoogleLogin(
  challengeId: string,
  idToken: string,
  csrf: string,
): Promise<BrowserSession> {
  const value: unknown = await (
    await request('google/exchange', { challengeId, idToken }, csrf)
  ).json();
  if (!record(value) || !uuid(value.accountId) || !instant(value.expiresAt))
    throw new BrowserAuthError(503);
  return { accountId: value.accountId, expiresAt: value.expiresAt };
}
export async function logoutBrowser(): Promise<void> {
  await request('logout', {}, await browserCsrf());
}
export async function renewBrowserSession(): Promise<BrowserSession> {
  const value: unknown = await (await request('session/renew', {}, await browserCsrf())).json();
  if (!record(value) || !uuid(value.accountId) || !instant(value.expiresAt))
    throw new BrowserAuthError(503);
  return { accountId: value.accountId, expiresAt: value.expiresAt };
}
export async function deleteBrowserAccount(accountId: string): Promise<void> {
  const body: paths['/api/v1/auth/account/delete']['post']['requestBody']['content']['application/json'] =
    { confirmation: 'DELETE', accountId };
  await request('account/delete', body, await browserCsrf());
}
