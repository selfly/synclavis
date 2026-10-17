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

import { useSettingsStore } from '@/stores/settings';
import {
  DEFAULT_LAYOUT,
  LAYOUT_BOUNDS,
  clampLayout,
  readLayoutCache,
  resolveDragSize,
  resolveLayout,
  resolveResizeDirection,
} from '@/utils/layout';

const CACHE_KEY = 'synclavis.layout';

function fullLayout(overrides: Record<string, number> = {}) {
  return { ...DEFAULT_LAYOUT, ...overrides };
}

describe('layout 工具（F-16 / F-17）', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('clampLayout 把每个字段各自钳到上下界', () => {
    expect(
      clampLayout({
        commitPanelHeight: 99999,
        logPanelHeight: 1,
        sidebarWidth: -20,
        commitListWidth: 99999,
      }),
    ).toEqual({
      commitPanelHeight: LAYOUT_BOUNDS.commitPanelHeight.max,
      logPanelHeight: LAYOUT_BOUNDS.logPanelHeight.min,
      sidebarWidth: LAYOUT_BOUNDS.sidebarWidth.min,
      commitListWidth: LAYOUT_BOUNDS.commitListWidth.max,
    });
  });

  it('clampLayout 丢弃非数字/未传字段（增量 patch 语义）', () => {
    expect(clampLayout({ commitPanelHeight: 260 })).toEqual({ commitPanelHeight: 260 });
    expect(clampLayout({} as never)).toEqual({});
    expect(clampLayout({ logPanelHeight: Number.NaN })).toEqual({
      logPanelHeight: LAYOUT_BOUNDS.logPanelHeight.min,
    });
  });

  it('resolveLayout 用默认值补全缺失字段并钳制', () => {
    expect(resolveLayout(undefined)).toEqual(DEFAULT_LAYOUT);
    expect(resolveLayout(null)).toEqual(DEFAULT_LAYOUT);
    expect(resolveLayout({ sidebarWidth: 999 })).toEqual({ ...DEFAULT_LAYOUT, sidebarWidth: 420 });
    // 提交列表列宽：默认 380，超出上界钳到 720
    expect(DEFAULT_LAYOUT.commitListWidth).toBe(380);
    expect(resolveLayout({ commitListWidth: 9999 })).toEqual({
      ...DEFAULT_LAYOUT,
      commitListWidth: LAYOUT_BOUNDS.commitListWidth.max,
    });
  });

  it('resolveDragSize：invert 决定拖拽方向', () => {
    // 提交面板：向上拖（delta 为负）应变大
    expect(resolveDragSize({ base: 190, delta: -60, invert: true, min: 120, max: 600 })).toBe(250);
    expect(resolveDragSize({ base: 190, delta: 60, invert: true, min: 120, max: 600 })).toBe(130);
    // 左侧栏：向右拖（delta 为正）变大（最小宽度 120）
    expect(resolveDragSize({ base: 240, delta: 60, invert: false, min: 120, max: 420 })).toBe(300);
    expect(resolveDragSize({ base: 240, delta: -60, invert: false, min: 120, max: 420 })).toBe(180);
    expect(resolveDragSize({ base: 240, delta: -999, invert: false, min: 120, max: 420 })).toBe(120);
  });

  it('resolveDragSize 在上下界内钳制', () => {
    expect(resolveDragSize({ base: 190, delta: -10000, invert: true, min: 120, max: 600 })).toBe(600);
    expect(resolveDragSize({ base: 190, delta: 10000, invert: true, min: 120, max: 600 })).toBe(120);
  });

  it('resolveResizeDirection：键盘按键映射为“变大/变小”，不受 invert 位移翻转影响', () => {
    // 提交面板 / 日志面板：分隔条在下方，invert=true → 上键变大
    expect(resolveResizeDirection({ axis: 'y', invert: true, key: 'ArrowUp' })).toBe(1);
    expect(resolveResizeDirection({ axis: 'y', invert: true, key: 'ArrowDown' })).toBe(-1);
    // 左侧栏：分隔条在右侧，invert=false → 右键变大
    expect(resolveResizeDirection({ axis: 'x', invert: false, key: 'ArrowRight' })).toBe(1);
    expect(resolveResizeDirection({ axis: 'x', invert: false, key: 'ArrowLeft' })).toBe(-1);
    // 其余按键不处理
    expect(resolveResizeDirection({ axis: 'y', invert: true, key: 'ArrowLeft' })).toBe(0);
    expect(resolveResizeDirection({ axis: 'y', invert: true, key: 'Enter' })).toBe(0);
  });

  it('键盘步进：grow 键恒定使尺寸 +8 并钳制（回归：曾因复用 invert 而反向变小）', () => {
    const stepOnce = (size: number, key: string, min: number, max: number) => {
      const direction = resolveResizeDirection({ axis: 'y', invert: true, key });
      return Math.max(min, Math.min(max, size + direction * 8));
    };
    expect(stepOnce(270, 'ArrowUp', 120, 600)).toBe(278);
    expect(stepOnce(270, 'ArrowDown', 120, 600)).toBe(262);
    expect(stepOnce(600, 'ArrowUp', 120, 600)).toBe(600);
    expect(stepOnce(120, 'ArrowDown', 120, 600)).toBe(120);
  });

  it('缓存损坏时回退默认值，不抛异常', () => {
    localStorage.setItem(CACHE_KEY, '{not json');
    expect(readLayoutCache()).toEqual(DEFAULT_LAYOUT);
    localStorage.setItem(CACHE_KEY, JSON.stringify({ commitPanelHeight: 9999 }));
    expect(readLayoutCache().commitPanelHeight).toBe(600);
  });
});

