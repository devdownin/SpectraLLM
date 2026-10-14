import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  // VITE_BASE_PATH is injected by the GitHub Actions workflow (/SpectraLLM/).
  // Falls back to '/' for local development.
  base: process.env.VITE_BASE_PATH ?? '/',
  // recharts v3 has CJS/ESM interop issues with Vite's dev server;
  // pre-bundling it resolves require_isUnsafeProperty crashes.
  optimizeDeps: {
    include: ['recharts'],
  },
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
