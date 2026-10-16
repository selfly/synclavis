import { describe, expect, it } from 'vitest';
import { computeVirtualWindow } from '@/utils/virtual';

const ROW_H = 44;
const VIEWPORT = 660;

function win(overrides: Partial<Parameters<typeof computeVirtualWindow>[0]> = {}) {
  return computeVirtualWindow({
    scrollTop: 0,
    viewportHeight: VIEWPORT,
    rowHeight: ROW_H,
    total: 442,
    overscan: 6,
    ...overrides,
  });
}

describe('computeVirtualWindow（F-14 / F-15）', () => {
  it('顶部：从 0 开始，渲染一屏 + overscan', () => {
    const w = win();
    const visible = Math.ceil(VIEWPORT / ROW_H); // 15
    expect(w.start).toBe(0);
    expect(w.end).toBe(visible + 12);
    expect(w.offsetY).toBe(0);
    expect(w.totalHeight).toBe(442 * ROW_H);
  });

  it('中部：按 scrollTop 定位，窗口随偏移平移', () => {
    const w = win({ scrollTop: 10 * ROW_H });
    expect(w.start).toBe(4); // 10 - overscan(6)
    expect(w.offsetY).toBe(4 * ROW_H);
    expect(w.end).toBeLessThanOrEqual(442);
  });

  it('底部：末行必定在窗口内，且 start 不超过最后一屏首行', () => {
    const visible = Math.ceil(VIEWPORT / ROW_H);
    const w = win({ scrollTop: 442 * ROW_H, overscan: 6 });
    expect(w.end).toBe(442);
    expect(w.start).toBe(442 - visible);
    expect(w.start + visible).toBe(442);
  });

  it('DOM 行数远小于总条数（虚拟滚动生效）', () => {
    const visible = Math.ceil(VIEWPORT / ROW_H);
    expect(win().end - win().start).toBeLessThan(40);
    expect(win().end - win().start).toBeLessThan(visible + 13);
  });

  it('total = 0 → 全零，不产生占位高度', () => {
    expect(win({ total: 0 })).toEqual({ start: 0, end: 0, offsetY: 0, totalHeight: 0 });
  });

  it('提交数少于一屏时全部渲染', () => {
    const w = win({ total: 3 });
    expect(w.start).toBe(0);
    expect(w.end).toBe(3);
    expect(w.totalHeight).toBe(3 * ROW_H);
  });

  it('越界输入被钳制（负数 scrollTop / 未测量的 viewport / 非法 rowHeight）', () => {
    const w = win({ scrollTop: -100, viewportHeight: 0, rowHeight: 0, total: 10, overscan: -5 });
    expect(w.start).toBe(0);
    expect(w.offsetY).toBe(0);
    expect(w.end).toBeGreaterThan(0);
    expect(w.end).toBeLessThanOrEqual(10);
    expect(w.totalHeight).toBe(10 * 1); // rowHeight 兜底为 1
  });

  it('overscan 省略时按 0 处理', () => {
    const w = computeVirtualWindow({ scrollTop: 0, viewportHeight: VIEWPORT, rowHeight: ROW_H, total: 100 });
    expect(w.start).toBe(0);
    expect(w.end).toBe(Math.ceil(VIEWPORT / ROW_H));
  });
});
