import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8721',
        changeOrigin: false,
      },
      '/ws': {
        target: 'ws://127.0.0.1:8721',
        ws: true,
      },
    },
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          const normalizedId = id.replaceAll('\\', '/')
          if (normalizedId.includes('/node_modules/echarts/')) {
            return 'charts'
          }
          if (normalizedId.includes('/node_modules/zrender/')) return 'zrender'
          if (
            normalizedId.includes('/node_modules/vue/')
            || normalizedId.includes('/node_modules/@vue/')
            || normalizedId.includes('/node_modules/pinia/')
            || normalizedId.includes('/node_modules/vue-router/')
          ) {
            return 'vue-vendor'
          }
        },
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: false,
    include: ['src/**/*.test.ts'],
    setupFiles: ['src/test/setup.ts'],
  },
})
