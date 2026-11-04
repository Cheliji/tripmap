import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// tripmap 前端构建配置：开发阶段代理到本地 Spring Boot 服务（后续 SSE/接口联调使用）
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
