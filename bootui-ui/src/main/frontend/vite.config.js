import {defineConfig} from 'vitest/config'
import vue from '@vitejs/plugin-vue'

// Build the BootUI Vue app as a static SPA that lives under /bootui/.
// All assets are emitted with a /bootui/ prefix so the bundled Spring Boot
// classpath resource at META-INF/resources/bootui/index.html can serve them.
export default defineConfig({
  base: '/bootui/',
  server: {
    proxy: {
      '/bootui/api': {
        target: 'http://localhost:8080',
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
})
