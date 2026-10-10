import {ApiError, getJson} from '../api.js'
import {formatLoadError} from './loadError.js'

const UNKNOWN_OUTCOME =
  'The action outcome is unknown: no valid acknowledgement was received. Re-reading the current state; the action was not retried.'

function isRecord(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function isNonemptyString(value) {
  return typeof value === 'string' && value.trim().length > 0
}

function isNullableString(value) {
  return value === null || typeof value === 'string'
}

export function isLoggerAcknowledgement(value, name) {
  return (
    isRecord(value) &&
    value.name === name &&
    isNullableString(value.configuredLevel) &&
    isNonemptyString(value.effectiveLevel)
  )
}

export function isConfigRemoveAcknowledgement(value, name) {
  return (
    isRecord(value) &&
    value.name === name &&
    value.value === null &&
    isNullableString(value.previousValue) &&
    typeof value.persisted === 'boolean' &&
    isNonemptyString(value.message)
  )
}

export function isHibernateStatisticsReport(value) {
  return (
    isRecord(value) &&
    typeof value.available === 'boolean' &&
    typeof value.enableAvailable === 'boolean' &&
    isNullableString(value.unavailableReason) &&
    (isRecord(value.statistics) || (!value.available && value.statistics === null))
  )
}

// The shared capture controls return reports, including unavailable and unchanged outcomes.
export function isCaptureReport(value) {
  return (
    isRecord(value) &&
    typeof value.available === 'boolean' &&
    isNullableString(value.unavailableReason) &&
    typeof value.capturing === 'boolean' &&
    Number.isInteger(value.bufferSize) &&
    value.bufferSize >= 0 &&
    typeof value.totalCaptured === 'number' &&
    Number.isFinite(value.totalCaptured) &&
    value.totalCaptured >= 0 &&
    isRecord(value.stats) &&
    Array.isArray(value.entries) &&
    value.entries.every(isRecord)
  )
}

export function isWebSocketReport(value) {
  return (
    isRecord(value) &&
    typeof value.available === 'boolean' &&
    isNullableString(value.unavailableReason) &&
    typeof value.capturing === 'boolean' &&
    typeof value.frameCaptureSupported === 'boolean' &&
    Array.isArray(value.activity) &&
    value.activity.every(isRecord)
  )
}

export function isJournalClearAcknowledgement(value) {
  return (
    isRecord(value) &&
    value.status === 'cleared' &&
    isNonemptyString(value.message) &&
    Number.isInteger(value.clearedEvents) &&
    value.clearedEvents >= 0
  )
}

export function isDatasourceSwitchAcknowledgement(value) {
  return (
    isRecord(value) &&
    ['success', 'already-active'].includes(value.status) &&
    isNonemptyString(value.message) &&
    isNonemptyString(value.tableName)
  )
}

export function isMcpServerStatus(value) {
  return (
    isRecord(value) &&
    typeof value.enabled === 'boolean' &&
    typeof value.overridden === 'boolean' &&
    ['ON', 'OFF', 'AUTO'].includes(value.configuredMode) &&
    ['serverName', 'serverVersion', 'transport', 'endpoint', 'protocolVersion'].every((key) =>
      isNonemptyString(value[key])
    ) &&
    Number.isSafeInteger(value.maxResults) &&
    value.maxResults > 0 &&
    Number.isSafeInteger(value.toolCount) &&
    value.toolCount >= 0 &&
    Array.isArray(value.tools) &&
    value.tools.every((tool) => isRecord(tool) && isNonemptyString(tool.name) && typeof tool.action === 'boolean')
  )
}

export function isHttpSessionAcknowledgement(value, action, sessionKey) {
  return (
    isRecord(value) &&
    value.status === (action === 'clear' ? 'cleared' : 'destroyed') &&
    value.sessionKey === sessionKey &&
    isNonemptyString(value.message) &&
    Number.isSafeInteger(value.affectedAttributes) &&
    value.affectedAttributes >= 0
  )
}

export function isDevToolsAcknowledgement(value, action) {
  return (
    isRecord(value) &&
    value.action === action &&
    (action === 'restart' ? value.status === 'scheduled' : ['triggered', 'no_clients'].includes(value.status)) &&
    isNonemptyString(value.message)
  )
}

export function isDevToolsStatus(value) {
  return (
    isRecord(value) &&
    ['restartAvailable', 'restartPending', 'liveReloadAvailable'].every((key) => typeof value[key] === 'boolean') &&
    Number.isSafeInteger(value.liveReloadConnections) &&
    value.liveReloadConnections >= 0
  )
}

export function isCacheClearAcknowledgement(value) {
  return (
    isRecord(value) &&
    value.status === 'cleared' &&
    isNonemptyString(value.message) &&
    Number.isSafeInteger(value.clearedCaches) &&
    value.clearedCaches >= 0 &&
    Array.isArray(value.caches) &&
    value.caches.every(isNonemptyString)
  )
}

export function isDevServiceRestartAcknowledgement(value, id) {
  return isRecord(value) && value.id === id && value.status === 'restarted' && isNonemptyString(value.message)
}

// Recognize the report, not a particular installation outcome. Nullable diagnostics and future fields remain valid.
export function isJavaAgentReport(value) {
  return (
    isRecord(value) &&
    isNonemptyString(value.state) &&
    isNonemptyString(value.bootUiVersion) &&
    isNonemptyString(value.jdk) &&
    Number.isSafeInteger(value.expectedProtocol) &&
    value.expectedProtocol >= 0 &&
    ['sensors', 'toggles', 'messages', 'warnings'].every((key) => Array.isArray(value[key])) &&
    value.toggles.every(
      (toggle) =>
        isRecord(toggle) &&
        isNonemptyString(toggle.id) &&
        typeof toggle.enabled === 'boolean' &&
        typeof toggle.configured === 'boolean' &&
        typeof toggle.overridden === 'boolean' &&
        typeof toggle.available === 'boolean' &&
        isNonemptyString(toggle.state)
    )
  )
}

export async function getDiagnosticAcknowledgement(input, init, accepts) {
  let body
  try {
    body = await getJson(input, init)
  } catch (error) {
    if (error instanceof ApiError) throw error
    throw new Error(UNKNOWN_OUTCOME, {cause: error})
  }
  if (!accepts(body)) throw new Error(UNKNOWN_OUTCOME)
  return body
}

export function diagnosticActionError(error, context) {
  if (error instanceof ApiError) {
    const detail = [error.body?.reason, error.body?.message, error.body?.error].find(isNonemptyString)
    return detail || error.message
  }
  return formatLoadError(error, context)
}
