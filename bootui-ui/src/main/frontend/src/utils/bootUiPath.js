const DEFAULT_PATH = '/bootui'
const SAFE_PATH = /^\/[A-Za-z0-9._~-]+(?:\/[A-Za-z0-9._~-]+)*$/

export function normalizeBootUiPath(rawPath) {
  return normalizePath(rawPath, true)
}

export function normalizeBootUiApiPath(rawPath) {
  return normalizePath(rawPath, false)
}

function normalizePath(rawPath, rejectInternalChild) {
  if (typeof rawPath !== 'string' || !rawPath.trim()) {
    throw new Error('BootUI path must not be blank')
  }
  let path = rawPath.trim()
  if (!path.startsWith('/')) throw new Error("BootUI path must start with '/'")
  while (path.length > 1 && path.endsWith('/')) path = path.slice(0, -1)
  if (path === '/') throw new Error("BootUI path must not be '/'")
  if (
    path
      .slice(1)
      .split('/')
      .some((segment) => segment === '.' || segment === '..') ||
    path.includes('?') ||
    path.includes('#') ||
    path.includes('//') ||
    !SAFE_PATH.test(path)
  ) {
    throw new Error(`Invalid BootUI path: '${path}'`)
  }
  if (rejectInternalChild && path !== DEFAULT_PATH && path.startsWith(DEFAULT_PATH + '/')) {
    throw new Error("BootUI path must not use the reserved internal '/bootui/**' namespace")
  }
  return path
}

export function getBootUiBasePath() {
  if (typeof document === 'undefined') return DEFAULT_PATH
  const href = document.querySelector('base')?.getAttribute('href')
  if (!href) return DEFAULT_PATH
  try {
    const base = typeof window === 'undefined' ? 'http://localhost/' : window.location.href
    return withoutTrailingSlash(new URL(href, base).pathname)
  } catch {
    return DEFAULT_PATH
  }
}

export function getBootUiApiPath() {
  return configuredApiPath() ?? getBootUiBasePath() + '/api'
}

export function resolveBootUiApiUrl(input) {
  if (typeof input !== 'string' || !(input === 'api' || input.startsWith('api/') || input.startsWith('api?'))) {
    return input
  }
  const apiPath = configuredApiPath()
  return apiPath ? apiPath + input.slice(3) : input
}

function configuredApiPath() {
  if (typeof document === 'undefined') return null
  const content = document.querySelector('meta[name="bootui-api-path"]')?.getAttribute('content')
  return content ? withoutTrailingSlash(content) : null
}

function withoutTrailingSlash(path) {
  let normalized = path || '/'
  while (normalized.length > 1 && normalized.endsWith('/')) normalized = normalized.slice(0, -1)
  return normalized
}
