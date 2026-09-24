import { timeAgo } from '../utils/formatTime';
import './DeviceStatusPanel.css';

// Shows every device that has ever registered with the coordinator, with a
// live online/offline dot. "online" comes from the server's WebSocket
// presence tracking (PresenceEventListener) - it's true only while that
// device currently has an open WebSocket session, not just because it once
// existed.
export default function DeviceStatusPanel({ devices }) {
  return (
    <section className="panel">
      <h2>Devices</h2>
      {devices.length === 0 ? (
        <p className="empty-state">No devices have registered yet.</p>
      ) : (
        <ul className="device-list">
          {devices.map((device) => (
            <li key={device.deviceId} className="device-row">
              <span className={`status-dot ${device.online ? 'online' : 'offline'}`} />
              <span className="device-name">{device.displayName || device.deviceId}</span>
              <span className="device-meta">
                {device.online ? 'online' : `last seen ${timeAgo(device.lastSeenAt)}`}
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
