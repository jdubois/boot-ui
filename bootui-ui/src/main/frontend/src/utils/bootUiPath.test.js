import {afterEach, describe, expect, it} from 'vitest'

import {
  getBootUiApiPath,
  getBootUiBasePath,
  normalizeBootUiApiPath,
  normalizeBootUiPath,
  resolveBootUiApiUrl
} from './bootUiPath.js'

afterEach(() => {
  document.head.innerHTML = ''
})

describe('BootUI runtime paths', () => {
  it('reads application-root-aware UI and API paths from injected markup', () => {
    document.head.innerHTML = `
      <base href="/host/dev-console/" />
      <meta content="/host/internal/bootui-api" name="bootui-api-path" />
    `

    expect(getBootUiBasePath()).toBe('/host/dev-console')
    expect(getBootUiApiPath()).toBe('/host/internal/bootui-api')
    expect(resolveBootUiApiUrl('api/overview?limit=5')).toBe('/host/internal/bootui-api/overview?limit=5')
  })

  it('keeps relative API URLs and the default path when runtime markup is absent', () => {
    expect(getBootUiBasePath()).toBe('/bootui')
    expect(getBootUiApiPath()).toBe('/bootui/api')
    expect(resolveBootUiApiUrl('api/overview')).toBe('api/overview')
  })

  it('normalizes safe development paths and rejects ambiguous routes', () => {
    expect(normalizeBootUiPath(' /dev-console/// ')).toBe('/dev-console')
    expect(() => normalizeBootUiPath('/')).toThrow("must not be '/'")
    expect(() => normalizeBootUiPath('/admin/**')).toThrow('Invalid')
    expect(() => normalizeBootUiPath('/bootui/custom')).toThrow('reserved internal')
    expect(() => normalizeBootUiPath('/admin/./console')).toThrow('Invalid')
    expect(normalizeBootUiPath('/release..preview')).toBe('/release..preview')
    expect(normalizeBootUiApiPath('/bootui/api/')).toBe('/bootui/api')
  })
})
