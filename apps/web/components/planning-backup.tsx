'use client';
import { useRef, useState } from 'react';
import { Download, Upload, ArrowRight } from 'lucide-react';
import {
  MAX_BACKUP_BYTES,
  parsePlanningBackup,
  mergePlanningBackup,
  localDate,
  type PlanningBackup,
} from '@routiqo/shared';
import { usePlanning } from './planning-provider';
import { Modal } from './modal';

export function PlanningBackupControls() {
  const { state, ready, exportBackup, restoreBackup } = usePlanning();
  const [mode, setMode] = useState<'export' | 'import' | null>(null);
  const [backup, setBackup] = useState<PlanningBackup | null>(null);
  const [reading, setReading] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [backupText, setBackupText] = useState<string | null>(null);
  const request = useRef(0);
  function close() {
    request.current++;
    setMode(null);
    setBackupText(null);
    setBackup(null);
    setError('');
    setReading(false);
  }
  async function selectFile(file: File | undefined) {
    const current = ++request.current;
    setBackup(null);
    setError('');
    if (!file) {
      setReading(false);
      return;
    }
    setReading(true);
    try {
      if (file.size > MAX_BACKUP_BYTES) throw new Error('Choose a backup smaller than 512 KB.');
      const parsed = parsePlanningBackup(await file.text());
      if (request.current === current) setBackup(parsed);
    } catch (cause) {
      if (request.current === current)
        setError(cause instanceof Error ? cause.message : 'This file could not be read.');
    } finally {
      if (request.current === current) setReading(false);
    }
  }
  function download() {
    try {
      const content = exportBackup();
      const url = URL.createObjectURL(new Blob([content], { type: 'application/json' }));
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = `routiqo-plans-${localDate()}.json`;
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 60000);
      close();
      setNotice('Backup download requested. Check your browser downloads.');
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Backup could not be created.');
    }
  }
  let preview: ReturnType<typeof mergePlanningBackup> | null = null;
  let previewError = '';
  if (backup) {
    try {
      preview = mergePlanningBackup(state, backup.data);
    } catch (cause) {
      previewError = cause instanceof Error ? cause.message : 'This backup cannot be restored.';
    }
  }
  return (
    <>
      <button
        className="settings-row"
        disabled={!ready}
        onClick={() => {
          setMode('export');
          setNotice('');
        }}
      >
        <Download size={21} />
        <div>
          <strong>Download a planning backup</strong>
          <p>Keep a copy of your plans and saved places.</p>
        </div>
        <ArrowRight size={18} />
      </button>
      <button
        className="settings-row"
        disabled={!ready}
        onClick={() => {
          setMode('import');
          setNotice('');
        }}
      >
        <Upload size={21} />
        <div>
          <strong>Restore a planning backup</strong>
          <p>Add plans from a Routiqo backup file.</p>
        </div>
        <ArrowRight size={18} />
      </button>
      {notice && (
        <p className="backup-notice" role="status">
          {notice}
        </p>
      )}
      {mode && (
        <Modal
          title={mode === 'export' ? 'Download your backup' : 'Restore your backup'}
          onClose={close}
        >
          {mode === 'export' ? (
            <>
              <p className="modal-intro">
                The file includes your route names, dates, notes and saved places. It is not
                encrypted. Keep it somewhere private.
              </p>
              <p className="local-strip">
                This saves a file through your browser. Routiqo does not upload it.
              </p>
              <button
                className="button secondary"
                onClick={() => {
                  try {
                    setBackupText(exportBackup());
                    setError('');
                  } catch (cause) {
                    setError(
                      cause instanceof Error ? cause.message : 'Backup could not be created.',
                    );
                  }
                }}
              >
                View backup text
              </button>
              {backupText !== null && (
                <label className="backup-file-label">
                  Backup JSON
                  <span className="fine-print">
                    If downloads are unavailable, copy this text and save it as a .json file. Select
                    the field to select all text.
                  </span>
                  <textarea
                    className="backup-json"
                    readOnly
                    value={backupText}
                    rows={6}
                    onFocus={(event) => event.currentTarget.select()}
                  />
                </label>
              )}
            </>
          ) : (
            <>
              <p className="modal-intro">
                Choose a Routiqo JSON backup, up to 512 KB. Existing plans stay unchanged when the
                same plan is already saved.
              </p>
              <label className="backup-file-label">
                Backup file
                <input
                  type="file"
                  accept="application/json,.json"
                  onChange={(event) => {
                    void selectFile(event.currentTarget.files?.[0]);
                  }}
                />
              </label>
              {reading && (
                <p role="status" className="backup-notice">
                  Reading backup…
                </p>
              )}
              {backup && (
                <div className="backup-preview">
                  <h3>Ready to review</h3>
                  <p>
                    Plans in file: {backup.data.plans.length} · Saved places:{' '}
                    {backup.data.saved.length}
                  </p>
                  {preview && (
                    <p>
                      New plans: {preview.summary.addedPlans} · New saved places:{' '}
                      {preview.summary.addedPlaces} · Existing plans kept:{' '}
                      {preview.summary.keptPlans}
                    </p>
                  )}
                  <p className="fine-print">
                    Times use this device’s local timezone. Nothing changes until you select Restore
                    backup.
                  </p>
                </div>
              )}
            </>
          )}
          {(error || previewError) && (
            <p className="form-error" role="alert">
              {error || previewError}
            </p>
          )}
          <div className="detail-actions">
            <button className="button secondary" onClick={close}>
              Cancel
            </button>
            {mode === 'export' ? (
              <button className="button primary" onClick={download}>
                Download JSON
              </button>
            ) : (
              <button
                className="button primary"
                disabled={!backup || !preview || reading}
                onClick={() => {
                  if (!backup) return;
                  try {
                    const result = restoreBackup(backup.data);
                    close();
                    setNotice(
                      `Restore complete. New plans: ${result.addedPlans}. New saved places: ${result.addedPlaces}. Existing plans were kept.`,
                    );
                  } catch (cause) {
                    setError(
                      cause instanceof Error
                        ? cause.message
                        : 'Restore failed. Your data was not replaced.',
                    );
                  }
                }}
              >
                Restore backup
              </button>
            )}
          </div>
        </Modal>
      )}
    </>
  );
}
