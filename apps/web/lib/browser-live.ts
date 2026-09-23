import {
  BrowserLiveError,
  liveRequest,
  readExpectedSignalGrant,
  readExpectedSignalIssue,
  readConsent,
  readConsentIntent,
  readRouteBinding,
  readRouteBindingResult,
  readRouteContextResult,
  readSignalAcceptance,
  readSignalChoiceSnapshot,
  readSignalGrant,
  readSignalIssue,
  readSignalReceipt,
  readSignalStopResponse,
  readPublicSignalShareRequest,
  readPublicSignalShareResponse,
  readPublicSignalStopResponse,
  readPublicIntentPage,
  readUuid,
  type BrowserLiveErrorKind,
  type LiveConsent,
  type LiveConsentIntent,
  type LiveExpectedSignalIssue,
  type LiveRouteBinding,
  type LiveRouteBindingResult,
  type LiveRouteContext,
  type LiveRouteContextRead,
  type LiveSignalAcceptance,
  type LiveSignalChoice,
  type LiveSignalChoiceSnapshot,
  type LiveSignalGrant,
  type LiveSignalIssue,
  type LiveSignalReceipt,
  type LiveSignalStopResponse,
  type LivePublicSignalShareRequest,
  type LivePublicSignalShareResponse,
  type LivePublicSignalStopResponse,
  type LivePublicIntent,
  type LivePublicIntentPage,
} from './browser-live-private';

export {
  BrowserLiveError,
  type BrowserLiveErrorKind,
  type LiveConsent,
  type LiveConsentIntent,
  type LiveExpectedSignalIssue,
  type LiveRouteBinding,
  type LiveRouteBindingResult,
  type LiveRouteContext,
  type LiveRouteContextRead,
  type LiveSignalAcceptance,
  type LiveSignalChoice,
  type LiveSignalChoiceSnapshot,
  type LiveSignalGrant,
  type LiveSignalIssue,
  type LiveSignalReceipt,
  type LiveSignalStopResponse,
  type LivePublicSignalShareRequest,
  type LivePublicSignalShareResponse,
  type LivePublicSignalStopResponse,
  type LivePublicIntent,
  type LivePublicIntentPage,
};

function identities(accountId: unknown, journeyId: unknown): [string, string] {
  return [readUuid(accountId), readUuid(journeyId)];
}

export function readBrowserLiveConsent(
  accountId: string,
  journeyId: string,
  signal?: AbortSignal,
): Promise<LiveConsent> {
  [accountId, journeyId] = identities(accountId, journeyId);
  return liveRequest({
    accountId,
    path: `/api/v1/journeys/${journeyId}/consent`,
    timeoutMilliseconds: 12000,
    signal,
    validate: (value) => readConsent(value, journeyId),
  });
}

export function submitBrowserLiveConsent(
  accountId: string,
  journeyId: string,
  input: unknown,
  signal?: AbortSignal,
): Promise<LiveConsent> {
  [accountId, journeyId] = identities(accountId, journeyId);
  const body = readConsentIntent(input);
  return liveRequest({
    accountId,
    path: `/api/v1/journeys/${journeyId}/consent`,
    body,
    timeoutMilliseconds: 12000,
    signal,
    validate: (value) => readConsent(value, journeyId, body),
  });
}

export function readBrowserLiveRouteContext(
  accountId: string,
  journeyId: string,
  signal?: AbortSignal,
): Promise<LiveRouteContextRead> {
  [accountId, journeyId] = identities(accountId, journeyId);
  return liveRequest({
    accountId,
    path: `/api/v1/journeys/${journeyId}/route-context`,
    timeoutMilliseconds: 12000,
    signal,
    validate: readRouteContextResult,
  });
}

export function bindBrowserLiveRouteContext(
  accountId: string,
  journeyId: string,
  input: unknown,
  signal?: AbortSignal,
): Promise<LiveRouteBindingResult> {
  [accountId, journeyId] = identities(accountId, journeyId);
  const body = readRouteBinding(input);
  return liveRequest({
    accountId,
    path: `/api/v1/journeys/${journeyId}/route-context`,
    body,
    timeoutMilliseconds: 30000,
    signal,
    validate: readRouteBindingResult,
  });
}

export function issueBrowserLiveSignalCommand(
  accountId: string,
  journeyId: string,
  input: unknown,
  signal?: AbortSignal,
): Promise<LiveSignalGrant> {
  [accountId, journeyId] = identities(accountId, journeyId);
  const body = readSignalIssue(input);
  return liveRequest({
    accountId,
    path: `/api/v1/journeys/${journeyId}/signal-commands`,
    body,
    timeoutMilliseconds: 12000,
    signal,
    validate: (value) => readSignalGrant(value, body.anchorId),
  });
}

