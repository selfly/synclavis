import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
  put: vi.fn(),
  post: vi.fn(),
  del: vi.fn(),
}));

vi.mock('@/api/http', () => ({
  http: mocks,
  ApiRequestError: class ApiRequestError extends Error {
    readonly code: string;
    constructor(code: string, message: string) {
      super(message);
      this.code = code;
    }
  },
}));

import { useTabsStore } from '@/stores/tabs';

const CACHE_KEY = 'synclavis.session.v1';

function cacheOneTab(id: string): void {
  localStorage.setItem(
    CACHE_KEY,
    JSON.stringify({
      activeId: id,
      tabs: [
        { id, path: `/tmp/${id}`, name: id, sshKeyPath: null, pullStrategy: 'merge', order: 0 },
      ],
    }),
  );
}

describe('tabs store', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    localStorage.clear();
    mocks.get.mockReset();
    mocks.put.mockReset();
    mocks.post.mockReset();
    mocks.del.mockReset();
  });

  it('后端为准：本地有缓存而后端为空时，最终无 Tab（不产生幽灵 Tab）', async () => {
    cacheOneTab('cached');
    mocks.get.mockResolvedValue({ activeId: null, tabs: [] });

    const store = useTabsStore();
    await store.hydrate();

    expect(store.tabs).toHaveLength(0);
    expect(store.activeId).toBeNull();
    expect(store.hydratedFromServer).toBe(true);
  });

  it('后端会话覆盖本地缓存（含顺序与设置）', async () => {
    cacheOneTab('cached');
    mocks.get.mockResolvedValue({
      activeId: 'b',
      tabs: [
        { id: 'b', path: '/tmp/b', name: 'b', sshKeyPath: '/k', pullStrategy: 'rebase', order: 0 },
        { id: 'c', path: '/tmp/c', name: 'c', sshKeyPath: null, pullStrategy: 'merge', order: 1 },
      ],
    });

    const store = useTabsStore();
    await store.hydrate();

    expect(store.tabs.map((tab) => tab.id)).toEqual(['b', 'c']);
    expect(store.activeId).toBe('b');
    expect(store.tabs[0].pullStrategy).toBe('rebase');
  });

  it('openRepo 追加新 Tab 并设为激活', async () => {
    mocks.get.mockResolvedValue({ activeId: null, tabs: [] });
    mocks.post.mockResolvedValue({
      id: 'new',
      path: '/tmp/new',
      name: 'new',
      sshKeyPath: null,
      pullStrategy: 'merge',
      order: 0,
    });

    const store = useTabsStore();
    await store.hydrate();
    await store.openRepo('/tmp/new');

    expect(store.tabs.map((tab) => tab.id)).toEqual(['new']);
    expect(store.activeId).toBe('new');
  });

  it('openRepo 对已存在仓库不重复追加', async () => {
    mocks.get.mockResolvedValue({
      activeId: 'a',
      tabs: [
        { id: 'a', path: '/tmp/a', name: 'a', sshKeyPath: null, pullStrategy: 'merge', order: 0 },
      ],
    });
    mocks.post.mockResolvedValue({
      id: 'a',
      path: '/tmp/a',
      name: 'a',
      sshKeyPath: null,
      pullStrategy: 'merge',
      order: 0,
    });

    const store = useTabsStore();
    await store.hydrate();
    await store.openRepo('/tmp/a');

    expect(store.tabs).toHaveLength(1);
  });

  it('closeRepo 移除 Tab 并把激活项交给第一个剩余项', async () => {
    mocks.get.mockResolvedValue({
      activeId: 'x',
      tabs: [
        { id: 'x', path: '/tmp/x', name: 'x', sshKeyPath: null, pullStrategy: 'merge', order: 0 },
        { id: 'y', path: '/tmp/y', name: 'y', sshKeyPath: null, pullStrategy: 'merge', order: 1 },
      ],
    });
    mocks.del.mockResolvedValue({ id: 'x' });

    const store = useTabsStore();
    await store.hydrate();
    await store.closeRepo('x');

    expect(store.tabs.map((tab) => tab.id)).toEqual(['y']);
    expect(store.activeId).toBe('y');
    expect(store.tabs[0].order).toBe(0);
  });

  it('closeRepo 关闭最后一个时 activeId 置空', async () => {
    mocks.get.mockResolvedValue({
      activeId: 'only',
      tabs: [
        { id: 'only', path: '/tmp/only', name: 'only', sshKeyPath: null, pullStrategy: 'merge', order: 0 },
      ],
    });
    mocks.del.mockResolvedValue({ id: 'only' });

    const store = useTabsStore();
    await store.hydrate();
    await store.closeRepo('only');

    expect(store.tabs).toHaveLength(0);
    expect(store.activeId).toBeNull();
  });

  it('upsertStatus 只更新对应仓库', async () => {    mocks.get.mockResolvedValue({
      activeId: 'a',
      tabs: [
        { id: 'a', path: '/tmp/a', name: 'a', sshKeyPath: null, pullStrategy: 'merge', order: 0 },
        { id: 'b', path: '/tmp/b', name: 'b', sshKeyPath: null, pullStrategy: 'merge', order: 1 },
      ],
    });

    const store = useTabsStore();
    await store.hydrate();
    store.upsertStatus('a', {
      branch: 'main',
      detached: false,
      ahead: 1,
      behind: 2,
      staged: [],
      unstaged: [],
      untracked: [],
      conflicts: [],
      remotes: [],
      hasUpstream: true,
      upstream: 'origin/main',
      rebaseInProgress: false,
      repositoryState: 'SAFE',
    });

    expect(store.tabById('a')?.status?.ahead).toBe(1);
    expect(store.tabById('b')?.status).toBeNull();
  });

  it('reorder 本地立即换位并写回后端完整 id 列表', async () => {
    mocks.get.mockResolvedValue({
      activeId: 'a',
      tabs: [
        { id: 'a', path: '/tmp/a', name: 'a', sshKeyPath: null, pullStrategy: 'merge', order: 0 },
        { id: 'b', path: '/tmp/b', name: 'b', sshKeyPath: null, pullStrategy: 'merge', order: 1 },
        { id: 'c', path: '/tmp/c', name: 'c', sshKeyPath: null, pullStrategy: 'merge', order: 2 },
      ],
    });
    mocks.put.mockResolvedValue({ activeId: 'a', tabs: [] });

    const store = useTabsStore();
    await store.hydrate();
    await store.reorder(2, 0);

    expect(store.tabs.map((tab) => tab.id)).toEqual(['c', 'a', 'b']);
    expect(store.tabs.map((tab) => tab.order)).toEqual([0, 1, 2]);
    expect(store.activeId).toBe('a');
    expect(mocks.put).toHaveBeenCalledWith('/api/session/order', { ids: ['c', 'a', 'b'] });
  });

  it('reorder 持久化失败时保留本地顺序', async () => {
    mocks.get.mockResolvedValue({
      activeId: 'a',
      tabs: [
        { id: 'a', path: '/tmp/a', name: 'a', sshKeyPath: null, pullStrategy: 'merge', order: 0 },
        { id: 'b', path: '/tmp/b', name: 'b', sshKeyPath: null, pullStrategy: 'merge', order: 1 },
      ],
    });
    mocks.put.mockRejectedValue(new Error('network down'));
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);

    const store = useTabsStore();
    await store.hydrate();
    await store.reorder(0, 1);

    expect(store.tabs.map((tab) => tab.id)).toEqual(['b', 'a']);
    expect(store.tabs.map((tab) => tab.order)).toEqual([0, 1]);
    expect(warn).toHaveBeenCalled();
    warn.mockRestore();
  });
});
