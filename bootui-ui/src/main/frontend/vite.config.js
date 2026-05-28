import {defineConfig} from 'vitest/config'
import vue from '@vitejs/plugin-vue'

// Build the BootUI Vue app as a static SPA that lives under /bootui/.
// All assets are emitted with a /bootui/ prefix so the bundled Spring Boot
// classpath resource at META-INF/resources/bootui/index.html can serve them.
export default defineConfig({
  base: '/bootui/',
  plugins: [vue()],
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    assetsDir: 'assets',
    sourcemap: false
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.js'],
    clearMocks: true,
    restoreMocks: true
  }
})
