import {describe, expect, it} from 'vitest'
import {hasScanResult, isCompleteScan, scanStatusBadgeClass, scanStatusLabel} from './scanStatus.js'

describe('scan status', () => {
  it.each(['SCANNED', 'PARTIAL', 'ERROR', 'DISABLED'])('retains %s reports independently of scoring', (status) => {
    expect(hasScanResult(status)).toBe(true)
    expect(isCompleteScan(status)).toBe(status === 'SCANNED')
  })

  it.each([null, undefined, '', 'NOT_SCANNED'])('does not invent scan results for %s', (status) => {
    expect(hasScanResult(status)).toBe(false)
    expect(isCompleteScan(status)).toBe(false)
  })

  it('labels partial results as incomplete while preserving failed and disabled status', () => {
    expect(scanStatusLabel('PARTIAL')).toBe('Incomplete')
    expect(scanStatusBadgeClass('PARTIAL')).toBe('text-bg-warning')
    expect(scanStatusLabel('ERROR')).toBe('Scan failed')
    expect(scanStatusLabel('DISABLED')).toBe('Scan disabled')
  })
})
