const SERVER_UNREACHABLE_TITLE = 'Server unreachable'
const SERVER_UNREACHABLE_MESSAGE =
  'BootUI could not reach the Spring Boot app. The server may have been stopped. Start it again, then retry or refresh this page.'

const NETWORK_ERROR_MARKERS = [
  'failed to fetch',
  'fetch failed',
  'load failed',
  'network request failed',
  'networkerror when attempting to fetch resource',
  'the network connection was lost'
]

export function toErrorMessage(error, fallback = 'Request failed') {
  if (error instanceof Error && error.message) return error.message
  if (typeof error === 'string' && error.trim()) return error
  if (error && typeof error === 'object' && typeof error.message === 'string' && error.message.trim()) {
    return error.message
  }
  return fallback
}

export function isServerUnreachableError(error) {
  const normalized = toErrorMessage(error, '').trim().toLowerCase()
  let end = normalized.length
  while (end > 0 && (normalized[end - 1] === '.' || normalized[end - 1] === '!')) {
    end--
  }
  const message = normalized.slice(0, end)
  if (!message) return false
  if (message.startsWith(`${SERVER_UNREACHABLE_TITLE.toLowerCase()}:`)) return true

  return NETWORK_ERROR_MARKERS.some(
    (marker) => message === marker || message.endsWith(`: ${marker}`) || message.includes(` ${marker}`)
  )
}

export function describeLoadError(error, context = null) {
  const message = toErrorMessage(error)
  const serverUnreachable = isServerUnreachableError(message)

  if (serverUnreachable) {
    return {
      title: SERVER_UNREACHABLE_TITLE,
      message: SERVER_UNREACHABLE_MESSAGE,
      serverUnreachable: true
    }
  }

  return {
    title: 'Load failed',
    message: context ? `${context}: ${message}` : message,
    serverUnreachable: false
  }
}

export function formatLoadError(error, context = null) {
  const description = describeLoadError(error, context)
  return description.serverUnreachable ? `${description.title}: ${description.message}` : description.message
}
