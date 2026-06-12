import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      '/us-weekly': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      '/weekly': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      '/api/ipo': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      '/api/nse': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      '/delta': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
