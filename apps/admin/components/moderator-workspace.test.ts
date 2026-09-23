import { describe, expect, it } from 'vitest';
import { AdminApiError } from '../lib/admin-client';
import { isQueueUnavailableForGrantAdmin } from '../lib/admin-workspace-access';

describe('grant-only admin workspace', () => {
  it('hides the moderator report area when queue authorization is absent', () => {
    expect(isQueueUnavailableForGrantAdmin(new AdminApiError(404), true)).toBe(true);
    expect(isQueueUnavailableForGrantAdmin(new AdminApiError(403), true)).toBe(true);
    expect(isQueueUnavailableForGrantAdmin(new AdminApiError(503), true)).toBe(false);
    expect(isQueueUnavailableForGrantAdmin(new AdminApiError(404), false)).toBe(false);
  });
});
