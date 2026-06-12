// @vitest-environment node
import {describe, it, expect, beforeAll, afterAll} from 'vitest'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {collectUsedIconClasses, generateBootstrapIconsSubset} from './generate-icon-subset.mjs'

const frontendRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)))
const srcRoot = path.join(frontendRoot, 'src')

describe('collectUsedIconClasses', () => {
  it('finds bi-* classes used in the app and excludes the bare base class', () => {
    const used = collectUsedIconClasses(srcRoot)
    expect(used.size).toBeGreaterThan(50)
    expect(used.has('bi-house-door')).toBe(true)
    expect(used.has('bi')).toBe(false)
  })

  it('matches classes referenced from a scanned file', () => {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'icon-scan-'))
    try {
      fs.writeFileSync(
        path.join(tmpDir, 'Sample.vue'),
        '<template><i class="bi bi-gear"></i><i :class="ok ? \'bi-check-lg\' : \'bi-x-lg\'" /></template>'
      )
      const used = collectUsedIconClasses(tmpDir)
      expect([...used].sort()).toEqual(['bi-check-lg', 'bi-gear', 'bi-x-lg'])
    } finally {
      fs.rmSync(tmpDir, {recursive: true, force: true})
    }
  })
})

describe('generateBootstrapIconsSubset', () => {
  let outputDir
  let stats

  beforeAll(async () => {
    outputDir = fs.mkdtempSync(path.join(os.tmpdir(), 'icon-subset-'))
    stats = await generateBootstrapIconsSubset({sourceRoot: srcRoot, outputDir})
  }, 60000)

  afterAll(() => {
    if (outputDir) {
      fs.rmSync(outputDir, {recursive: true, force: true})
    }
  })

  it('resolves the icons actually used by the app', () => {
    expect(stats.iconCount).toBeGreaterThan(50)
    expect(stats.missing).toEqual([])
  })

  it('produces a font dramatically smaller than the full pack', () => {
    expect(stats.subsetFontBytes).toBeGreaterThan(0)
    // The full bootstrap-icons woff2 is ~134 KB; the subset must be a small fraction.
    expect(stats.subsetFontBytes).toBeLessThan(stats.originalFontBytes / 2)
  })

  it('writes a valid subset woff2 file', () => {
    const fontPath = path.join(outputDir, 'bootstrap-icons.woff2')
    expect(fs.existsSync(fontPath)).toBe(true)
    // woff2 files start with the "wOF2" magic number.
    expect(fs.readFileSync(fontPath).subarray(0, 4).toString('latin1')).toBe('wOF2')
  })

  it('emits a woff2-only @font-face and only the used icon rules', () => {
    const css = fs.readFileSync(path.join(outputDir, 'bootstrap-icons.css'), 'utf8')
    expect(css).toContain('format("woff2")')
    expect(css).not.toContain('.woff"')
    expect(css).toContain('.bi-house-door::before')
    // An icon the app never references must not be emitted.
    expect(css).not.toContain('.bi-alarm-fill::before')
  })
})
