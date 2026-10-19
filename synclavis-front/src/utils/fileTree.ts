/**
 * 暂存区文件列表的展示方式与树形构建。
 *
 * <p>暂存区有三种渲染粒度：单文件、整个目录、整个区块。三者最终都归结为
 * `paths: string[]` 下发到 `POST /stage` / `/unstage`（见《详细设计文档》§6.2），
 * 所以「暂存文件夹 = 其下所有文件」只是把目录节点展开成文件路径，后端无需感知树形。</p>
 *
 * <p>纯函数，便于单测（F-27）。</p>
 */
import type { ChangeType } from '@/types/repo';

/** 列表 / 树形两种展示方式 */
export type FileViewMode = 'list' | 'tree';

/** 建树输入：文件相对路径 + 变更类型（未跟踪按 ADD 呈现） */
export interface WorktreeFileItem {
  path: string;
  changeType: ChangeType;
}

/** 树节点：目录用 children 承载，文件用 changeType 承载（目录的 changeType 恒为 null） */
export interface FileTreeNode {
  /** 单段显示名（目录名或文件名） */
  name: string;
  /** 仓库内完整相对路径，目录批量操作按它收集后代 */
  path: string;
  dir: boolean;
  changeType: ChangeType | null;
  /** 该子树下的文件总数：目录聚合后代，文件恒为 1 */
  count: number;
  children: FileTreeNode[];
}

/** 压平后的一行：可见节点 + 缩进深度（树形折叠后供渲染） */
export interface VisibleRow {
  node: FileTreeNode;
  depth: number;
}

const VIEW_CACHE_KEY = 'synclavis.commitFileView';

/**
 * 从文件列表构建目录树。
 *
 * <p>同一路径可能同时出现在「已暂存」和「未暂存」（部分暂存），因此调用方按分区
 * 分别建树，不在这里做跨分区合并。</p>
 */
export function buildFileTree(files: WorktreeFileItem[]): FileTreeNode[] {
  const roots: FileTreeNode[] = [];
  const dirs = new Map<string, FileTreeNode>();

  const ensureDir = (path: string): FileTreeNode => {
    const existing = dirs.get(path);
    if (existing) {
      return existing;
    }
    const slash = path.lastIndexOf('/');
    const name = slash < 0 ? path : path.slice(slash + 1);
    const node: FileTreeNode = { name, path, dir: true, changeType: null, count: 0, children: [] };
    dirs.set(path, node);
    if (slash < 0) {
      roots.push(node);
    } else {
      ensureDir(path.slice(0, slash)).children.push(node);
    }
    return node;
  };

  for (const file of files) {
    const slash = file.path.lastIndexOf('/');
    const name = slash < 0 ? file.path : file.path.slice(slash + 1);
    const leaf: FileTreeNode = {
      name,
      path: file.path,
      dir: false,
      changeType: file.changeType,
      count: 1,
      children: [],
    };
    if (slash < 0) {
      roots.push(leaf);
    } else {
      ensureDir(file.path.slice(0, slash)).children.push(leaf);
    }
  }

  return finalize(roots);
}

/** 目录在前、文件在后，各自按名称排序；自底向上聚合 count */
function finalize(nodes: FileTreeNode[]): FileTreeNode[] {
  nodes.sort((a, b) => (a.dir === b.dir ? a.name.localeCompare(b.name) : a.dir ? -1 : 1));
  for (const node of nodes) {
    if (node.dir) {
      finalize(node.children);
      node.count = node.children.reduce((sum, child) => sum + child.count, 0);
    } else {
      node.count = 1;
    }
  }
  return nodes;
}

/** 收集节点下的全部文件路径（目录=后代文件；文件=自身）。顺序稳定，供整目录暂存使用。 */
export function collectFilePaths(node: FileTreeNode): string[] {
  const result: string[] = [];
  const walk = (current: FileTreeNode): void => {
    if (current.dir) {
      current.children.forEach(walk);
    } else {
      result.push(current.path);
    }
  };
  walk(node);
  return result;
}

/** 按折叠状态把树压平成渲染行；折叠的目录不展开其后代。 */
export function flattenVisible(
  nodes: FileTreeNode[],
  isCollapsed: (path: string) => boolean,
  depth = 0,
): VisibleRow[] {
  const rows: VisibleRow[] = [];
  const walk = (list: FileTreeNode[], currentDepth: number): void => {
    for (const node of list) {
      rows.push({ node, depth: currentDepth });
      if (node.dir && !isCollapsed(node.path)) {
        walk(node.children, currentDepth + 1);
      }
    }
  };
  walk(nodes, depth);
  return rows;
}

/** 读取展示偏好；空值或非法值一律回退「列表」，保证首屏行为可预期。 */
export function readFileView(): FileViewMode {
  try {
    return localStorage.getItem(VIEW_CACHE_KEY) === 'tree' ? 'tree' : 'list';
  } catch {
    return 'list';
  }
}

export function writeFileView(mode: FileViewMode): void {
  try {
    localStorage.setItem(VIEW_CACHE_KEY, mode);
  } catch {
    // 缓存失败不影响功能
  }
}
