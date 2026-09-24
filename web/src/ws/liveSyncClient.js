import { Client } from '@stomp/stompjs';

// Live updates for the dashboard: subscribes to the same STOMP topics the
// watcher clients do (/topic/changes, /topic/devices, /topic/conflicts -
// see server's ChangeBroadcastService) so the UI updates in real time
// instead of needing a manual refresh or polling.
//
// Deliberately connects WITHOUT a ?deviceId= query param, unlike a watcher
// instance. The server's presence tracking (PresenceEventListener) only
// marks a device online/offline when it can read a deviceId off the
// handshake - by omitting it, the dashboard's own connection never shows
// up as a fake "device" in the device status panel, which should only ever
// list real watcher instances.
export function connectLiveSync({ onChange, onDeviceStatus, onConflict }) {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  const client = new Client({
    brokerURL: `${protocol}//${window.location.host}/ws`,
    reconnectDelay: 3000,
    onConnect: () => {
      client.subscribe('/topic/changes', (message) => {
        onChange?.(JSON.parse(message.body));
      });
      client.subscribe('/topic/devices', (message) => {
        onDeviceStatus?.(JSON.parse(message.body));
      });
      client.subscribe('/topic/conflicts', (message) => {
        onConflict?.(JSON.parse(message.body));
      });
    },
  });
  client.activate();
  return () => client.deactivate();
}
