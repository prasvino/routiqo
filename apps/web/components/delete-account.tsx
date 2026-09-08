'use client';
import { useState } from 'react';
import { BrowserAuthError, deleteBrowserAccount } from '../lib/browser-auth';
import { Modal } from './modal';

export function DeleteAccount({
  accountId,
  onClose,
  onDeleted,
  onSignIn,
}: {
  accountId: string;
  onClose: () => void;
  onDeleted: () => void;
  onSignIn: () => void;
}) {
  const [confirmation, setConfirmation] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [reauthenticate, setReauthenticate] = useState(false);
  async function remove() {
    if (busy || confirmation !== 'DELETE') return;
    setBusy(true);
    setError('');
    try {
      await deleteBrowserAccount(accountId);
      onDeleted();
    } catch (failure) {
      if (
        failure instanceof BrowserAuthError &&
        (failure.status === 428 || failure.status === 401)
      ) {
        setReauthenticate(true);
        setError('Sign in with Google again, then reopen this dialog to confirm deletion.');
      } else
        setError(
          'Deletion could not be confirmed. Check your connection and account status before trying again.',
        );
    } finally {
      setBusy(false);
    }
  }
  return (
    <Modal
      title="Delete your Routiqo account?"
      onClose={() => {
        if (!busy) onClose();
      }}
    >
      <p className="modal-intro">
        This permanently deletes your Routiqo account and server journey records, and signs you out
        on every device.
      </p>
      <p className="modal-intro">
        Plans and saved places on this device stay here. Your Google account is unaffected.
      </p>
      <form
        className="plan-form"
        onSubmit={(event) => {
          event.preventDefault();
          void remove();
        }}
      >
        <label>
          Type DELETE to confirm
          <input
            value={confirmation}
            onChange={(event) => setConfirmation(event.target.value)}
            autoComplete="off"
            spellCheck={false}
            disabled={busy || reauthenticate}
          />
        </label>
        {error && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}
        <div className="detail-actions">
          <button type="button" className="button secondary" disabled={busy} onClick={onClose}>
            Keep account
          </button>
          {reauthenticate ? (
            <button type="button" className="button primary" onClick={onSignIn}>
              Sign in again
            </button>
          ) : (
            <button
              type="submit"
              className="button danger"
              disabled={busy || confirmation !== 'DELETE'}
            >
              {busy ? 'Deleting…' : 'Delete account'}
            </button>
          )}
        </div>
      </form>
    </Modal>
  );
}