describe('settings.setLayout（F-18）', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    localStorage.clear();
    mocks.get.mockReset();
    mocks.put.mockReset();
  });

  it('只把变更字段发给后端，并立即本地生效 + 写缓存', async () => {
    mocks.put.mockResolvedValue({ theme: 'dark', shortcuts: {}, recentRepos: [], layout: fullLayout({ commitPanelHeight: 300 }) });

    const store = useSettingsStore();
    await store.setLayout({ commitPanelHeight: 300 });

    expect(mocks.put).toHaveBeenCalledTimes(1);
    expect(mocks.put).toHaveBeenCalledWith('/api/settings', {
      layout: { commitPanelHeight: 300 },
    });
    expect(store.layout.commitPanelHeight).toBe(300);
    expect(store.layout.logPanelHeight).toBe(DEFAULT_LAYOUT.logPanelHeight);
    expect(JSON.parse(localStorage.getItem(CACHE_KEY) as string).commitPanelHeight).toBe(300);
  });

  it('越界值先在本端钳制再发送', async () => {
    mocks.put.mockResolvedValue({ theme: 'dark', shortcuts: {}, recentRepos: [], layout: fullLayout({ sidebarWidth: 420 }) });

    const store = useSettingsStore();
    await store.setLayout({ sidebarWidth: 9999 });

    expect(mocks.put).toHaveBeenCalledWith('/api/settings', { layout: { sidebarWidth: 420 } });
    expect(store.layout.sidebarWidth).toBe(420);
  });

  it('空 patch 不发请求', async () => {
    const store = useSettingsStore();
    await store.setLayout({});
    expect(mocks.put).not.toHaveBeenCalled();
  });

  it('持久化失败保留本地值，不抛出', async () => {
    mocks.put.mockRejectedValue(new Error('boom'));
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);

    const store = useSettingsStore();
    await expect(store.setLayout({ logPanelHeight: 260 })).resolves.toBeUndefined();

    expect(store.layout.logPanelHeight).toBe(260);
    expect(JSON.parse(localStorage.getItem(CACHE_KEY) as string).logPanelHeight).toBe(260);
    expect(warn).toHaveBeenCalled();
    warn.mockRestore();
  });

  it('拖拽期间的后端响应不回滚更新的本地值', async () => {
    const pending: Array<(value: unknown) => void> = [];
    mocks.put.mockImplementation(
      () =>
        new Promise((resolve) => {
          pending.push(resolve);
        }),
    );

    const store = useSettingsStore();
    const first = store.setLayout({ commitPanelHeight: 260 });
    // 响应尚未返回时又拖了一次
    store.layout = { ...store.layout, commitPanelHeight: 320 };
    pending[0]?.({ theme: 'dark', shortcuts: {}, recentRepos: [], layout: fullLayout({ commitPanelHeight: 260 }) });
    await first;

    expect(store.layout.commitPanelHeight).toBe(320);
  });

  it('左侧栏最小宽度已按用户要求降为 120（回归：曾为 180）', () => {
    expect(LAYOUT_BOUNDS.sidebarWidth.min).toBe(120);
  });

  it('提交列表列宽可单独持久化（只发变更字段）', async () => {
    mocks.put.mockResolvedValue({
      theme: 'dark',
      shortcuts: {},
      recentRepos: [],
      layout: fullLayout({ commitListWidth: 520 }),
    });

    const store = useSettingsStore();
    await store.setLayout({ commitListWidth: 520 });

    expect(mocks.put).toHaveBeenCalledWith('/api/settings', { layout: { commitListWidth: 520 } });
    expect(store.layout.commitListWidth).toBe(520);
    expect(store.layout.sidebarWidth).toBe(DEFAULT_LAYOUT.sidebarWidth);
  });

  it('load 用后端 layout 覆盖缓存并回写', async () => {
    localStorage.setItem(CACHE_KEY, JSON.stringify(fullLayout({ commitPanelHeight: 300 })));
    mocks.get.mockResolvedValue({
      theme: 'light',
      shortcuts: { refresh: 'F5', fetch: 'Ctrl+Shift+F', push: 'Ctrl+Shift+P' },
      recentRepos: [],
      layout: fullLayout({ commitPanelHeight: 420, logPanelHeight: 200 }),
    });

    const store = useSettingsStore();
    expect(store.layout.commitPanelHeight).toBe(300); // 首屏用缓存
    await store.load();

    expect(store.layout.commitPanelHeight).toBe(420); // 后端为准
    expect(JSON.parse(localStorage.getItem(CACHE_KEY) as string).commitPanelHeight).toBe(420);
  });
});
