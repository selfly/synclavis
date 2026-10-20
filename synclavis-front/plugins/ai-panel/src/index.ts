import { definePlugin } from '@synclavis/plugin-api';
import { defineComponent, h } from 'vue';
import AiPanel from './AiPanel.vue';
import './styles.css';

/**
 * AI 面板插件：消费宿主的 /api/ai/* 与 /ws/ai/* 契约。
 *
 * <p>插件不直接访问 OpenCode；harness 的发现、认证与事件归一化全部由后端完成。</p>
 */
export default definePlugin({
  activate(ctx) {
    ctx.views.register({
      id: 'ai.main',
      title: 'AI',
      slot: 'rightDock',
      order: 5,
      component: async () =>
        defineComponent({
          name: 'AiPanelHost',
          setup() {
            return () => h(AiPanel, { context: ctx });
          },
        }),
    });
  },
});
