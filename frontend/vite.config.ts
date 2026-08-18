import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发期代理 /api 与 /mock 到后端 8080；构建产物由 Spring Boot 静态托管（同源）
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
      '/mock': 'http://localhost:8080',
    },
  },
})
