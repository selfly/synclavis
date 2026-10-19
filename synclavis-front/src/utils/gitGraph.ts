/**
 * 提交泳道图（timeline）布局纯函数（见《详细设计文档》§9.5.4）。
 *
 * <p>输入是**当前已加载的整个提交集合**（新 → 旧），输出与之一一对应的行布局。
 * 每行只依赖本行数据 + 全局列状态，因此与窗口化渲染天然兼容：相邻行没被渲染也不影响。</p>
 *
 * <p>列状态维护方式：`active[i]` = 第 i 列「期待出现的下一个提交 id」。逐行推进时，
 * 命中某列则消费它，否则找一个空闲列（没有就开新列）；提交的父提交落在哪个列由同一套规则决定。</p>
 *
 * <p>两条刻意的取舍：</p>
 * <ul>
 *   <li><b>列号不压缩</b>：列一旦分配就保留编号（空闲列可被后续提交复用，但绝不整体左移），
 *       否则滚动时同一分支的线会横向抖动；</li>
 *   <li><b>只认「已加载集合内」的父提交</b>：父提交不在集合里（被作者/关键字过滤掉，或还没翻页
 *       加载到）就不分配列 —— 线的该行终止（断线），与 SourceGit 的表现一致。</li>
 * </ul>
 */
export interface GraphCommit {
  id: string;
  parents?: readonly string[];
}

export interface GraphSegment {
  x1: number;
  y1: number;
  x2: number;
  y2: number;
  /** 是否用三次贝塞尔画（同一列内的竖线为 false） */
  curve: boolean;
  /** 颜色槽位（1-based），对应 `--sl-graph-${color}` */
  color: number;
}

export interface GraphRow {
  /** 本提交所在列（0-based，已按 maxLanes 收敛到哨兵列） */
  lane: number;
  /** 圆点中心 x / y */
  x: number;
  y: number;
  /** 圆点颜色槽位（1-based），与列颜色一致 */
  color: number;
  /** 本行是否有列被并入哨兵列 */
  overflow: boolean;
  /** 被并入哨兵列的活跃列数（>0 时行内提示 `+N`） */
  overflowCount: number;
  /** 本行线段（上半段竖线 / 下半段竖线 / 圆点到父列的曲线） */
  segments: GraphSegment[];
}

export interface GraphLayout {
  /** 整表列数（1..maxLanes），用于统一列宽 */
  laneCount: number;
  /** 过程出现的最大列数（未收敛），超出 maxLanes 时仅用于诊断 */
  rawLaneCount: number;
  rows: GraphRow[];
}

export interface GraphOptions {
  /** 行高，必须与窗口化的 `ROW_H` 同源（默认 44） */
  rowHeight?: number;
  /** 单列宽度（默认 14） */
  laneWidth?: number;
  /** 列数上限，超出并入哨兵列（默认 8） */
  maxLanes?: number;
  /** 颜色槽位数，与 `--sl-graph-*` 数量一致（默认 6） */
  colors?: number;
}

export const DEFAULT_ROW_HEIGHT = 44;
export const DEFAULT_LANE_WIDTH = 14;
export const DEFAULT_MAX_LANES = 8;
export const DEFAULT_COLORS = 6;

export function computeGraph(commits: readonly GraphCommit[], options: GraphOptions = {}): GraphLayout {
  const rowHeight = options.rowHeight ?? DEFAULT_ROW_HEIGHT;
  const laneWidth = options.laneWidth ?? DEFAULT_LANE_WIDTH;
  const maxLanes = Math.max(1, Math.floor(options.maxLanes ?? DEFAULT_MAX_LANES));
  const colors = Math.max(1, Math.floor(options.colors ?? DEFAULT_COLORS));
  const half = rowHeight / 2;

  const present = new Set<string>();
  for (let i = 0; i < commits.length; i += 1) {
    present.add(commits[i].id);
  }

  /** 每列期待的提交 id（null = 空闲，可被后续提交复用） */
  const active: (string | null)[] = [];
  const rows: GraphRow[] = [];
  let rawLaneCount = 0;

  /** 取一个空闲列；没有则追加新列（列号只增不减，见文件头注释） */
  function takeSlot(): number {
    const free = active.indexOf(null);
    if (free >= 0) {
      return free;
    }
    active.push(null);
    return active.length - 1;
  }

  function laneX(lane: number): number {
    return Math.min(lane, maxLanes - 1) * laneWidth + laneWidth / 2;
  }

  function laneColor(lane: number): number {
    return (Math.min(lane, maxLanes - 1) % colors) + 1;
  }

  for (let index = 0; index < commits.length; index += 1) {
    const commit = commits[index];
    let lane = active.indexOf(commit.id);
    if (lane < 0) {
      lane = takeSlot();
    }
    const before = active.slice();
    // 本提交消费掉这一列的期待
    active[lane] = null;

    // 只有「已加载集合内」的父提交才分配列（过滤 / 分页边界处线会断）
    const parents = commit.parents ?? [];
    const targets: number[] = [];
    for (let k = 0; k < parents.length; k += 1) {
      const parent = parents[k];
      if (!parent || !present.has(parent)) {
        targets.push(-1);
        continue;
      }
      const existing = active.indexOf(parent);
      if (existing >= 0) {
        targets.push(existing);
        continue;
      }
      if (k === 0) {
        // 首父继承本列，分支线在视觉上继续而不是拐弯
        active[lane] = parent;
        targets.push(lane);
      } else {
        const slot = takeSlot();
        active[slot] = parent;
        targets.push(slot);
      }
    }

    rawLaneCount = Math.max(rawLaneCount, active.length, lane + 1);

    const segments: GraphSegment[] = [];
    // 上半段：本行开始时仍活跃的列，从 y=0 竖直到中点
    for (let j = 0; j < before.length; j += 1) {
      if (before[j] === null) {
        continue;
      }
      segments.push({ x1: laneX(j), y1: 0, x2: laneX(j), y2: half, curve: false, color: laneColor(j) });
    }
    // 下半段：本行结束后仍活跃的列，从中点竖直到 y=rowHeight
    for (let j = 0; j < active.length; j += 1) {
      if (active[j] === null) {
        continue;
      }
      segments.push({ x1: laneX(j), y1: half, x2: laneX(j), y2: rowHeight, curve: false, color: laneColor(j) });
    }
    // 圆点 → 父列：与圆点同列的由上面的下半段覆盖，其余画曲线
    for (let k = 0; k < targets.length; k += 1) {
      const target = targets[k];
      if (target < 0 || target === lane) {
        continue;
      }
      segments.push({
        x1: laneX(lane),
        y1: half,
        x2: laneX(target),
        y2: rowHeight,
        curve: true,
        color: laneColor(target),
      });
    }

    let overflowCount = 0;
    const span = Math.max(before.length, active.length);
    for (let j = maxLanes; j < span; j += 1) {
      if ((before[j] ?? null) !== null || (active[j] ?? null) !== null) {
        overflowCount += 1;
      }
    }

    rows.push({
      lane: Math.min(lane, maxLanes - 1),
      x: laneX(lane),
      y: half,
      color: laneColor(lane),
      overflow: overflowCount > 0,
      overflowCount,
      segments,
    });
  }

  return {
    laneCount: Math.max(1, Math.min(rawLaneCount, maxLanes)),
    rawLaneCount,
    rows,
  };
}
