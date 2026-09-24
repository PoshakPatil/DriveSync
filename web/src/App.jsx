import { useCallback, useEffect, useState } from 'react';
import { fetchDevices } from './api/deviceApi';
import { fetchRecentActivity } from './api/activityApi';
import { fetchConflicts, resolveConflict } from './api/conflictApi';
import { connectLiveSync } from './ws/liveSyncClient';
import DeviceStatusPanel from './components/DeviceStatusPanel';
import ActivityFeed from './components/ActivityFeed';
import ConflictsPanel from './components/ConflictsPanel';
import './App.css';

const ACTIVITY_LIMIT = 50;

function App() {
  const [devices, setDevices] = useState([]);
  const [activity, setActivity] = useState([]);
  const [conflicts, setConflicts] = useState([]);
  const [connectionError, setConnectionError] = useState(null);

  // Initial load over REST - the dashboard's "catch-up" equivalent. After
  // this, WebSocket pushes keep everything current without re-polling.
  useEffect(() => {
    Promise.all([fetchDevices(), fetchRecentActivity(ACTIVITY_LIMIT), fetchConflicts()])
      .then(([deviceList, activityList, conflictList]) => {
        setDevices(deviceList);
        setActivity(activityList);
        setConflicts(conflictList);
      })
      .catch((err) => setConnectionError(err.message));
  }, []);

  // Live updates over the same STOMP topics the watcher clients subscribe to.
  useEffect(() => {
    const disconnect = connectLiveSync({
      onChange: (change) => {
        setActivity((prev) => {
          if (prev.some((c) => c.id === change.id)) return prev; // already have it from initial load
          return [change, ...prev].slice(0, ACTIVITY_LIMIT);
        });
      },
      onDeviceStatus: (device) => {
        setDevices((prev) => {
          const others = prev.filter((d) => d.deviceId !== device.deviceId);
          return [...others, device].sort((a, b) => a.deviceId.localeCompare(b.deviceId));
        });
      },
      onConflict: (conflict) => {
        setConflicts((prev) => {
          const others = prev.filter((c) => c.id !== conflict.id);
          return [conflict, ...others];
        });
      },
    });
    return disconnect;
  }, []);

  const handleResolve = useCallback(async (conflictId, keepDeviceId) => {
    // The resulting ConflictResponse also arrives via /topic/conflicts, but
    // applying it here too makes the UI feel instant rather than waiting on
    // the round trip - the WS update that follows is a harmless no-op merge.
    const updated = await resolveConflict(conflictId, keepDeviceId);
    setConflicts((prev) => prev.map((c) => (c.id === updated.id ? updated : c)));
  }, []);

  return (
    <div className="app-shell">
      <header className="app-header">
        <h1>DriveSync</h1>
        <p className="subtitle">Cross-device file sync — live dashboard</p>
        {connectionError && <p className="connection-error">Could not reach the coordinator: {connectionError}</p>}
      </header>

      <div className="dashboard-grid">
        <DeviceStatusPanel devices={devices} />
        <ConflictsPanel conflicts={conflicts} onResolve={handleResolve} />
        <div className="activity-panel">
          <ActivityFeed activity={activity} />
        </div>
      </div>
    </div>
  );
}

export default App;
