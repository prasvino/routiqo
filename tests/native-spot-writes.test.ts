import { describe, expect, it, vi } from 'vitest';
import {
  blockNativeSpotAuthor,
  deleteNativeSpotPost,
  reportNativeSpotItem,
  submitNativeSpotContribution,
  voteNativeSpotItem,
} from '../apps/mobile/src/features/spots/native-spots';
import { NativeHttpStatus } from '../apps/mobile/src/auth/safe-transport';
import type { QueuedSpotContribution } from '../packages/shared/src/spot-contributions';

const account = '00000000-0000-4000-8000-000000000001';
const id = (n: number) => `00000000-0000-4000-8000-${n.toString(16).padStart(12, '0')}`;
const receipt = {
  ref: id(9),
  status: 'active',
  expiresAt: '2026-11-05T07:30:00Z',
  alias: 'Calm Auto',
};

function identity(response: unknown) {
  const verifiedRequest = vi.fn(async (..._args: unknown[]) => {
    if (response instanceof Error) throw response;
    return response;
  });
  return {
    verifiedRequest,
    identity: {
      activeAccount: () => account,
      revision: () => 1,
      verifiedRequest,
    } as unknown as Parameters<typeof submitNativeSpotContribution>[0],
  };
}

const post: QueuedSpotContribution = {
  kind: 'post',
  clientKey: id(1),
  spotId: id(2),
  journeyId: id(3),
  capturedAt: '2026-11-05T06:30:00.000Z',
  type: 'traffic',
  text: 'Lane 3 moving',
  attempts: 2,
  nextAttemptAt: 0,
};

describe('Spot writes', () => {
  it('sends queued items exactly as queued, without queue bookkeeping', async () => {
    const { identity: current, verifiedRequest } = identity(receipt);
    await expect(submitNativeSpotContribution(current, account, post)).resolves.toEqual(receipt);
    expect(verifiedRequest).toHaveBeenCalledWith('/api/v1/native/spots/posts', 'POST', {
      accountId: account,
      body: {
        clientKey: id(1),
        spotId: id(2),
        capturedAt: '2026-11-05T06:30:00.000Z',
        journeyId: id(3),
        type: 'traffic',
        text: 'Lane 3 moving',
      },
    });
    await submitNativeSpotContribution(current, account, {
      ...post,
      kind: 'signal',
      category: 'traffic',
      value: 'slow',
    } as QueuedSpotContribution);
    expect(verifiedRequest.mock.calls[1]![0]).toBe('/api/v1/native/spots/signals');
    expect((verifiedRequest.mock.calls[1]![2] as { body: object }).body).toEqual({
      clientKey: id(1),
      spotId: id(2),
      capturedAt: '2026-11-05T06:30:00.000Z',
      journeyId: id(3),
      category: 'traffic',
      value: 'slow',
    });
  });

  it('maps every refusal to a specific code', async () => {
    for (const [status, code] of [
      [401, 'session'],
      [403, 'forbidden'],
      [404, 'not-found'],
      [409, 'conflict'],
      [410, 'too-old'],
      [422, 'contact-details'],
      [429, 'rate-limited'],
      [400, 'invalid'],
      [503, 'unavailable'],
    ] as const) {
      const { identity: current } = identity(new NativeHttpStatus(status));
      await expect(submitNativeSpotContribution(current, account, post)).rejects.toMatchObject({
        code,
      });
    }
  });

  it('votes, deletes, reports and blocks on item paths and checks every answer', async () => {
    const vote = identity({
      ref: id(9),
      status: 'active',
      expiresAt: '2026-11-05T07:30:00Z',
      stillTrue: 3,
    });
    await expect(
      voteNativeSpotItem(vote.identity, account, id(9), 'still_true'),
    ).resolves.toMatchObject({
      stillTrue: 3,
    });
    expect(vote.verifiedRequest.mock.calls[0]![0]).toBe(`/api/v1/native/spots/items/${id(9)}/vote`);
    const deleted = identity({ ...receipt, status: 'deleted' });
    await expect(deleteNativeSpotPost(deleted.identity, account, id(9))).resolves.toMatchObject({
      status: 'deleted',
    });
    const report = identity({
      receivedAt: '2026-11-05T06:30:00Z',
      receiptExpiresAt: '2026-11-12T06:30:00Z',
    });
    await reportNativeSpotItem(report.identity, account, id(9), id(77), 'spam');
    expect(report.verifiedRequest.mock.calls[0]![2]).toMatchObject({
      body: { requestId: id(77), reason: 'spam' },
    });
    const block = identity(null);
    await expect(blockNativeSpotAuthor(block.identity, account, id(9))).resolves.toBeUndefined();
    const badBlock = identity({ unexpected: true });
    await expect(blockNativeSpotAuthor(badBlock.identity, account, id(9))).rejects.toMatchObject({
      code: 'invalid',
    });
    const badVote = identity({ ref: id(9), status: 'gone' });
    await expect(
      voteNativeSpotItem(badVote.identity, account, id(9), 'still_true'),
    ).rejects.toMatchObject({
      code: 'invalid',
    });
    const never = identity(receipt);
    await expect(
      voteNativeSpotItem(never.identity, account, 'not-a-ref', 'still_true'),
    ).rejects.toMatchObject({
      code: 'invalid',
    });
    await expect(
      reportNativeSpotItem(never.identity, account, id(9), 'x', 'spam'),
    ).rejects.toMatchObject({
      code: 'invalid',
    });
    expect(never.verifiedRequest).not.toHaveBeenCalled();
  });
});
