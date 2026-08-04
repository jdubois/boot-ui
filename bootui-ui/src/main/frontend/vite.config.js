import {fileURLToPath} from 'node:url'
import path from 'node:path'
import {defineConfig} from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import checker from 'vite-plugin-checker'
import {generateBootstrapIconsSubset} from './scripts/generate-icon-subset.mjs'
import {normalizeBootUiApiPath, normalizeBootUiPath} from './src/utils/bootUiPath.js'

const frontendRoot = path.dirname(fileURLToPath(import.meta.url))

// Vite plugin that subsets the Bootstrap Icons font + CSS to only the glyphs the
// app actually references, writing the result into `src/generated/` (git-ignored).
// Runs in `buildStart`, which fires for production builds, the dev server, and
// Vitest, so `main.js`'s `./generated/bootstrap-icons.css` import always resolves.
function bootstrapIconsSubsetPlugin() {
  return {
    name: 'bootui-bootstrap-icons-subset',
    async buildStart() {
      const stats = await generateBootstrapIconsSubset({
        sourceRoot: path.join(frontendRoot, 'src'),
        outputDir: path.join(frontendRoot, 'src', 'generated')
      })
      if (stats.missing.length > 0) {
        this.warn(`Ignoring unknown Bootstrap Icon classes: ${stats.missing.join(', ')}`)
      }
    }
  }
}

// Build the BootUI Vue app as a static SPA that can live under any configured BootUI path.
//
// The production build uses a relative base ('./') so the generated index.html
// references its assets relatively (e.g. ./assets/index-*.js). Because the SPA
// is served from a trailing-slash shell URL, those relative URLs resolve correctly
// under the configured BootUI path and the host application's root. An absolute
// build base would ignore those runtime paths and 404. The dev server uses the
// configured dev base and API path so its proxy matches the runtime metadata.
//
// Override the dev-server base and proxy path with BOOTUI_DEV_PATH (e.g.
// BOOTUI_DEV_PATH=/my-console) when developing against an app that uses a
// non-default bootui.path. The path must start with '/' and must not be '/'.
const devBasePath = normalizeBootUiPath(process.env.BOOTUI_DEV_PATH || '/bootui')
const devApiPath = normalizeBootUiApiPath(process.env.BOOTUI_DEV_API_PATH || devBasePath + '/api')

function devRuntimePathPlugin() {
  return {
    name: 'bootui-dev-runtime-path',
    apply: (_, {command, mode}) => command === 'serve' && mode !== 'test',
    transformIndexHtml: {
      order: 'pre',
      handler(html, context) {
        if (!context.server) return html
        return html.replace(/<head([^>]*)>/i, `<head$1>\n    <meta content="${devApiPath}" name="bootui-api-path" />`)
      }
    }
  }
}

export default defineConfig(({command}) => ({
  base: command === 'build' ? './' : devBasePath + '/',
  server: {
    proxy: {
      [devApiPath]: {
        // Defaults to :8080; override with BOOTUI_API_PROXY_TARGET so the dev
        // server can point at a sample app bound to a dynamic port.
        target: process.env.BOOTUI_API_PROXY_TARGET || 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  plugins: [devRuntimePathPlugin(), vue(), bootstrapIconsSubsetPlugin(), checker({vueTsc: true})],
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    assetsDir: 'assets',
    sourcemap: false
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.js', 'scripts/**/*.test.js'],
    clearMocks: true,
    restoreMocks: true,
    reporters: process.env.CI ? ['default', 'junit'] : 'default',
    outputFile: {junit: './test-results/vitest-junit.xml'}
  }
}))
