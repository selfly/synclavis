import { createApp } from 'vue';
import { createPinia } from 'pinia';
import App from './App.vue';
import './styles/tokens.css';
import './styles/index.css';

// 首屏主题：缓存优先，避免闪烁；随后由后端设置校正（见《详细设计文档》§4.5）
const cachedTheme = localStorage.getItem('synclavis.theme');
if (cachedTheme === 'light' || cachedTheme === 'dark') {
  document.documentElement.setAttribute('data-theme', cachedTheme);
}

createApp(App).use(createPinia()).mount('#app');
