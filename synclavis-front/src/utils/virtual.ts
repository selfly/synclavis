/**
 * 提交列表窗口化（虚拟滚动）计算。
 *
 * <p>只渲染视口内的行 + 上下 overscan，避免长提交历史把 DOM 行数线性放大；
 * 行高必须是固定常量（见《详细设计文档》§9.5），窗口计算与行内 :style 共用同一常量。</p>
 *
 * <p>纯函数，便于单测（F-14 / F-15）。</p>
 */
export interface VirtualWindow {
  /** 首个渲染的行下标 */
  start: number;
  /** 末个渲染的行下标（不含） */
  end: number;
  /** 窗口容器相对内容顶部的偏移像素 */
  offsetY: number;
  /** 全部行的总高度，用于撑出滚动条 */
  totalHeight: number;
}

export interface VirtualWindowInput {
  scrollTop: number;
  viewportHeight: number;
  rowHeight: number;
  total: number;
  overscan?: number;
}

function clamp(value: number, min: number, max: number): number {
  if (max < min) {
    return min;
  }
  return Math.max(min, Math.min(max, value));
}

export function computeVirtualWindow(input: VirtualWindowInput): VirtualWindow {
  const total = Math.max(0, Math.floor(input.total));
  if (total === 0) {
    return { start: 0, end: 0, offsetY: 0, totalHeight: 0 };
  }

  const rowHeight = Math.max(1, input.rowHeight);
  const viewportHeight = Math.max(0, input.viewportHeight);
  const overscan = Math.max(0, Math.floor(input.overscan ?? 0));
  const scrollTop = Math.max(0, input.scrollTop);

  const visible = Math.max(1, Math.ceil(viewportHeight / rowHeight));
  // 起始行不允许超过「最后一屏的首行」，否则滚到底部时末行永远渲染不出来
  const maxStart = Math.max(0, total - visible);
  const start = clamp(Math.floor(scrollTop / rowHeight) - overscan, 0, maxStart);
  const end = Math.min(total, start + visible + 2 * overscan);

  return {
    start,
    end,
    offsetY: start * rowHeight,
    totalHeight: total * rowHeight,
  };
}
