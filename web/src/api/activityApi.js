// REST calls for the activity feed (recent file changes, newest first).
export async function fetchRecentActivity(limit = 50) {
  const res = await fetch(`/api/sync/activity?limit=${limit}`);
  if (!res.ok) throw new Error(`Failed to fetch activity: HTTP ${res.status}`);
  return res.json();
}
