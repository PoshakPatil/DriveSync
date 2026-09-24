import { useState } from 'react';
import { timeAgo, shortHash } from '../utils/formatTime';
import './ConflictsPanel.css';

// Surfaces conflicts (milestone 5's ConflictResolver, mirrored here) and
// lets the user override the automatic "newer edit wins the name" choice.
// The override is never the ONLY way a conflict gets resolved safely -
// both versions already exist as real, synced files the moment a conflict
// is detected (see LEARNING.md) - this is purely about which one keeps the
// original filename going forward.
export default function ConflictsPanel({ conflicts, onResolve }) {
  const unresolved = conflicts.filter((c) => !c.resolved);
  const resolved = conflicts.filter((c) => c.resolved);

  return (
    <section className="panel">
      <h2>
        Conflicts
        {unresolved.length > 0 && <span className="conflict-badge">{unresolved.length}</span>}
      </h2>
      {unresolved.length === 0 ? (
        <p className="empty-state">No active conflicts.</p>
      ) : (
        <ul className="conflict-list">
          {unresolved.map((conflict) => (
            <ConflictCard key={conflict.id} conflict={conflict} onResolve={onResolve} />
          ))}
        </ul>
      )}

      {resolved.length > 0 && (
        <details className="resolved-history">
          <summary>{resolved.length} resolved</summary>
          <ul className="conflict-list resolved">
            {resolved.map((conflict) => (
              <li key={conflict.id} className="conflict-card resolved-card">
                <div className="conflict-path">{conflict.relativePath}</div>
                <div className="conflict-resolution-note">
                  Kept <strong>{conflict.resolvedKeepDeviceId}</strong>'s version, {timeAgo(conflict.resolvedAt)}
                </div>
              </li>
            ))}
          </ul>
        </details>
      )}
    </section>
  );
}

function ConflictCard({ conflict, onResolve }) {
  const [resolving, setResolving] = useState(null); // which deviceId is being applied, or null

  const handlePick = async (deviceId) => {
    setResolving(deviceId);
    try {
      await onResolve(conflict.id, deviceId);
    } finally {
      setResolving(null);
    }
  };

  return (
    <li className="conflict-card">
      <div className="conflict-path">{conflict.relativePath}</div>
      <div className="conflict-backup-note">
        Both versions kept — the other is saved as <code>{conflict.conflictedCopyPath}</code>
      </div>
      <div className="conflict-options">
        <ConflictOption
          label="keeps the name now"
          deviceId={conflict.winningDeviceId}
          hash={conflict.winningHash}
          isCurrent
          disabled={resolving !== null}
          resolving={resolving === conflict.winningDeviceId}
          onPick={() => handlePick(conflict.winningDeviceId)}
        />
        <ConflictOption
          label="saved as backup"
          deviceId={conflict.losingDeviceId}
          hash={conflict.losingHash}
          isCurrent={false}
          disabled={resolving !== null}
          resolving={resolving === conflict.losingDeviceId}
          onPick={() => handlePick(conflict.losingDeviceId)}
        />
      </div>
      <div className="conflict-detected-at">detected {timeAgo(conflict.detectedAt)}</div>
    </li>
  );
}

function ConflictOption({ label, deviceId, hash, isCurrent, disabled, resolving, onPick }) {
  return (
    <div className={`conflict-option ${isCurrent ? 'current' : ''}`}>
      <div className="conflict-option-header">
        <span className="conflict-option-device">{deviceId}</span>
        <span className="conflict-option-label">{label}</span>
      </div>
      <div className="conflict-option-hash">{shortHash(hash)}</div>
      {!isCurrent && (
        <button type="button" disabled={disabled} onClick={onPick}>
          {resolving ? 'Applying…' : 'Keep this version instead'}
        </button>
      )}
    </div>
  );
}
