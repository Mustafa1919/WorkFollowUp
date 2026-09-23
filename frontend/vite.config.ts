import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'

// Gelistirmede API ayni origin'den (5173) proxy'lenir: refresh cookie'si (HttpOnly, Secure,
// SameSite=Strict, path=/api/v1/auth) boylece tarayici tarafindan gonderilir. Tarayicilar
// http://localhost'u "secure context" saydigi icin Secure cookie duz HTTP'de de kabul edilir.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const target = env.VITE_API_PROXY_TARGET || 'http://localhost:8080'
  return {
    plugins: [react(), tailwindcss()],
    resolve: { alias: { '@': '/src' } },
    server: {
      port: 5173,
      proxy: {
        '/api': { target, changeOrigin: false },
        '/actuator': { target, changeOrigin: false },
        // Native WebSocket (SockJS yok, bkz. backend WebSocketConfig javadoc'u): ws:true olmadan
        // proxy sadece HTTP upgrade oncesi istegi gecirir, handshake tamamlanmaz.
        '/ws': { target, changeOrigin: false, ws: true },
      },
    },
  }
})
