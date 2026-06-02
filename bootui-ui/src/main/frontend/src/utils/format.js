export function formatDuration(nanos) {
  if (nanos == null) return '—'
  const ms = nanos / 1_000_000
  if (ms < 1) return (nanos / 1000).toFixed(1) + ' µs'
  if (ms < 1000) return ms.toFixed(1) + ' ms'
  return (ms / 1000).toFixed(2) + ' s'
}

export function formatTime(epochNanos) {
  if (!epochNanos) return '—'
  return new Date(Math.floor(epochNanos / 1_000_000)).toLocaleTimeString()
}

export function formatNumber(n) {
  if (n == null) return '—'
  return Number(n).toLocaleString()
}

export function shortName(name) {
  if (!name) return '—'
  const i = name.lastIndexOf('.')
  return i < 0 ? name : name.substring(i + 1)
}

export function isPlainObject(value) {
  return Object.prototype.toString.call(value) === '[object Object]'
}

export function formatRelative(epochMillis, nowMillis = Date.now()) {
  if (epochMillis == null) return '—'
  const diff = nowMillis - epochMillis
  if (diff < 5000) return 'just now'
  if (diff < 60000) return Math.floor(diff / 1000) + 's ago'
  if (diff < 3600000) return Math.floor(diff / 60000) + 'm ago'
  return Math.floor(diff / 3600000) + 'h ago'
}
