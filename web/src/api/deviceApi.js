// REST calls for device status - the dashboard never writes devices, only reads.
export async function fetchDevices() {
  const res = await fetch('/api/devices');
  if (!res.ok) throw new Error(`Failed to fetch devices: HTTP ${res.status}`);
  return res.json();
}
