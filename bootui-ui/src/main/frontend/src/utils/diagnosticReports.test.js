import {describe, expect, it} from 'vitest'
import {
  isCaptureReport,
  isConfigRemoveAcknowledgement,
  isHibernateStatisticsReport,
  isLoggerAcknowledgement,
  isWebSocketReport
} from './diagnosticAcknowledgement.js'

describe('native diagnostic report recognition', () => {
  it('recognizes real logger resets without imposing the requested level', () => {
    expect(
      isLoggerAcknowledgement({name: 'logger', configuredLevel: null, effectiveLevel: 'INFO', future: true}, 'logger')
    ).toBe(true)
    expect(isLoggerAcknowledgement({name: 'logger', configuredLevel: 'WARN', effectiveLevel: 'WARN'}, 'logger')).toBe(
      true
    )
    expect(isLoggerAcknowledgement({name: 'other', configuredLevel: null, effectiveLevel: 'INFO'}, 'logger')).toBe(
      false
    )
    expect(isLoggerAcknowledgement({name: 'logger', configuredLevel: 42, effectiveLevel: 'INFO'}, 'logger')).toBe(false)
  })

  it('recognizes removing an already absent override and masked prior values', () => {
    for (const previousValue of [null, '******']) {
      expect(
        isConfigRemoveAcknowledgement(
          {
            name: 'property',
            value: null,
            previousValue,
            persisted: true,
            message: 'Restart may be required.',
            future: true
          },
          'property'
        )
      ).toBe(true)
    }
    expect(
      isConfigRemoveAcknowledgement(
        {name: 'property', value: 'still-set', previousValue: null, persisted: true, message: 'Stored.'},
        'property'
      )
    ).toBe(false)
  })

  it('recognizes legitimate Hibernate unavailability and future counters', () => {
    expect(
      isHibernateStatisticsReport({
        available: false,
        enableAvailable: false,
        unavailableReason: 'No factory.',
        statistics: null,
        future: true
      })
    ).toBe(true)
    expect(
      isHibernateStatisticsReport({
        available: true,
        enableAvailable: false,
        unavailableReason: null,
        statistics: {futureCounter: 1}
      })
    ).toBe(true)
    expect(
      isHibernateStatisticsReport({available: true, enableAvailable: false, unavailableReason: null, statistics: null})
    ).toBe(false)
  })

  it('recognizes the empty unavailable capture DTO without inventing intent', () => {
    const report = {
      available: false,
      unavailableReason: 'No recorder.',
      capturing: false,
      bufferSize: 0,
      totalCaptured: 0,
      stats: {},
      entries: [],
      future: true
    }
    expect(isCaptureReport(report)).toBe(true)
    expect(isCaptureReport({...report, available: true, unavailableReason: null, capturing: true})).toBe(true)
    expect(isCaptureReport({...report, capturing: 'false'})).toBe(false)
    expect(isCaptureReport({...report, entries: {}})).toBe(false)
    expect(isCaptureReport({...report, entries: [null]})).toBe(false)
  })

  it('recognizes a metadata-only WebSocket report with unchanged capture state', () => {
    const report = {
      available: true,
      unavailableReason: null,
      capturing: false,
      frameCaptureSupported: false,
      activity: [],
      future: true
    }
    expect(isWebSocketReport(report)).toBe(true)
    expect(isWebSocketReport({...report, available: false, unavailableReason: 'Unsupported.'})).toBe(true)
    expect(isWebSocketReport({...report, activity: {}})).toBe(false)
  })

  it.each([null, {}, [], 'done'])('rejects an unrecognized reply %j', (body) => {
    expect(isCaptureReport(body)).toBe(false)
    expect(isHibernateStatisticsReport(body)).toBe(false)
    expect(isWebSocketReport(body)).toBe(false)
    expect(isLoggerAcknowledgement(body, 'logger')).toBe(false)
    expect(isConfigRemoveAcknowledgement(body, 'property')).toBe(false)
  })
})
