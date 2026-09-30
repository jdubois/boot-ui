import {afterEach, beforeEach, describe, expect, it} from 'vitest'
import {
  OPEN_IN_PRESETS,
  OPEN_IN_STORAGE_KEY,
  formatLocation,
  openInHref,
  presetLabel,
  resetOpenInPreference,
  useOpenInPreference
} from './sourceLocation.js'

const location = {
  className: 'com.example.OrderService',
  memberName: 'place',
  kind: 'METHOD',
  sourceFile: 'OrderService.java',
  line: 42,
  sourcePath: '/work/shop/src/main/java/com/example/OrderService.java',
  precision: 'LINE'
}

beforeEach(() => {
  window.localStorage.clear()
  resetOpenInPreference()
})
afterEach(() => {
  window.localStorage.clear()
  resetOpenInPreference()
})

describe('formatLocation', () => {
  it('formats class, member, source file and line like a stack frame', () => {
    expect(formatLocation(location)).toBe('com.example.OrderService#place (OrderService.java:42)')
  })

  it('omits what the location does not carry', () => {
    expect(formatLocation({...location, line: null})).toBe('com.example.OrderService#place (OrderService.java)')
    expect(formatLocation({...location, memberName: null, line: null})).toBe(
      'com.example.OrderService (OrderService.java)'
    )
    expect(formatLocation({className: 'com.example.Order', line: 7})).toBe('com.example.Order (line 7)')
    expect(formatLocation({...location, line: 0})).toBe('com.example.OrderService#place (OrderService.java)')
  })

  it('returns nothing for a missing or classless location', () => {
    expect(formatLocation(null)).toBe('')
    expect(formatLocation({memberName: 'place'})).toBe('')
  })
})

describe('openInHref', () => {
  it('builds links only from the fixed VS Code and IntelliJ IDEA schemes', () => {
    expect(openInHref(location, 'vscode')).toBe(
      'vscode://file/work/shop/src/main/java/com/example/OrderService.java:42'
    )
    expect(openInHref(location, 'idea')).toBe(
      'idea://open?file=%2Fwork%2Fshop%2Fsrc%2Fmain%2Fjava%2Fcom%2Fexample%2FOrderService.java&line=42'
    )
  })

  it('produces no link while the preference is off or unknown', () => {
    expect(openInHref(location, 'none')).toBeNull()
    expect(openInHref(location, 'https://example.com/{path}')).toBeNull()
    expect(openInHref(location, 'custom')).toBeNull()
    expect(openInHref(location, undefined)).toBeNull()
  })

  it('never links a location without an absolute local path', () => {
    expect(openInHref({...location, sourcePath: null}, 'vscode')).toBeNull()
    expect(openInHref({...location, sourcePath: 'src/main/java/Order.java'}, 'vscode')).toBeNull()
    expect(openInHref({...location, sourcePath: '//server/share/Order.java'}, 'idea')).toBeNull()
    expect(openInHref({...location, sourcePath: 'https://example.com/Order.java'}, 'vscode')).toBeNull()
    expect(openInHref({...location, sourcePath: '/work/Order.java\nevil'}, 'vscode')).toBeNull()
    expect(openInHref({...location, sourcePath: `/${'a'.repeat(1100)}`}, 'vscode')).toBeNull()
  })

  it('encodes path characters so a path can never change the scheme, host, or query', () => {
    const tricky = {...location, line: null, sourcePath: '/work/a?b#c/x&line=9/Order Service.java'}
    expect(openInHref(tricky, 'vscode')).toBe('vscode://file/work/a%3Fb%23c/x%26line%3D9/Order%20Service.java')
    expect(openInHref(tricky, 'idea')).toBe(
      'idea://open?file=%2Fwork%2Fa%3Fb%23c%2Fx%26line%3D9%2FOrder%20Service.java'
    )
  })

  it('keeps a Windows drive and omits an unknown line', () => {
    const windows = {...location, line: undefined, sourcePath: 'C:\\work\\shop\\Order.java'}
    expect(openInHref(windows, 'vscode')).toBe('vscode://file/C:/work/shop/Order.java')
    expect(openInHref(windows, 'idea')).toBe('idea://open?file=C%3A%5Cwork%5Cshop%5COrder.java')
  })
})

describe('useOpenInPreference', () => {
  it('is off by default and offers only the fixed presets', () => {
    const {openIn} = useOpenInPreference()
    expect(openIn.value).toBe('none')
    expect(OPEN_IN_PRESETS.map((preset) => preset.id)).toEqual(['none', 'vscode', 'idea'])
    expect(presetLabel('idea')).toBe('IntelliJ IDEA')
  })

  it('persists a preset per browser and clears it when turned off', () => {
    const {openIn, setOpenIn} = useOpenInPreference()
    setOpenIn('vscode')
    expect(openIn.value).toBe('vscode')
    expect(window.localStorage.getItem(OPEN_IN_STORAGE_KEY)).toBe('vscode')
    resetOpenInPreference()
    expect(useOpenInPreference().openIn.value).toBe('vscode')
    setOpenIn('none')
    expect(window.localStorage.getItem(OPEN_IN_STORAGE_KEY)).toBeNull()
  })

  it('rejects a stored or requested value that is not a preset', () => {
    window.localStorage.setItem(OPEN_IN_STORAGE_KEY, 'https://example.com/{path}')
    const {openIn, setOpenIn} = useOpenInPreference()
    expect(openIn.value).toBe('none')
    expect(window.localStorage.getItem(OPEN_IN_STORAGE_KEY)).toBeNull()
    setOpenIn('javascript:alert(1)')
    expect(openIn.value).toBe('none')
    expect(window.localStorage.getItem(OPEN_IN_STORAGE_KEY)).toBeNull()
  })
})
