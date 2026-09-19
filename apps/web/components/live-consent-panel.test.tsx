// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  BrowserLiveError,
  readBrowserLiveConsent,
  submitBrowserLiveConsent,
  type LiveConsent,
} from '../lib/browser-live';
import { LiveConsentPanel } from './live-consent-panel';

vi.mock('../lib/browser-live', async (load) => {
  const actual = await load<typeof import('../lib/browser-live')>();
  return {
    ...actual,
    readBrowserLiveConsent: vi.fn(),
    submitBrowserLiveConsent: vi.fn(),
  };
});

const accountId = '00000000-0000-4000-8000-000000000001';
const journeyId = '00000000-0000-4000-8000-000000000002';
const nextAccountId = '00000000-0000-4000-8000-000000000003';
const nextJourneyId = '00000000-0000-4000-8000-000000000004';

function consent(overrides: Partial<LiveConsent> = {}): LiveConsent {
  return {
    journeyId,
    generation: '9007199254740993',
    sharing: false,
    journeyActive: true,
    ...overrides,
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((accept, decline) => {
    resolve = accept;
    reject = decline;
  });
  return { promise, resolve, reject };
}

function panel(overrides: Partial<React.ComponentProps<typeof LiveConsentPanel>> = {}) {
  return (
    <LiveConsentPanel accountId={accountId} journeyId={journeyId} online available {...overrides} />
  );
}

beforeEach(() => {
  vi.mocked(readBrowserLiveConsent).mockResolvedValue(consent());
  vi.mocked(submitBrowserLiveConsent).mockResolvedValue(
    consent({ generation: '9007199254740994', sharing: true }),
  );
  Object.defineProperty(document, 'visibilityState', {
    configurable: true,
    value: 'visible',
  });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.resetAllMocks();
});

describe('LiveConsentPanel', () => {
  it('does not request on mount and offers an explicit safe stop from unknown generation zero', async () => {
    render(panel());
    expect(readBrowserLiveConsent).not.toHaveBeenCalled();
    expect(screen.queryByRole('button', { name: 'Allow private contributions' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Stop private contributions' }));
    await waitFor(() => expect(submitBrowserLiveConsent).toHaveBeenCalledTimes(1));
    expect(vi.mocked(submitBrowserLiveConsent).mock.calls[0]?.slice(0, 3)).toEqual([
      accountId,
      journeyId,
      { expectedGeneration: '0', sharing: false },
    ]);
  });

  it('allows only after a confirmed off read and preserves exact generations beyond safe integers', async () => {
    render(panel());
    fireEvent.click(screen.getByRole('button', { name: 'Check LIVE settings' }));
    const allow = await screen.findByRole('button', { name: 'Allow private contributions' });
    fireEvent.click(allow);
    await waitFor(() => expect(submitBrowserLiveConsent).toHaveBeenCalledTimes(1));
    expect(vi.mocked(submitBrowserLiveConsent).mock.calls[0]?.[2]).toEqual({
      expectedGeneration: '9007199254740993',
      sharing: true,
    });
  });

  it('never offers enable at the maximum generation', async () => {
    vi.mocked(readBrowserLiveConsent).mockResolvedValue(
      consent({ generation: '9223372036854775807' }),
    );
    render(panel());
    fireEvent.click(screen.getByRole('button', { name: 'Check LIVE settings' }));
    await screen.findByText(/Last checked: private LIVE contribution preparation is off/);
    expect(screen.queryByRole('button', { name: 'Allow private contributions' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Stop private contributions' })).toBeTruthy();
  });

  it('keeps a failed write unknown and requires explicit stop even after a later off read', async () => {
    vi.mocked(submitBrowserLiveConsent).mockRejectedValueOnce(new BrowserLiveError('conflict'));
    render(panel());
    fireEvent.click(screen.getByRole('button', { name: 'Check LIVE settings' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Allow private contributions' }));
    await screen.findByRole('alert');
    expect(screen.getByRole('alert').textContent).toMatch(/changed on the server/);
    expect(screen.queryByRole('button', { name: 'Allow private contributions' })).toBeNull();
    expect(submitBrowserLiveConsent).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByRole('button', { name: 'Check LIVE settings' }));
    await screen.findByText(/previous change was not confirmed/);
    expect(screen.queryByRole('button', { name: 'Allow private contributions' })).toBeNull();
    vi.mocked(submitBrowserLiveConsent).mockResolvedValueOnce(
      consent({ generation: '9007199254740994', sharing: false }),
    );
    fireEvent.click(screen.getByRole('button', { name: 'Stop private contributions' }));
    await screen.findByText(/is stopped for this journey/);
  });

  it('classifies rate and authentication failures without exposing prior known state', async () => {
    render(panel());
    fireEvent.click(screen.getByRole('button', { name: 'Check LIVE settings' }));
    await screen.findByRole('button', { name: 'Allow private contributions' });
    vi.mocked(submitBrowserLiveConsent).mockRejectedValueOnce(new BrowserLiveError('rate_limited'));
    fireEvent.click(screen.getByRole('button', { name: 'Allow private contributions' }));
    await screen.findByText(/Wait, then try again manually/);
    expect(screen.queryByRole('button', { name: 'Allow private contributions' })).toBeNull();

    vi.mocked(readBrowserLiveConsent).mockRejectedValueOnce(new BrowserLiveError('authentication'));
    fireEvent.click(screen.getByRole('button', { name: 'Check LIVE settings' }));
    await screen.findByText(/Sign-in could not be confirmed/);
    expect(screen.queryByRole('button', { name: 'Allow private contributions' })).toBeNull();
  });

  it('uses a synchronous guard so repeated clicks start one mutation', async () => {
    const pending = deferred<LiveConsent>();
    vi.mocked(submitBrowserLiveConsent).mockReturnValue(pending.promise);
    render(panel());
    const stop = screen.getByRole('button', { name: 'Stop private contributions' });
    fireEvent.click(stop);
    fireEvent.click(stop);
    expect(submitBrowserLiveConsent).toHaveBeenCalledTimes(1);
    expect((screen.getByRole('button', { name: 'Working…' }) as HTMLButtonElement).disabled).toBe(
      true,
    );
    pending.resolve(consent({ generation: '1' }));
    await screen.findByText(/is stopped for this journey/);
  });

  it('moves keyboard focus from a completed allow action to the safe stop action', async () => {
    const pending = deferred<LiveConsent>();
    vi.mocked(submitBrowserLiveConsent).mockReturnValueOnce(pending.promise);
    render(panel());
    fireEvent.click(screen.getByRole('button', { name: 'Check LIVE settings' }));
    const allow = await screen.findByRole('button', { name: 'Allow private contributions' });
    allow.focus();
    fireEvent.click(allow, { detail: 0 });
    pending.resolve(consent({ generation: '9007199254740994', sharing: true }));
    const stop = await screen.findByRole('button', { name: 'Stop private contributions' });
    await waitFor(() => expect(document.activeElement).toBe(stop));
  });

  it('does not steal focus when the user moved elsewhere during allow', async () => {
    const pending = deferred<LiveConsent>();
    vi.mocked(submitBrowserLiveConsent).mockReturnValueOnce(pending.promise);
    render(
      <>
        <button>Outside action</button>
        {panel()}
      </>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Check LIVE settings' }));
    const allow = await screen.findByRole('button', { name: 'Allow private contributions' });
    allow.focus();
    fireEvent.click(allow, { detail: 0 });
    const outside = screen.getByRole('button', { name: 'Outside action' });
    outside.focus();
    pending.resolve(consent({ generation: '9007199254740994', sharing: true }));
    await screen.findByText(/Public LIVE remains unavailable/);
    expect(document.activeElement).toBe(outside);
  });

  it('discards an old read across account and journey changes', async () => {
    const pending = deferred<LiveConsent>();
    vi.mocked(readBrowserLiveConsent).mockReturnValueOnce(pending.promise);
    const view = render(panel());
    fireEvent.click(screen.getByRole('button', { name: 'Check LIVE settings' }));
    const signal = vi.mocked(readBrowserLiveConsent).mock.calls[0]?.[2];
    view.rerender(panel({ accountId: nextAccountId, journeyId: nextJourneyId }));
    expect(signal?.aborted).toBe(true);
    pending.resolve(consent({ sharing: true }));
    await Promise.resolve();
    expect(screen.queryByText(/Last checked/)).toBeNull();
    expect(readBrowserLiveConsent).toHaveBeenCalledTimes(1);
  });

  it('aborts and clears on offline, then reconnects without a request', async () => {
    const pending = deferred<LiveConsent>();
    vi.mocked(readBrowserLiveConsent).mockReturnValueOnce(pending.promise);
    const view = render(panel());
    fireEvent.click(screen.getByRole('button', { name: 'Check LIVE settings' }));
    const signal = vi.mocked(readBrowserLiveConsent).mock.calls[0]?.[2];
    view.rerender(panel({ online: false }));
    expect(signal?.aborted).toBe(true);
    expect(screen.getByText(/Connect to check or change/)).toBeTruthy();
    expect(
      (screen.getByRole('button', { name: 'Stop private contributions' }) as HTMLButtonElement)
        .disabled,
    ).toBe(true);
    view.rerender(panel({ online: true }));
    expect(readBrowserLiveConsent).toHaveBeenCalledTimes(1);
    expect(screen.getByText('Settings have not been checked.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Allow private contributions' })).toBeNull();
  });

  it('keeps an aborted enable uncertain until a successful explicit stop', async () => {
    const pending = deferred<LiveConsent>();
    vi.mocked(submitBrowserLiveConsent)
      .mockReturnValueOnce(pending.promise)
      .mockResolvedValueOnce(consent({ generation: '9007199254740995', sharing: false }));
    const view = render(panel());
    fireEvent.click(screen.getByRole('button', { name: 'Check LIVE settings' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Allow private contributions' }));
    const signal = vi.mocked(submitBrowserLiveConsent).mock.calls[0]?.[3];
    view.rerender(panel({ online: false }));
    expect(signal?.aborted).toBe(true);
    view.rerender(panel({ online: true }));
    expect((await screen.findByRole('alert')).textContent).toMatch(
      /previous change was not confirmed/i,
    );
    expect(submitBrowserLiveConsent).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByRole('button', { name: 'Check LIVE settings' }));
    await screen.findByText(/previous change was not confirmed/i);
    expect(screen.queryByRole('button', { name: 'Allow private contributions' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Stop private contributions' }));
    await screen.findByText(/is stopped for this journey/);
    expect(submitBrowserLiveConsent).toHaveBeenCalledTimes(2);
    pending.resolve(consent({ generation: '9007199254740994', sharing: true }));
    await Promise.resolve();
    expect(screen.getByText(/is stopped for this journey/)).toBeTruthy();
    expect(screen.queryByText(/is allowed for this journey/)).toBeNull();
  });

  it.each(['blur', 'hidden'] as const)(
    'clears known state on %s without refetching',
    async (event) => {
      render(panel());
      fireEvent.click(screen.getByRole('button', { name: 'Check LIVE settings' }));
      await screen.findByRole('button', { name: 'Allow private contributions' });
      if (event === 'hidden') {
        Object.defineProperty(document, 'visibilityState', {
          configurable: true,
          value: 'hidden',
        });
        fireEvent(document, new Event('visibilitychange'));
      } else {
        fireEvent.blur(window);
      }
      expect(screen.queryByRole('button', { name: 'Allow private contributions' })).toBeNull();
      expect(
        (screen.getByRole('button', { name: 'Stop private contributions' }) as HTMLButtonElement)
          .disabled,
      ).toBe(true);
      expect(readBrowserLiveConsent).toHaveBeenCalledTimes(1);
    },
  );

  it.each(['blur', 'hidden'] as const)('discards a late response after %s', async (event) => {
    const pending = deferred<LiveConsent>();
    vi.mocked(readBrowserLiveConsent).mockReturnValueOnce(pending.promise);
    render(panel());
    fireEvent.click(screen.getByRole('button', { name: 'Check LIVE settings' }));
    const signal = vi.mocked(readBrowserLiveConsent).mock.calls[0]?.[2];
    if (event === 'hidden') {
      Object.defineProperty(document, 'visibilityState', {
        configurable: true,
        value: 'hidden',
      });
      fireEvent(document, new Event('visibilitychange'));
    } else {
      fireEvent.blur(window);
    }
    expect(signal?.aborted).toBe(true);
    pending.resolve(consent({ sharing: true }));
    await Promise.resolve();
    expect(screen.queryByText(/Last checked/)).toBeNull();
    expect(readBrowserLiveConsent).toHaveBeenCalledTimes(1);
  });

  it('latches a completed response until the identity changes', async () => {
    vi.mocked(readBrowserLiveConsent)
      .mockResolvedValueOnce(consent({ journeyActive: false, generation: '4' }))
      .mockResolvedValueOnce(consent({ journeyActive: true, generation: '5' }))
      .mockResolvedValueOnce(
        consent({ journeyId: nextJourneyId, journeyActive: true, generation: '0' }),
      );
    const view = render(panel());
    fireEvent.click(screen.getByRole('button', { name: 'Check LIVE settings' }));
    await screen.findByText(/journey is complete/);
    fireEvent.click(screen.getByRole('button', { name: 'Check LIVE settings' }));
    await waitFor(() => expect(readBrowserLiveConsent).toHaveBeenCalledTimes(2));
    expect(screen.queryByRole('button', { name: 'Allow private contributions' })).toBeNull();

    view.rerender(panel({ journeyId: nextJourneyId }));
    fireEvent.click(screen.getByRole('button', { name: 'Check LIVE settings' }));
    expect(await screen.findByRole('button', { name: 'Allow private contributions' })).toBeTruthy();
  });
});
