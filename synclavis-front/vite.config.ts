import { fileURLToPath, URL } from 'node:url';
import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

// 前端只依赖 HTTP/WS 契约（见《详细设计文档》§6），不感知后端实现
const API_TARGET = process.env.VITE_API_TARGET ?? 'http://127.0.0.1:18080';

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    host: '127.0.0.1',
    port: 5173,
    proxy: {
      '/api': { target: API_TARGET, changeOrigin: true },
      '/ws': { target: API_TARGET, ws: true, changeOrigin: true },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
    chunkSizeWarningLimit: 700,
    rollupOptions: {
      output: {
        // 拆包，避免单个 chunk 过大
        manualChunks: {
          'vendor-vue': ['vue', 'pinia'],
          'vendor-naive': ['naive-ui'],
        },
      },
    },
  },
});
