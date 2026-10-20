import { fileURLToPath, URL } from 'node:url';
import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

const root = fileURLToPath(new URL('.', import.meta.url));

/**
 * 第一方插件单独构建：宿主只提供 vue/naive-ui/@synclavis/plugin-api 三个共享模块，
 * 其余代码打进 dist/index.js，避免与宿主产生第二个 Vue 实例。
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
