import { fileURLToPath, URL } from 'node:url';
import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

const root = fileURLToPath(new URL('.', import.meta.url));

/**
 * 终端插件单独构建：vue / @synclavis/plugin-api 由宿主 import map 提供；
 * xterm 及其 CSS 打进插件自己的 dist，避免占用宿主运行时。
 */
export default defineConfig({
  root,
  plugins: [vue()],
  build: {
    target: 'es2020',
    outDir: 'dist',
    emptyOutDir: true,
    cssCodeSplit: false,
    lib: {
      entry: fileURLToPath(new URL('./src/index.ts', import.meta.url)),
      formats: ['es'],
      fileName: () => 'index.js',
    },
    rollupOptions: {
      external: ['vue', 'naive-ui', '@synclavis/plugin-api'],
      output: {
        assetFileNames: (asset) =>
          asset.name && asset.name.endsWith('.css')
            ? 'style.css'
            : 'assets/[name][extname]',
      },
    },
  },
});
