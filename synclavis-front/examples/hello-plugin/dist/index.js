import { definePlugin } from '@synclavis/plugin-api';
import { NButton } from 'naive-ui';
import { h, onMounted, onUnmounted, ref } from 'vue';

/**
 * 示例插件：只依赖宿主通过 import map 提供的 vue 与 @synclavis/plugin-api。
 * 不打包 Vue 自身的副本，确保与宿主共享同一个实例。
 */
function createHelloView(ctx) {
  return {
    setup() {
      const repoId = ref(ctx.services.repos.activeId());
      let timer = null;

      onMounted(() => {
        repoId.value = ctx.services.repos.activeId();
        // 示例用轮询；正式插件可以改为订阅宿主事件
        timer = setInterval(() => {
          repoId.value = ctx.services.repos.activeId();
        }, 1000);
      });

      onUnmounted(() => {
        if (timer !== null) {
          clearInterval(timer);
        }
      });

      return () =>
        h('div', { class: 'synclavis-hello' }, [
          h('p', { style: 'font-weight:600;margin:0 0 6px' }, 'Hello 插件'),
          h('p', { style: 'margin:0' }, '右侧 Dock 已由插件渲染。'),
          repoId.value
            ? h('p', { style: 'margin:6px 0 0' }, `当前仓库：${repoId.value}`)
            : h('p', { style: 'margin:6px 0 0;opacity:.7' }, '尚未打开仓库。'),
          h(
            NButton,
            {
              size: 'small',
              style: 'margin-top:10px',
              onClick: () => ctx.services.ui.notify('来自插件视图的通知', 'info'),
            },
            { default: () => '通知宿主' },
          ),
        ]);
    },
  };
}

export default definePlugin({
  activate(ctx) {
    ctx.views.register({
      id: 'hello.main',
      title: 'Hello',
      slot: 'rightDock',
      order: 10,
      component: async () => createHelloView(ctx),
    });
    ctx.services.ui.notify('Hello 插件已激活', 'success');
  },
});
