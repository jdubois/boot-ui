// Build-time Bootstrap Icons subsetting.
//
// BootUI only renders ~120 of the 2,000+ glyphs that ship in the full
// `bootstrap-icons` font. Bundling the whole pack adds ~310 KB of font data and
// ~99 KB of CSS to the packaged JAR. This generator scans the front-end sources
// for the `bi-*` classes that are actually referenced, then emits:
//
//   - a subset `bootstrap-icons.woff2` containing only those glyphs, and
//   - a trimmed `bootstrap-icons.css` with only the matching `.bi-*` rules and a
//     woff2-only `@font-face` (the legacy `.woff` fallback is dropped — every
//     browser that can run this Vue 3 SPA supports woff2).
//
// The generated files live in `src/generated/` (git-ignored) and are imported by
// `main.js`, so Vite's normal CSS/asset pipeline hashes and bundles them.

import {createRequire} from 'node:module'
import fs from 'node:fs'
import path from 'node:path'

const require = createRequire(import.meta.url)

// Matches Bootstrap Icon utility classes such as `bi-house-door` or `bi-x-lg`.
const ICON_CLASS_PATTERN = /bi-[a-z0-9-]+/g

// File extensions worth scanning for icon references.
const SCANNED_EXTENSIONS = new Set(['.vue', '.js', '.mjs', '.cjs', '.ts', '.html'])

// Directories under the scan root that never contain authored icon usage.
const SKIPPED_DIRECTORIES = new Set(['generated', 'node_modules', 'dist'])

function resolvePackageDir(packageName) {
  // Some packages (e.g. bootstrap-icons) expose no main entry, while others
  // (e.g. fonteditor-core) block `./package.json` via their `exports` map. Try a
  // few resolvable probes, then climb to the directory that owns the package.
  let entry
  for (const specifier of [`${packageName}/package.json`, packageName]) {
    try {
      entry = require.resolve(specifier)
      break
    } catch {
      // Try the next probe.
    }
  }
  if (!entry) {
    throw new Error(`Unable to resolve the installed "${packageName}" package`)
  }
  let dir = path.dirname(entry)
  while (dir !== path.dirname(dir)) {
    const pkg = path.join(dir, 'package.json')
    if (fs.existsSync(pkg) && JSON.parse(fs.readFileSync(pkg, 'utf8')).name === packageName) {
      return dir
    }
    dir = path.dirname(dir)
  }
  throw new Error(`Unable to locate the installed "${packageName}" package root`)
}

function listSourceFiles(root) {
  const files = []
  const walk = (dir) => {
    for (const entry of fs.readdirSync(dir, {withFileTypes: true})) {
      if (entry.isDirectory()) {
        if (!SKIPPED_DIRECTORIES.has(entry.name)) {
          walk(path.join(dir, entry.name))
        }
      } else if (SCANNED_EXTENSIONS.has(path.extname(entry.name))) {
        files.push(path.join(dir, entry.name))
      }
    }
  }
  if (fs.existsSync(root)) {
    walk(root)
  }
  return files
}

// Collect the set of `bi-*` class names referenced anywhere in the sources.
export function collectUsedIconClasses(sourceRoot) {
  const used = new Set()
  for (const file of listSourceFiles(sourceRoot)) {
    const content = fs.readFileSync(file, 'utf8')
    const matches = content.match(ICON_CLASS_PATTERN)
    if (matches) {
      for (const match of matches) {
        used.add(match)
      }
    }
  }
  // `bi` is the shared base class, not an icon glyph.
  used.delete('bi')
  return used
}

// Extract the leading `@font-face` + base `.bi` rule block from the upstream CSS
// (everything before the first per-icon rule), then rewrite the `@font-face`
// `src` to reference only our subset woff2 file.
function buildPrelude(originalCss, woff2FileName) {
  const firstIconRule = originalCss.search(/\.bi-[a-z0-9-]+::before/)
  const prelude = firstIconRule === -1 ? originalCss : originalCss.slice(0, firstIconRule)
  return prelude.replace(
    /@font-face\s*\{[\s\S]*?\}/,
    `@font-face {\n` +
      `  font-display: block;\n` +
      `  font-family: "bootstrap-icons";\n` +
      `  src: url("./${woff2FileName}") format("woff2");\n` +
      `}`
  )
}

/**
 * Generate the subset font + CSS.
 *
 * @param {object} options
 * @param {string} options.sourceRoot  Directory scanned for `bi-*` usage (the front-end `src`).
 * @param {string} options.outputDir   Directory the generated files are written to.
 * @returns {Promise<{iconCount: number, originalFontBytes: number, subsetFontBytes: number, cssBytes: number, missing: string[]}>}
 */
export async function generateBootstrapIconsSubset({sourceRoot, outputDir}) {
  const bootstrapIconsDir = resolvePackageDir('bootstrap-icons')
  const iconMap = JSON.parse(fs.readFileSync(path.join(bootstrapIconsDir, 'font', 'bootstrap-icons.json'), 'utf8'))
  const originalCss = fs.readFileSync(path.join(bootstrapIconsDir, 'font', 'bootstrap-icons.css'), 'utf8')
  const originalFontPath = path.join(bootstrapIconsDir, 'font', 'fonts', 'bootstrap-icons.woff2')

  const usedClasses = collectUsedIconClasses(sourceRoot)
  const resolved = []
  const missing = []
  for (const className of [...usedClasses].sort()) {
    const name = className.replace(/^bi-/, '')
    const codepoint = iconMap[name]
    if (codepoint == null) {
      missing.push(className)
    } else {
      resolved.push({className, name, codepoint})
    }
  }

  // Subset the woff2 to only the resolved glyphs. fonteditor-core's woff2 codec is
  // wasm-backed and must be initialised once before use. Under Node it self-locates
  // its bundled wasm and ignores this argument; we still pass the resolved path so
  // the call also works if ever run in a browser-like context.
  const {Font, woff2} = await import('fonteditor-core')
  const wasmPath = path.join(resolvePackageDir('fonteditor-core'), 'woff2', 'woff2.wasm')
  await woff2.init(wasmPath)

  const originalFont = fs.readFileSync(originalFontPath)
  const codepoints = resolved.map((icon) => icon.codepoint)
  const font = Font.create(originalFont, {
    type: 'woff2',
    subset: codepoints,
    hinting: true
  })
  const subsetFont = Buffer.from(font.write({type: 'woff2'}))

  const woff2FileName = 'bootstrap-icons.woff2'
  const cssLines = [
    '/* Generated by scripts/generate-icon-subset.mjs — do not edit by hand. */',
    buildPrelude(originalCss, woff2FileName).trimEnd(),
    ''
  ]
  for (const {className, codepoint} of resolved) {
    cssLines.push(`.${className}::before { content: "\\${codepoint.toString(16)}"; }`)
  }
  const css = cssLines.join('\n') + '\n'

  fs.mkdirSync(outputDir, {recursive: true})
  fs.writeFileSync(path.join(outputDir, woff2FileName), subsetFont)
  fs.writeFileSync(path.join(outputDir, 'bootstrap-icons.css'), css)

  return {
    iconCount: resolved.length,
    originalFontBytes: originalFont.length,
    subsetFontBytes: subsetFont.length,
    cssBytes: Buffer.byteLength(css),
    missing
  }
}
