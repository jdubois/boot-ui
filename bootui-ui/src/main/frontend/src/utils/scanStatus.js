const scanStatusLabels = {
  NOT_SCANNED: 'Not scanned yet',
  SCANNED: 'Scan complete',
  PARTIAL: 'Incomplete',
  ERROR: 'Scan failed',
  DISABLED: 'Scan disabled'
}

const scanStatusBadgeClasses = {
  NOT_SCANNED: 'text-bg-secondary',
  SCANNED: 'text-bg-success',
  PARTIAL: 'text-bg-warning',
  ERROR: 'text-bg-danger',
  DISABLED: 'text-bg-secondary'
}

export function hasScanResult(status) {
  // A report can contain useful findings and diagnostics without being eligible for a score.
  return Boolean(status && status !== 'NOT_SCANNED')
}

export function isCompleteScan(status) {
  return status === 'SCANNED'
}

export function scanStatusLabel(status) {
  if (!status) return 'Unknown'
  return scanStatusLabels[status] || humanizeStatus(status)
}

export function scanStatusBadgeClass(status) {
  return scanStatusBadgeClasses[status] || 'text-bg-light border text-dark'
}

function humanizeStatus(status) {
  return status
    .toString()
    .toLowerCase()
    .split('_')
    .filter(Boolean)
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(' ')
}
