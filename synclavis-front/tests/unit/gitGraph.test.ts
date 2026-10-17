import { describe, expect, it } from 'vitest';
import { computeGraph, DEFAULT_LANE_WIDTH, DEFAULT_MAX_LANES, DEFAULT_ROW_HEIGHT } from '@/utils/gitGraph';
import type { GraphCommit } from '@/utils/gitGraph';

const HALF = DEFAULT_ROW_HEIGHT / 2;

function c(id: string, ...parents: string[]): GraphCommit {
  return { id, parents };
}

/** 取某行的「下半段竖线」所在的列（曲线不算） */
function lowerLanes(row: { segments: { x1: number; y1: number; x2: number; y2: number; curve: boolean }[] }): number[] {
  return row.segments
    .filter((s) => !s.curve && s.y1 === HALF && s.y2 === DEFAULT_ROW_HEIGHT)
    .map((s) => Math.round((s.x1 - DEFAULT_LANE_WIDTH / 2) / DEFAULT_LANE_WIDTH));
}

describe('computeGraph（P13 泳道图布局）', () => {
  it('空列表：单列、无行', () => {
    const g = computeGraph([]);
    expect(g.rows).toEqual([]);
    expect(g.laneCount).toBe(1);
    expect(g.rawLaneCount).toBe(0);
  });

  it('线性历史：恒 1 列，圆点纵列排列，root 终止下半段', () => {
    const g = computeGraph([c('c3', 'c2'), c('c2', 'c1'), c('c1')]);
    expect(g.laneCount).toBe(1);
    expect(g.rawLaneCount).toBe(1);
    expect(g.rows.map((r) => r.lane)).toEqual([0, 0, 0]);
    expect(g.rows.map((r) => r.x)).toEqual([7, 7, 7]);

    // 首行（HEAD）上方无线
    expect(g.rows[0].segments.some((s) => s.y1 === 0)).toBe(false);
    expect(lowerLanes(g.rows[0])).toEqual([0]);
    expect(g.rows[0].segments).toHaveLength(1);
    // 中间行：上半段 + 下半段
    expect(g.rows[1].segments).toHaveLength(2);
    // root：只有上半段，线在此终止
    expect(g.rows[2].segments).toHaveLength(1);
    expect(lowerLanes(g.rows[2])).toEqual([]);
    expect(g.rows[2].y).toBe(HALF);
  });

  it('单 merge：整表 2 列，merge 行有两条出边 + 一条到第二父的曲线', () => {
    // 顺序即后端拓扑序：M, 首父链 c1, 第二父链 f2 f1, 共同祖先 base
    const g = computeGraph([
      c('M', 'c1', 'f2'),
      c('c1', 'base'),
      c('f2', 'f1'),
      c('f1', 'base'),
      c('base'),
    ]);
    expect(g.laneCount).toBe(2);
    expect(g.rawLaneCount).toBe(2);
    expect(g.rows.map((r) => r.lane)).toEqual([0, 0, 1, 1, 0]);

    // merge 行：下半段两条（首父本列 + 第二父新列），第二条是曲线且指向第 1 列
    const merge = g.rows[0];
    expect(lowerLanes(merge)).toEqual([0, 1]);
    const curves = merge.segments.filter((s) => s.curve);
    expect(curves).toHaveLength(1);
    expect(curves[0].x1).toBe(7);
    expect(curves[0].x2).toBe(21);
    expect(curves[0].color).toBe(2);
    expect(merge.segments).toHaveLength(3);

    // 分叉链上的提交：上半段 + 下半段各两条（另一列是贯穿线）
    expect(g.rows[2].segments).toHaveLength(4);
    // 汇合行（f1 -> base）：曲线回到第 0 列，本列线终止
    const join = g.rows[3];
    expect(lowerLanes(join)).toEqual([0]);
    expect(join.segments.filter((s) => s.curve)).toEqual([
      { x1: 21, y1: HALF, x2: 7, y2: DEFAULT_ROW_HEIGHT, curve: true, color: 1 },
    ]);
    // 共同祖先：两列都已收拢，仅剩第 0 列的上半段
    expect(g.rows[4].segments).toHaveLength(1);
  });

  it('octopus merge：一个圆点引出多条曲线，父提交各占一列', () => {
    const g = computeGraph([
      c('M', 'a', 'b', 'd'),
      c('a'),
      c('b'),
      c('d'),
    ]);
    expect(g.laneCount).toBe(3);
    expect(g.rows[0].lane).toBe(0);
    expect(lowerLanes(g.rows[0])).toEqual([0, 1, 2]);
    expect(g.rows[0].segments.filter((s) => s.curve).map((s) => s.x2)).toEqual([21, 35]);
  });

  it('同列颜色恒定：按列号取模，第 7 列回到第 1 色', () => {
    const parents = ['p0', 'p1', 'p2', 'p3', 'p4', 'p5', 'p6'];
    const g = computeGraph([c('M', ...parents), ...parents.map((p) => c(p))]);
    const merge = g.rows[0];
    expect(lowerLanes(merge)).toEqual([0, 1, 2, 3, 4, 5, 6]);
    expect(merge.segments.filter((s) => !s.curve).map((s) => s.color)).toEqual([1, 2, 3, 4, 5, 6, 1]);
  });

  it('父提交不在集合内（过滤 / 分页边界）：该行线终止，不产生悬空列', () => {
    // c2 的父 c1 被过滤掉 → c2 行没有下半段；c2 自身仍会画圆点
    const g = computeGraph([c('c3', 'c2'), c('c2', 'c1'), c('c1')]);
    const filtered = computeGraph([c('c2', 'c1')]);
    expect(filtered.laneCount).toBe(1);
    expect(filtered.rows[0].lane).toBe(0);
    expect(filtered.rows[0].segments).toEqual([]);
    // 对照组：父在集合里就有下半段
    expect(lowerLanes(g.rows[1])).toEqual([0]);
  });

  it('分页边界：末行父未加载 → 无下半段，加载后自动接上', () => {
    const page1 = computeGraph([c('c2', 'c1')]);
    expect(page1.rows[0].segments).toEqual([]);
    const page2 = computeGraph([c('c2', 'c1'), c('c1')]);
    expect(lowerLanes(page2.rows[0])).toEqual([0]);
    expect(page2.laneCount).toBe(1);
  });

  it('列溢出：超过 maxLanes 并入哨兵列，laneCount 收敛为上限并标记 overflow', () => {
    const parents = ['p0', 'p1', 'p2', 'p3', 'p4', 'p5', 'p6', 'p7', 'p8', 'p9'];
    const g = computeGraph([c('M', ...parents), ...parents.map((p) => c(p))], { maxLanes: 8 });
    expect(g.rawLaneCount).toBe(10);
    expect(g.laneCount).toBe(8);
    const merge = g.rows[0];
    expect(merge.overflow).toBe(true);
    expect(merge.overflowCount).toBe(2);
    // p8 / p9 被收敛到哨兵列（x = 7*14+7 = 105）
    expect(merge.segments.filter((s) => s.x2 === 105).length).toBeGreaterThanOrEqual(2);
  });

  it('默认列宽 / 行高可被 options 覆盖，且与行高同源', () => {
    const g = computeGraph([c('a', 'b'), c('b')], { rowHeight: 20, laneWidth: 10 });
    expect(g.rows[0].y).toBe(10);
    expect(g.rows[0].x).toBe(5);
    expect(g.rows[0].segments[0]).toMatchObject({ y1: 10, y2: 20 });
  });

  it('行数与提交数一一对应（组件按下标配对渲染，这个不变式不能破）', () => {
    const commits = [c('c3', 'c2'), c('c2', 'c1', 'x'), c('c1'), c('x'), c('isolated')];
    const g = computeGraph(commits);
    expect(g.rows).toHaveLength(commits.length);
    // 每行都能独立判断（不依赖相邻行是否被窗口化渲染掉）
    for (const row of g.rows) {
      expect(row.segments.length).toBeGreaterThanOrEqual(0);
      expect(Number.isInteger(row.lane)).toBe(true);
      expect(row.lane).toBeLessThanOrEqual(DEFAULT_MAX_LANES - 1);
    }
  });

  it('列号不压缩：释放的列可被复用，但既有列不左移', () => {
    // M 引出 a / b 两列；a 链先结束，其后 c 复用空闲的第 1 列
    const g = computeGraph([
      c('M', 'a', 'b'),
      c('a', 'base'),
      c('b', 'base'),
      c('c', 'base'),
      c('base'),
    ]);
    expect(g.rows.map((r) => r.lane)).toEqual([0, 0, 1, 1, 0]);
    expect(g.laneCount).toBe(2);
  });

  it('date 顺序：另一条分支的列会在别人的行里贯穿（SourceGit 那种平行线）', () => {
    // 后端 order=date 的输出形态：merge 之后两个分支按时间交错
    const interleaved = [
      c('M', 'B3', 'A3'),
      c('B3', 'B2'),
      c('A3', 'A2'),
      c('B2', 'B1'),
      c('A2', 'A1'),
      c('B1', 'base'),
      c('A1', 'base'),
      c('base'),
    ];
    const g = computeGraph(interleaved);
    expect(g.rows).toHaveLength(interleaved.length);
    expect(g.laneCount).toBe(2);

    // A 分支的列（第 1 列，x = 21）在 B 分支的行里仍连续：上半段 0..22 + 下半段 22..44
    const x = DEFAULT_LANE_WIDTH + DEFAULT_LANE_WIDTH / 2;
    for (const row of g.rows.slice(1, 6)) {
      const passing = row.segments.filter((s) => !s.curve && s.x1 === x && s.x2 === x);
      expect(passing.map((s) => `${s.y1}-${s.y2}`)).toEqual(['0-22', '22-44']);
    }

    // 对照：拓扑序下同分支扎堆，lane 序列连续
    const topo = [
      c('M', 'B3', 'A3'),
      c('A3', 'A2'),
      c('A2', 'A1'),
      c('A1', 'base'),
      c('B3', 'B2'),
      c('B2', 'B1'),
      c('B1', 'base'),
      c('base'),
    ];
    expect(g.rows.map((r) => r.lane)).toEqual([0, 0, 1, 0, 1, 0, 1, 0]);
    expect(computeGraph(topo).rows.map((r) => r.lane)).toEqual([0, 1, 1, 1, 0, 0, 0, 1]);
  });
});
