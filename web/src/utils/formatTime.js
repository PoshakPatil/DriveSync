// Small, dependency-free "time ago" formatter - avoids pulling in a date
// library for what's just a few branches.
export function timeAgo(isoString) {
  if (!isoString) return 'never';
  const seconds = Math.floor((Date.now() - new Date(isoString).getTime()) / 1000);
  if (seconds < 5) return 'just now';
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  return `${days}d ago`;
}

export function shortHash(hash) {
  return hash ? hash.slice(0, 10) : '—';
}
