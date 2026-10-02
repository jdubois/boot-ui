import {describe, expect, it} from 'vitest'

import {
  durationLabel,
  elapsedPercent,
  frameLabel,
  isResourceProfile,
  profileRows,
  remainingSeconds,
  requestShare,
  samplerLabel
} from './resourceProfile.js'

describe('resource profile helpers', () => {
  it('recognizes a profile and names its sampler', () => {
    expect(isResourceProfile({state: 'IDLE', routes: []})).toBe(true)
    expect(isResourceProfile({error: 'denied'})).toBe(false)
    expect(samplerLabel('jdk.CPUTimeSample')).toBe('CPU-time sampler')
    expect(samplerLabel('jdk.ExecutionSample')).toBe('execution sampler')
    expect(samplerLabel(null)).toBeNull()
  })

  it('sizes each route by its share of the samples joined to requests, never showing 0 % for a sampled route', () => {
    const rows = profileRows({
      routes: [
        {route: 'GET /a', cpuSamples: 297},
        {route: 'GET /b', cpuSamples: 2},
        {route: 'GET /c', cpuSamples: 0}
      ]
    })
    expect(rows.map((row) => row.shareLabel)).toEqual(['99 %', '1 %', '0 %'])
    expect(rows.map((row) => row.top)).toEqual([true, false, false])
    expect(profileRows(null)).toEqual([])
  })

  it('states the share of samples taken during requests', () => {
    expect(requestShare({cpuSamples: 200, outsideSamples: 50})).toBe(75)
    expect(requestShare({cpuSamples: 0, outsideSamples: 0})).toBeNull()
  })

  it('counts a running session down and never past its end', () => {
    const running = {state: 'RUNNING', startedAt: 0, endsAt: 30_000}
    expect(remainingSeconds(running, 10_500)).toBe(20)
    expect(elapsedPercent(running, 15_000)).toBe(50)
    expect(remainingSeconds(running, 40_000)).toBe(0)
    expect(elapsedPercent(running, 40_000)).toBe(100)
    expect(remainingSeconds({state: 'COMPLETED', endsAt: 30_000}, 0)).toBe(0)
  })

  it('says a session length in words', () => {
    expect(durationLabel(30)).toBe('30 seconds')
    expect(durationLabel(60)).toBe('1 minute')
    expect(durationLabel(120)).toBe('2 minutes')
    expect(durationLabel(90)).toBe('90 seconds')
  })

  it('drops the package of a frame and keeps its class, method, and line', () => {
    expect(frameLabel('com.example.orders.OrderService.price:42')).toBe('OrderService.price:42')
    expect(frameLabel('com.example.OrderService.price')).toBe('OrderService.price')
    expect(frameLabel('Main.run:3')).toBe('Main.run:3')
  })
})
