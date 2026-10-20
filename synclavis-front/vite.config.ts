import { fileURLToPath, URL } from 'node:url';
import { defineConfig, type Plugin } from 'vite';
import vue from '@vitejs/plugin-vue';

// 前端只依赖 HTTP/WS 契约（见《详细设计文档》§6），不感知后端实现
const API_TARGET = process.env.VITE_API_TARGET ?? 'http://127.0.0.1:18080';

/**
 * 开发期把 import map 的稳定 URL 指到源码。
 *
 * <p>生产构建会把同一批入口输出成 `plugin-runtime/*.js`；开发期没有构建产物，
 * 因此用一个极小的中间件把 `/plugin-runtime/x.js` 改写到 `/src/plugin-runtime/x.ts`。</p>
 */
function pluginRuntimeDev(): Plugin {
  const map: Record<string, string> = {
    '/plugin-runtime/vue.js': '/src/plugin-runtime/vue.ts',
    '/plugin-runtime/naive-ui.js': '/src/plugin-runtime/naive-ui.ts',
    '/plugin-runtime/plugin-api.js': '/src/plugin-runtime/plugin-api.ts',
  };
  return {
    name: 'synclavis-plugin-runtime-dev',
    apply: 'serve',
    configureServer(server) {
      server.middlewares.use((req, _res, next) => {
        const path = req.url?.split('?')[0] ?? '';
        if (map[path]) {
          req.url = map[path];
        }
        next();
      });
    },
  };
}

export default defineConfig({
  plugins: [vue(), pluginRuntimeDev()],
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
      '/plugin-assets': { target: API_TARGET, changeOrigin: true },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
    chunkSizeWarningLimit: 700,
    rollupOptions: {
      // 插件运行时入口必须保留全部导出；Vite 的 app 构建默认允许 tree-shake 入口签名
      preserveEntrySignatures: 'strict',
      input: {
        main: fileURLToPath(new URL('./index.html', import.meta.url)),
        'plugin-runtime/vue': fileURLToPath(new URL('./src/plugin-runtime/vue.ts', import.meta.url)),
        'plugin-runtime/naive-ui': fileURLToPath(
          new URL('./src/plugin-runtime/naive-ui.ts', import.meta.url),
        ),
        'plugin-runtime/plugin-api': fileURLToPath(
          new URL('./src/plugin-runtime/plugin-api.ts', import.meta.url),
        ),
      },
      output: {
        // 插件运行时 URL 必须稳定，不能被 hash 改名；其余资源照常带 hash
        entryFileNames: (chunk) =>
          chunk.name.startsWith('plugin-runtime/') ? '[name].js' : 'assets/[name]-[hash].js',
        chunkFileNames: 'assets/[name]-[hash].js',
        assetFileNames: 'assets/[name]-[hash][extname]',
        // 拆包，避免单个 chunk 过大
        manualChunks: {
          'vendor-vue': ['vue', 'pinia'],
          'vendor-naive': ['naive-ui'],
        },
      },
    },
  },
});
