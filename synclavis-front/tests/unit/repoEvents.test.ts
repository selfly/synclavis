import { describe, expect, it, vi } from 'vitest';
import { RepoEventHub } from '@/plugins/repoEvents';

interface FakeTabs {
  activeId: string | null;
  tabs: Array<{ id: string }>;
  $subscribe: (callback: () => void) => () => void;
}

function createSource() {
  const callbacks: Array<() => void> = [];
  const source: FakeTabs = {
    activeId: 'a',
    tabs: [{ id: 'a' }, { id: 'b' }],
    $subscribe: (callback) => {
      callbacks.push(callback);
      return () => undefined;
    },
  };
  const hub = new RepoEventHub(() => source);
  function emit(): void {
    callbacks.forEach((callback) => callback());
  }
  return { source, hub, emit, subscribeCount: () => callbacks.length };
}

describe('RepoEventHub', () => {
  it('openIds 返回当前 Tab 顺序', () => {
    const { hub } = createSource();
    expect(hub.openIds()).toEqual(['a', 'b']);
  });

  it('打开/关闭仓库回调完整 id 列表，且只订阅一次', () => {
    const { source, hub, emit, subscribeCount } = createSource();
    const open = vi.fn();
    const active = vi.fn();
    hub.onDidChangeOpen(open);
    hub.onDidChangeActive(active);
    expect(subscribeCount()).toBe(1);

    source.tabs = [{ id: 'a' }, { id: 'b' }, { id: 'c' }];
    source.activeId = 'b';
    emit();

    expect(open).toHaveBeenCalledWith(['a', 'b', 'c']);
    expect(active).toHaveBeenCalledWith('b');

    source.tabs = [{ id: 'b' }, { id: 'c' }];
    emit();

    expect(open).toHaveBeenLastCalledWith(['b', 'c']);
    expect(active).toHaveBeenCalledTimes(1);
  });

  it('dispose 后不再收到通知', () => {
    const { source, hub, emit } = createSource();
    const open = vi.fn();
    const subscription = hub.onDidChangeOpen(open);
    subscription.dispose();

    source.tabs = [{ id: 'a' }];
    emit();

    expect(open).not.toHaveBeenCalled();
  });

  it('监听器异常不影响其他监听器', () => {
    const { source, hub, emit } = createSource();
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const second = vi.fn();
    hub.onDidChangeOpen(() => {
      throw new Error('boom');
    });
    hub.onDidChangeOpen(second);

    source.tabs = [{ id: 'a' }];
    emit();

    expect(second).toHaveBeenCalledWith(['a']);
    expect(warn).toHaveBeenCalled();
    warn.mockRestore();
  });
});
