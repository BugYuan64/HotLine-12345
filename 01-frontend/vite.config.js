import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 12345政务热线 前端统一配置（Vite 多应用架构 Monorepo）
 * - 单一服务同时承载 市民端(/)、部门端(/staff)、管理端(/admin)，由根路由分发
 * - /api 统一代理至 Java 后端 8080（Excel 导出已改流式回写，走 /api 同一代理）
 */
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    // 局域网联调：监听 0.0.0.0，同事可通过 http://<本机IP>:5173 访问开发环境
    host: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
