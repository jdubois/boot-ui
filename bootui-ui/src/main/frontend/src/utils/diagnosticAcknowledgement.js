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
    const detail = [error.body?.error, error.body?.reason, error.body?.message].find(isNonemptyString)
    return detail || error.message
  }
  return formatLoadError(error, context)
}
