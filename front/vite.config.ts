import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    allowedHosts: true, // 放行 frp 公网域名
    proxy: {
      '/user': { target: 'http://localhost:8080', changeOrigin: true },
      '/todo': { target: 'http://localhost:8080', changeOrigin: true },
      '/chat': { target: 'http://localhost:8080', changeOrigin: true },
      '/calendar': { target: 'http://localhost:8080', changeOrigin: true },
      '/almanac': { target: 'http://localhost:8080', changeOrigin: true },
      '/daily-note': { target: 'http://localhost:8080', changeOrigin: true },
      '/test': { target: 'http://localhost:8080', changeOrigin: true }
    }
  }
})
