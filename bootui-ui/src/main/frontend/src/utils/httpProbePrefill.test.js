import {describe, expect, it} from 'vitest'
import {
  buildExchangeProbeLink,
  buildMappingProbeLink,
  composeProbePath,
  extractPathPlaceholders,
  hasQueryValuePlaceholders,
  isHttpProbeWritable,
  isMappingsAvailable,
  normalizeBasePath,
  preferredAccept,
  preferredContentType,
  resolveProbeMethod
} from './httpProbePrefill.js'

describe('httpProbePrefill', () => {
  it('resolves concrete HTTP methods and defaults ANY or blank to GET', () => {
    expect(resolveProbeMethod('POST')).toBe('POST')
    expect(resolveProbeMethod('patch')).toBe('PATCH')
    expect(resolveProbeMethod('ANY')).toBe('GET')
    expect(resolveProbeMethod('')).toBe('GET')
    expect(resolveProbeMethod(null)).toBe('GET')
    expect(resolveProbeMethod('POST, PUT')).toBe('POST')
  })

  it('normalizes base paths and composes route patterns without doubling prefixes', () => {
    expect(normalizeBasePath('')).toBe('')
    expect(normalizeBasePath('/')).toBe('')
    expect(normalizeBasePath(' /host/mvc/* ')).toBe('/host/mvc')

    expect(composeProbePath('', '/api/hello')).toBe('/api/hello')
    expect(composeProbePath('/host', '/api/hello')).toBe('/host/api/hello')
    expect(composeProbePath('/host', '/')).toBe('/host')
    expect(composeProbePath('/host', '/host/api/hello')).toBe('/host/api/hello')
  })

  it('selects preferred Accept and Content-Type media types while ignoring negations and wildcards', () => {
    expect(preferredAccept('!text/plain, text/html, application/json')).toBe('application/json')
    expect(preferredAccept('*/*')).toBe('')
    expect(preferredContentType('application/json;charset=UTF-8', 'POST')).toBe('application/json')
    expect(preferredContentType('application/json', 'GET')).toBe('')
    expect(preferredContentType('text/xml', 'PUT')).toBe('text/xml')
  })

  it('extracts path placeholders including regex constraints and detects VALUE query placeholders', () => {
    expect(extractPathPlaceholders('/api/orders/{orderId}/items/{id:[0-9]+}?filter={ignored}')).toEqual([
      '{orderId}',
      '{id:[0-9]+}'
    ])
    expect(extractPathPlaceholders('/api/hello')).toEqual([])
    expect(hasQueryValuePlaceholders('/api/orders?page=VALUE&sort=VALUE')).toBe(true)
    expect(hasQueryValuePlaceholders('/api/orders?page=1')).toBe(false)
  })

  it('evaluates http-probe and mappings panel availability and writability from the manifest', () => {
    expect(isHttpProbeWritable(null)).toBe(true)
    expect(
      isHttpProbeWritable({
        panels: [{id: 'http-probe', enabled: true, available: true, readOnly: false}]
      })
    ).toBe(true)
    expect(
      isHttpProbeWritable({
        panels: [{id: 'http-probe', enabled: true, available: true, readOnly: true}]
      })
    ).toBe(false)
    expect(
      isHttpProbeWritable({
        panels: [{id: 'http-probe', enabled: false, available: true, readOnly: false}]
      })
    ).toBe(false)

    expect(isMappingsAvailable(null)).toBe(false)
    expect(
      isMappingsAvailable({
        panels: [{id: 'mappings', enabled: true, available: true}]
      })
    ).toBe(true)
    expect(
      isMappingsAvailable({
        panels: [{id: 'mappings', enabled: false, available: true}]
      })
    ).toBe(false)
  })

  it('builds a passive HTTP Probe link from a MappingDto with composed base path and media types', () => {
    expect(
      buildMappingProbeLink(
        {
          method: 'POST',
          pattern: '/api/orders/{id}',
          produces: 'application/json',
          consumes: 'application/json'
        },
        '/host'
      )
    ).toEqual({
      path: '/http-probe',
      query: {
        method: 'POST',
        path: '/host/api/orders/{id}',
        accept: 'application/json',
        contentType: 'application/json'
      }
    })
    expect(buildMappingProbeLink({method: 'GET', pattern: ''}, '/host')).toBeNull()
  })

  it('builds a passive HTTP Probe link from an HttpExchangeDto without secrets or query values', () => {
    const link = buildExchangeProbeLink({
      method: 'POST',
      path: '/host/api/orders',
      query: 'token=******&page=2',
      uri: 'http://localhost:8080/host/api/orders?token=******&page=2',
      requestHeaders: [
        {name: 'Accept', values: ['application/json'], masked: false},
        {name: 'Accept-Language', values: ['en-US'], masked: false},
        {name: 'Content-Type', values: ['application/json'], masked: false},
        {name: 'Authorization', values: ['Bearer secret'], masked: false},
        {name: 'X-Api-Key', values: ['******'], masked: true}
      ]
    })

    expect(link).toEqual({
      path: '/http-probe',
      query: {
        method: 'POST',
        path: '/host/api/orders?token=VALUE&page=VALUE',
        accept: 'application/json',
        acceptLanguage: 'en-US',
        contentType: 'application/json',
        source: 'http-exchanges'
      }
    })
  })
})
