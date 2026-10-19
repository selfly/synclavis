/**
 * 项目文件树（侧栏「文件」模式）的纯函数。
 *
 * <p>树是**懒加载**的：只保存「已加载目录 → 其直接子项」，展开某个目录时才向后端要数据。
 * 这样即使仓库里有 node_modules 之类的大目录，也不会一次性把整棵树读进来。</p>
 *
 * <p>按关键字过滤走的是 `/files/search`（后端遍历，能命中未展开的目录），
 * 因此这里只负责「已加载 + 已展开」的层级压平，不做任何本地搜索。</p>
 */
import type { RepoFileEntry } from '@/api/files';

/** 一行可见节点 + 缩进深度 */
export interface ProjectRow {
  node: RepoFileEntry;
  depth: number;
}

/** 目录路径（仓库根为 ''）→ 直接子项 */
export type ProjectChildren = Record<string, RepoFileEntry[]>;

const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' });

/** 目录在前、文件在后，各自按名称自然排序（后端已排好，前端再兜底一次） */
export function sortEntries(entries: RepoFileEntry[]): RepoFileEntry[] {
  return [...entries].sort((left, right) => {
    if (left.dir !== right.dir) {
      return left.dir ? -1 : 1;
    }
    return collator.compare(left.name, right.name);
  });
}

/**
 * 压平成渲染行：只输出已展开目录的后代，未加载的目录不产生占位行。
 *
 * @param children 已加载的目录内容
 * @param expanded 已展开的目录路径集合
 */
export function flattenProjectTree(
  children: ProjectChildren,
  expanded: ReadonlySet<string>,
): ProjectRow[] {
  const rows: ProjectRow[] = [];

  const walk = (dirPath: string, depth: number): void => {
    const entries = children[dirPath];
    if (!entries) {
      return;
    }
    for (const node of entries) {
      rows.push({ node, depth });
      if (node.dir && expanded.has(node.path)) {
        walk(node.path, depth + 1);
      }
    }
  };

  walk('', 0);
  return rows;
}

/** 拆出目录前缀与文件名（文件头与搜索结果共用） */
export function splitPath(path: string): { dir: string; name: string } {
  const index = path.lastIndexOf('/');
  if (index < 0) {
    return { dir: '', name: path };
  }
  return { dir: path.slice(0, index), name: path.slice(index + 1) };
}

/** 人类可读的文件大小 */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) {
    return '';
  }
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  const units = ['KB', 'MB', 'GB', 'TB'];
  let value = bytes / 1024;
  let index = 0;
  while (value >= 1024 && index < units.length - 1) {
    value /= 1024;
    index += 1;
  }
  return `${value < 10 ? value.toFixed(1) : Math.round(value)} ${units[index]}`;
}
