import {defineConfig} from 'vitest/config'
import vue from '@vitejs/plugin-vue'

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
  plugins: [vue()],
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    assetsDir: 'assets',
    sourcemap: false
  },
  esbuild: {
    drop: process.env.NODE_ENV === 'production' ? ['console', 'debugger'] : []
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.js'],
    clearMocks: true,
    restoreMocks: true,
    reporters: process.env.CI ? ['default', 'junit'] : 'default',
    outputFile: {junit: './test-results/vitest-junit.xml'}
  }
}))
