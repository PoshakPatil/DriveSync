// REST calls for conflicts: listing and the manual-override resolve action.
export async function fetchConflicts() {
  const res = await fetch('/api/conflicts');
  if (!res.ok) throw new Error(`Failed to fetch conflicts: HTTP ${res.status}`);
  return res.json();
}

export async function resolveConflict(conflictId, keepDeviceId) {
  const res = await fetch(`/api/conflicts/${conflictId}/resolve`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ keepDeviceId }),
  });
  if (!res.ok) throw new Error(`Failed to resolve conflict: HTTP ${res.status}`);
  return res.json();
}
