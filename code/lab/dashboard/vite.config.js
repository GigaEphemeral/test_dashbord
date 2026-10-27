import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// `npm run dev` serves the console on the Vite port; the lab API and the
// Prometheus query proxy both live on the Nginx entry, so both prefixes are
// proxied there (override the entry for a remote stack).
const labApiTarget = process.env.VITE_LAB_API_TARGET || 'http://127.0.0.1:8080'

export default defineConfig({
  base: '/lab/',
  plugins: [vue()],
  server: {
    proxy: {
      '/lab-api': { target: labApiTarget, changeOrigin: true },
      '/prom': { target: labApiTarget, changeOrigin: true }
    }
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true
  }
})
