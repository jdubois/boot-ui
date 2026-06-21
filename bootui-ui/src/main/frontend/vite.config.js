import {fileURLToPath} from 'node:url'
import path from 'node:path'
import {defineConfig} from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import checker from 'vite-plugin-checker'
import {generateBootstrapIconsSubset} from './scripts/generate-icon-subset.mjs'

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

// Build the BootUI Vue app as a static SPA that lives under /bootui/.
//
// The production build uses a relative base ('./') so the generated index.html
// references its assets relatively (e.g. ./assets/index-*.js). Because the SPA
// is always served from a URL ending in '/bootui/', those relative URLs resolve
// correctly even when the host app sets a server.servlet.context-path (e.g.
// /api/bootui/assets/...). An absolute '/bootui/' base would ignore the context
// path and 404. The dev server keeps the '/bootui/' base so its /bootui/api
// proxy below continues to match the SPA's relative API calls.
export default defineConfig(({command}) => ({
  base: command === 'build' ? './' : '/bootui/',
  server: {
    proxy: {
      '/bootui/api': {
        // Defaults to :8080; override with BOOTUI_API_PROXY_TARGET so the dev
        // server can point at a sample app bound to a dynamic port.
        target: process.env.BOOTUI_API_PROXY_TARGET || 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  plugins: [vue(), bootstrapIconsSubsetPlugin(), checker({vueTsc: true})],
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
