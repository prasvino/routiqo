import { AdminApiError } from './admin-client';

/** A grant-only administrator has an admin session, but no moderator queue permission. */
export function isQueueUnavailableForGrantAdmin(error: unknown, grantAdminEnabled: boolean) {
  return grantAdminEnabled && error instanceof AdminApiError && [403, 404].includes(error.status);
}
