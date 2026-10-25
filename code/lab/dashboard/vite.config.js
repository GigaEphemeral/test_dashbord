import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  base: '/lab/',
  plugins: [vue()],
  build: {
    outDir: 'dist',
    emptyOutDir: true
  }
})
