import { timeAgo, shortHash } from '../utils/formatTime';
import './ActivityFeed.css';

const TYPE_LABEL = {
  CREATED: 'created',
  MODIFIED: 'modified',
  DELETED: 'deleted',
};

// Chronological (newest-first) feed of every file change the coordinator
// has recorded, across all devices. Backed by FileChangeRecord - the
// append-only log described in LEARNING.md's milestone 3 section - so this
// view is simply "read the log", no separate tracking needed.
export default function ActivityFeed({ activity }) {
  return (
    <section className="panel">
      <h2>Activity</h2>
      {activity.length === 0 ? (
        <p className="empty-state">No file changes yet. Start a watcher and edit a file.</p>
      ) : (
        <ul className="activity-list">
          {activity.map((change) => (
            <li key={change.id} className={`activity-row type-${change.changeType.toLowerCase()}`}>
              <span className="activity-type">{TYPE_LABEL[change.changeType] ?? change.changeType}</span>
              <span className="activity-path" title={change.relativePath}>{change.relativePath}</span>
              <span className="activity-device">{change.deviceId}</span>
              <span className="activity-hash">{shortHash(change.contentHash)}</span>
              <span className="activity-time">{timeAgo(change.serverReceivedAt)}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
