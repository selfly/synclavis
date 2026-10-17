/**
 * 布局尺寸（可拖拽面板的高度/宽度）：默认值、上下界、钳制与 localStorage 瞬时缓存。
 *
 * <p>真正的持久化在后端全局设置（`GET/PUT /api/settings` 的 `layout`），
 * 这里只负责「首屏不跳动」的缓存与前后端共用的钳制规则。</p>
 *
 * <p>上下界必须与后端 `LayoutSettings` 的常量保持一致（见《详细设计文档》§9.5）。</p>
 */
export interface LayoutSettings {
  /** 底部提交区（暂存文件 + 提交信息 + Commit）高度 */
  commitPanelHeight: number;
  /** 底部操作日志面板内容高度 */
  logPanelHeight: number;
  /** 左侧边栏宽度 */
  sidebarWidth: number;
  /** 中间提交列表列宽 */
  commitListWidth: number;
}

export type LayoutKey = keyof LayoutSettings;

export const DEFAULT_LAYOUT: LayoutSettings = {
  commitPanelHeight: 190,
  logPanelHeight: 150,
  sidebarWidth: 240,
  commitListWidth: 380,
};

export const LAYOUT_BOUNDS: Record<LayoutKey, { min: number; max: number }> = {
  commitPanelHeight: { min: 120, max: 600 },
  logPanelHeight: { min: 80, max: 480 },
  sidebarWidth: { min: 120, max: 420 },
  commitListWidth: { min: 240, max: 720 },
};

/**
 * 左/中两栏拖拽时为右侧 Diff 区保留的最小宽度。
 *
 * 两栏的动态上限都用它参与计算，否则窄窗口下可以把 Diff 区压成 0
 * （右列是 `min-w-0 flex-1`，再被 AppShell 的 overflow-hidden 裁掉）。
 */
export const RIGHT_COLUMN_MIN_WIDTH = 260;

const LAYOUT_CACHE_KEY = 'synclavis.layout';

export function clamp(value: number, min: number, max: number): number {
  if (!Number.isFinite(value)) {
    return min;
  }
  if (max < min) {
    return min;
  }
  return Math.max(min, Math.min(max, Math.round(value)));
}

export function clampLayoutSize(key: LayoutKey, value: number): number {
  const { min, max } = LAYOUT_BOUNDS[key];
  return clamp(value, min, max);
}

/** 只保留传入的字段并各自钳制，用于 PUT 的增量 patch */
export function clampLayout(patch: Partial<LayoutSettings>): Partial<LayoutSettings> {
  const result: Partial<LayoutSettings> = {};
  (Object.keys(patch) as LayoutKey[]).forEach((key) => {
    const value = patch[key];
    if (typeof value === 'number') {
      result[key] = clampLayoutSize(key, value);
    }
  });
  return result;
}

/** 用任意来源（后端响应 / 缓存 / 半个对象）补全成一份合法布局 */
export function resolveLayout(candidate?: Partial<LayoutSettings> | null): LayoutSettings {
  return {
    commitPanelHeight: clampLayoutSize(
      'commitPanelHeight',
      candidate?.commitPanelHeight ?? DEFAULT_LAYOUT.commitPanelHeight,
    ),
    logPanelHeight: clampLayoutSize(
      'logPanelHeight',
      candidate?.logPanelHeight ?? DEFAULT_LAYOUT.logPanelHeight,
    ),
    sidebarWidth: clampLayoutSize('sidebarWidth', candidate?.sidebarWidth ?? DEFAULT_LAYOUT.sidebarWidth),
    commitListWidth: clampLayoutSize(
      'commitListWidth',
      candidate?.commitListWidth ?? DEFAULT_LAYOUT.commitListWidth,
    ),
  };
}

/**
 * 把拖拽位移换算成新尺寸。
 *
 * <p>`invert` 用于「向上/向左拖变大」的分隔条（提交面板、日志面板位于其下/其右）。</p>
 */
export function resolveDragSize(input: {
  base: number;
  delta: number;
  invert: boolean;
  min: number;
  max: number;
}): number {
  const raw = input.invert ? input.base - input.delta : input.base + input.delta;
  return clamp(raw, input.min, input.max);
}

/**
 * 分隔条的键盘语义：`+1` 表示「变大」，`-1` 表示「变小」，`0` 表示该按键不处理。
 *
 * <p>注意与 {@link resolveDragSize} 的区别：键盘表达的是「变大/变小」，
 * 不能再套用 `invert` 的位移翻转，否则上下方向会反过来。</p>
 */
export function resolveResizeDirection(input: {
  axis: 'x' | 'y';
  invert: boolean;
  key: string;
}): -1 | 0 | 1 {
  const growKey =
    input.axis === 'y' ? (input.invert ? 'ArrowUp' : 'ArrowDown') : input.invert ? 'ArrowLeft' : 'ArrowRight';
  const shrinkKey =
    input.axis === 'y' ? (input.invert ? 'ArrowDown' : 'ArrowUp') : input.invert ? 'ArrowRight' : 'ArrowLeft';

  if (input.key === growKey) {
    return 1;
  }
  if (input.key === shrinkKey) {
    return -1;
  }
  return 0;
}

export function readLayoutCache(): LayoutSettings {
  try {
    const raw = localStorage.getItem(LAYOUT_CACHE_KEY);
    if (!raw) {
      return { ...DEFAULT_LAYOUT };
    }
    return resolveLayout(JSON.parse(raw) as Partial<LayoutSettings>);
  } catch {
    return { ...DEFAULT_LAYOUT };
  }
}

export function writeLayoutCache(layout: LayoutSettings): void {
  try {
    localStorage.setItem(LAYOUT_CACHE_KEY, JSON.stringify(layout));
  } catch {
    // 缓存失败不影响功能（后端仍为准）
  }
}
