import { beforeEach, describe, expect, it } from 'vitest';
import { defineComponent } from 'vue';
import { pluginRegistry } from '@/plugins/registry';
import type { PluginSlotName, PluginViewRegistration } from '@/plugins/types';

function view(
  id: string,
  slot: PluginSlotName = 'rightDock',
  order?: number,
): PluginViewRegistration {
  return {
    id,
    title: id,
    slot,
    order,
    component: async () => defineComponent({}),
  };
}

describe('pluginRegistry', () => {
  beforeEach(() => {
    pluginRegistry.clear();
  });

  it('按插槽过滤并按 order 排序', () => {
    pluginRegistry.registerView('a', view('later', 'rightDock', 20));
    pluginRegistry.registerView('b', view('earlier', 'rightDock', 10));
    pluginRegistry.registerView('c', view('bottom', 'bottomDock'));

    expect(pluginRegistry.viewsFor('rightDock').map((item) => item.id)).toEqual([
      'earlier',
      'later',
    ]);
    expect(pluginRegistry.viewsFor('bottomDock').map((item) => item.id)).toEqual(['bottom']);
  });

  it('dispose 只移除自己的视图', () => {
    const first = pluginRegistry.registerView('a', view('one'));
    pluginRegistry.registerView('b', view('two'));

    first.dispose();

    expect(pluginRegistry.viewsFor('rightDock').map((item) => item.id)).toEqual(['two']);
  });

  it('停用插件时清掉它的全部视图与命令', async () => {
    pluginRegistry.registerView('a', view('one'));
    pluginRegistry.registerCommand('a', { id: 'run', handler: () => 'ok' });
    pluginRegistry.registerView('b', view('two'));

    pluginRegistry.removePlugin('a');

    expect(pluginRegistry.viewsFor('rightDock').map((item) => item.id)).toEqual(['two']);
    await expect(pluginRegistry.execute('a:run')).rejects.toThrow('插件命令不存在');
  });

  it('拒绝重复的视图 id', () => {
    pluginRegistry.registerView('a', view('same'));
    expect(() => pluginRegistry.registerView('a', view('same'))).toThrow('插件视图 id 重复');
  });
});
