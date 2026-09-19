import {
  BrowserLiveError,
  liveRequest,
  readConsent,
  readConsentIntent,
  readRouteBinding,
  readRouteBindingResult,
  readRouteContextResult,
  readSignalAcceptance,
  readSignalGrant,
  readSignalIssue,
  readSignalReceipt,
  readUuid,
  type BrowserLiveErrorKind,
  type LiveConsent,
  type LiveConsentIntent,
  type LiveRouteBinding,
  type LiveRouteBindingResult,
  type LiveRouteContext,
  type LiveRouteContextRead,
  type LiveSignalAcceptance,
  type LiveSignalGrant,
  type LiveSignalIssue,
  type LiveSignalReceipt,
} from './browser-live-private';

export {
  BrowserLiveError,
  type BrowserLiveErrorKind,
  type LiveConsent,
  type LiveConsentIntent,
  type LiveRouteBinding,
  type LiveRouteBindingResult,
  type LiveRouteContext,
  type LiveRouteContextRead,
  type LiveSignalAcceptance,
  type LiveSignalGrant,
  type LiveSignalIssue,
  type LiveSignalReceipt,
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
