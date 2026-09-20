// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PlanningBackupControls } from './planning-backup';
import { PlanningProvider } from './planning-provider';

const KEY = 'routiqo.planning.v1';
const validState = {
  version: 1,
  plans: [],
  saved: ['place-pondicherry'],
};

describe('PlanningBackupControls download cleanup', () => {
  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem(KEY, JSON.stringify(validState));
    HTMLDialogElement.prototype.showModal = vi.fn(function (this: HTMLDialogElement) {
      this.setAttribute('open', '');
    });
    HTMLDialogElement.prototype.close = vi.fn(function (this: HTMLDialogElement) {
      this.removeAttribute('open');
    });
  });

  afterEach(() => {
    cleanup();
    localStorage.clear();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('removes appended anchor and revokes blob URL immediately when anchor click throws', () => {
    const revokeSpy = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:https://routiqo.test/backup-uuid');
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {
      throw new Error('Download blocked by browser sandbox');
    });

    render(
      <PlanningProvider>
        <PlanningBackupControls />
      </PlanningProvider>,
    );

    // Open export modal
    fireEvent.click(screen.getByText('Download a planning backup'));
    expect(screen.getByRole('dialog', { name: 'Download your backup' })).toBeTruthy();

    // Trigger download
    fireEvent.click(screen.getByRole('button', { name: 'Download JSON' }));

    // In-dialog alert is displayed
    expect(screen.getByRole('alert').textContent).toBe('Download blocked by browser sandbox');
    expect(screen.queryByText(/Backup download requested/i)).toBeNull();

    // Anchor is removed from DOM and URL is revoked immediately
    expect(document.body.querySelectorAll('a')).toHaveLength(0);
    expect(revokeSpy).toHaveBeenCalledWith('blob:https://routiqo.test/backup-uuid');

    // Dialog remains open with fallback available
    fireEvent.click(screen.getByRole('button', { name: 'View backup text' }));
    expect((screen.getByLabelText(/Backup JSON/i) as HTMLTextAreaElement).value).toContain(
      'place-pondicherry',
    );

    // Recover: restore working click and download again
    clickSpy.mockImplementation(() => undefined);
    fireEvent.click(screen.getByRole('button', { name: 'Download JSON' }));
    expect(
      screen.getByText('Backup download requested. Check your browser downloads.'),
    ).toBeTruthy();
  });

  it('does not attempt revocation when URL creation fails', () => {
    const revokeSpy = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    vi.spyOn(URL, 'createObjectURL').mockImplementation(() => {
      throw new Error('Blob creation denied');
    });

    render(
      <PlanningProvider>
        <PlanningBackupControls />
      </PlanningProvider>,
    );

    fireEvent.click(screen.getByText('Download a planning backup'));
    fireEvent.click(screen.getByRole('button', { name: 'Download JSON' }));

    expect(screen.getByRole('alert').textContent).toBe('Blob creation denied');
    expect(revokeSpy).not.toHaveBeenCalled();
    expect(document.body.querySelectorAll('a')).toHaveLength(0);
  });

  it('removes anchor on successful click and schedules URL revocation after 60 seconds', () => {
    vi.useFakeTimers();
    const revokeSpy = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:https://routiqo.test/success-uuid');
    const clickSpy = vi
      .spyOn(HTMLAnchorElement.prototype, 'click')
      .mockImplementation(() => undefined);

    render(
      <PlanningProvider>
        <PlanningBackupControls />
      </PlanningProvider>,
    );

    fireEvent.click(screen.getByText('Download a planning backup'));
    fireEvent.click(screen.getByRole('button', { name: 'Download JSON' }));

    // Anchor removed immediately
    expect(document.body.querySelectorAll('a')).toHaveLength(0);
    expect(clickSpy).toHaveBeenCalledOnce();
    expect(
      screen.getByText('Backup download requested. Check your browser downloads.'),
    ).toBeTruthy();

    // Revocation scheduled for 60 seconds
    expect(revokeSpy).not.toHaveBeenCalled();
    act(() => {
      vi.advanceTimersByTime(59_999);
    });
    expect(revokeSpy).not.toHaveBeenCalled();

    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(revokeSpy).toHaveBeenCalledWith('blob:https://routiqo.test/success-uuid');
  });
});
