// MySQL unsigned counters travel as decimal strings. Never pass them through Number:
// neighboring counters above 2^53 must still have different displays and sort positions.
export function exactInteger(value) {
  if (typeof value === 'bigint') return value
  if (typeof value === 'number') return Number.isSafeInteger(value) ? BigInt(value) : null
  if (typeof value !== 'string' || !/^-?\d+$/.test(value)) return null
  return BigInt(value)
}

export function formatCounter(value) {
  const integer = exactInteger(value)
  return integer === null ? '—' : integer.toLocaleString('en-US')
}

export function compareCounters(left, right) {
  const a = exactInteger(left)
  const b = exactInteger(right)
  if (a === b) return 0
  if (a === null) return 1
  if (b === null) return -1
  return a < b ? -1 : 1
}

export function formatDuration(value, unit = 'ms') {
  return typeof value === 'number' && Number.isFinite(value)
    ? `${value.toLocaleString('en-US', {maximumFractionDigits: 2})} ${unit}`
    : '—'
}

export function formatRatio(value) {
  return typeof value === 'number' && Number.isFinite(value) ? `${(value * 100).toFixed(1)}%` : '—'
}