export function readBrowserLiveSignalChoices(
  accountId: string,
  journeyId: string,
  signal?: AbortSignal,
): Promise<LiveSignalChoiceSnapshot> {
  [accountId, journeyId] = identities(accountId, journeyId);
  return liveRequest({
    accountId,
    path: `/api/v1/journeys/${journeyId}/signal-choices`,
    timeoutMilliseconds: 12000,
    responseLimit: 262144,
    signal,
    validate: readSignalChoiceSnapshot,
  });
}

export function issueBrowserLiveExpectedSignalCommand(
  accountId: string,
  journeyId: string,
  input: unknown,
  signal?: AbortSignal,
): Promise<LiveSignalGrant> {
  [accountId, journeyId] = identities(accountId, journeyId);
  const body = readExpectedSignalIssue(input);
  return liveRequest({
    accountId,
    path: `/api/v1/journeys/${journeyId}/signal-commands/expected-context`,
    body,
    timeoutMilliseconds: 12000,
    signal,
    validate: (value) => readExpectedSignalGrant(value, body),
  });
}

export function acceptBrowserLiveSignalCommand(
  accountId: string,
  journeyId: string,
  commandId: string,
  input: unknown,
  signal?: AbortSignal,
): Promise<LiveSignalReceipt> {
  [accountId, journeyId] = identities(accountId, journeyId);
  commandId = readUuid(commandId);
  const body = readSignalAcceptance(input);
  return liveRequest({
    accountId,
    path: `/api/v1/journeys/${journeyId}/signals/${commandId}`,
    body,
    timeoutMilliseconds: 12000,
    signal,
    validate: (value) => readSignalReceipt(value, commandId, false),
  });
}

export function withdrawBrowserLiveSignalCommand(
  accountId: string,
  journeyId: string,
  commandId: string,
  signal?: AbortSignal,
): Promise<LiveSignalReceipt> {
  [accountId, journeyId] = identities(accountId, journeyId);
  commandId = readUuid(commandId);
  return liveRequest({
    accountId,
    path: `/api/v1/journeys/${journeyId}/signals/${commandId}/withdraw`,
    body: {},
    timeoutMilliseconds: 12000,
    signal,
    validate: (value) => readSignalReceipt(value, commandId, true),
  });
}

export function stopBrowserLiveSignalCommand(
  accountId: string,
  journeyId: string,
  commandId: string,
  signal?: AbortSignal,
): Promise<LiveSignalStopResponse> {
  [accountId, journeyId] = identities(accountId, journeyId);
  commandId = readUuid(commandId);
  return liveRequest({
    accountId,
    path: `/api/v1/journeys/${journeyId}/signal-commands/${commandId}/stop`,
    body: {},
    timeoutMilliseconds: 12000,
    signal,
    validate: (value) => readSignalStopResponse(value, commandId),
  });
}

export function shareBrowserLiveSignalPublicIntent(
  accountId: string,
  journeyId: string,
  commandId: string,
  input: unknown,
  signal?: AbortSignal,
): Promise<LivePublicSignalShareResponse> {
  [accountId, journeyId] = identities(accountId, journeyId);
  commandId = readUuid(commandId);
  const body = readPublicSignalShareRequest(input);
  return liveRequest({
    accountId,
    path: `/api/v1/journeys/${journeyId}/signals/${commandId}/public-intent`,
    body,
    timeoutMilliseconds: 12000,
    signal,
    validate: (value) => readPublicSignalShareResponse(value, commandId),
  });
}

export function stopBrowserLiveSignalPublicIntent(
  accountId: string,
  journeyId: string,
  commandId: string,
  signal?: AbortSignal,
): Promise<LivePublicSignalStopResponse> {
  [accountId, journeyId] = identities(accountId, journeyId);
  commandId = readUuid(commandId);
  return liveRequest({
    accountId,
    path: `/api/v1/journeys/${journeyId}/signals/${commandId}/public-intent/stop`,
    body: {},
    timeoutMilliseconds: 12000,
    signal,
    validate: (value) => readPublicSignalStopResponse(value, commandId),
  });
}

export function readBrowserLivePublicIntents(
  accountId: string,
  cursor?: string,
  signal?: AbortSignal,
): Promise<LivePublicIntentPage> {
  accountId = readUuid(accountId);
  if (cursor !== undefined && !/^[A-Za-z0-9_-]{1,128}$/.test(cursor))
    throw new BrowserLiveError('invalid');
  return liveRequest({
    accountId,
    path:
      cursor === undefined ? '/api/v1/public-intents' : `/api/v1/public-intents?cursor=${cursor}`,
    timeoutMilliseconds: 12000,
    signal,
    validate: readPublicIntentPage,
  });
}
