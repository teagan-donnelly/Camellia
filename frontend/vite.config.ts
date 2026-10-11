import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// The dev server proxies /api to the Spring Boot backend, so the browser never needs CORS or credentials.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    // Lets a Cloudflare quick tunnel (https://<random>.trycloudflare.com) reach the dev and preview servers.
    allowedHosts: ['.trycloudflare.com'],
    proxy: {
      '/api': { target: process.env.CAMELLIA_API ?? 'http://localhost:8080', changeOrigin: true },
    },
  },
})
