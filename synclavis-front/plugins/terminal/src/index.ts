import { definePlugin } from '@synclavis/plugin-api';
import { defineComponent, h } from 'vue';
import TerminalView from './TerminalView.vue';
import '@xterm/xterm/css/xterm.css';
import './styles.css';

/**
 * 终端插件：消费宿主的 /api/terminal 与 /ws/terminal 契约。
 *
 * <p>插件不直接创建进程；shell/cwd 由宿主按已打开仓库决定。</p>
 */
export default definePlugin({
  activate(ctx) {
    ctx.views.register({
      id: 'terminal.main',
      title: '终端',
      slot: 'bottomDock',
      order: 10,
      component: async () =>
        defineComponent({
          name: 'TerminalHost',
          setup() {
            return () => h(TerminalView, { context: ctx });
          },
        }),
    });
  },
});
