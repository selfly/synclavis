import { defineStore } from 'pinia';
import { filesApi, type RepoFileContent, type RepoFileEntry } from '@/api/files';
import { sortEntries, type ProjectChildren } from '@/utils/projectTree';

/** 侧栏两种模式：Git（分支/标签/Stash）与文件（项目文件树） */
export type SidebarMode = 'git' | 'files';

const MODE_CACHE_KEY = 'synclavis.sidebarMode';
const IGNORED_CACHE_KEY = 'synclavis.showIgnoredFiles';

export interface ProjectTreeState {
  /** 已加载的目录内容，键为目录相对路径（仓库根为 ''） */
  children: ProjectChildren;
  /** 已展开的目录路径 */
  expanded: Set<string>;
  /** 是否显示被 .gitignore 忽略的条目 */
  showIgnored: boolean;
  loading: boolean;
  error: string | null;
}

export interface FileContentState {
  loading: boolean;
  data: RepoFileContent | null;
  error: string | null;
}

/** 关键字搜索结果（后端遍历，能命中未展开的目录） */
export interface FileSearchState {
  loading: boolean;
  error: string | null;
  matches: RepoFileEntry[];
  truncated: boolean;
}

function emptyTree(): ProjectTreeState {
  return { children: {}, expanded: new Set(), showIgnored: readShowIgnored(), loading: false, error: null };
}

function emptyContent(): FileContentState {
  return { loading: false, data: null, error: null };
}

function emptySearch(): FileSearchState {
  return { loading: false, error: null, matches: [], truncated: false };
}

/** 首屏缓存：模式与「显示忽略项」都只存前端偏好（localStorage 前缀 `synclavis.`） */
export function readSidebarMode(): SidebarMode {
  try {
    return localStorage.getItem(MODE_CACHE_KEY) === 'files' ? 'files' : 'git';
  } catch {
    return 'git';
  }
}

function writeSidebarMode(mode: SidebarMode): void {
  try {
    localStorage.setItem(MODE_CACHE_KEY, mode);
  } catch {
    // 缓存失败不影响功能
  }
}

function readShowIgnored(): boolean {
  try {
    return localStorage.getItem(IGNORED_CACHE_KEY) === 'true';
  } catch {
    return false;
  }
}

function writeShowIgnored(value: boolean): void {
  try {
    localStorage.setItem(IGNORED_CACHE_KEY, String(value));
  } catch {
    // 缓存失败不影响功能
  }
}

/**
 * 项目文件树 + 当前查看的文件内容。
 *
 * <p>树按目录懒加载并缓存，刷新时整棵清空重取根目录；
 * 内容与 Diff 互斥（选文件时由 RepoWorkspace 负责清掉 Diff 选择）。</p>
 */
export const useFilesStore = defineStore('files', {
  state: () => ({
    trees: {} as Record<string, ProjectTreeState>,
    contents: {} as Record<string, FileContentState>,
    searches: {} as Record<string, FileSearchState>,
    /** 每次搜索的序号，用于丢弃晚到的旧响应 */
    searchSeq: {} as Record<string, number>,
    /** 当前查看的文件（仓库内相对路径），null 表示右列仍显示 Diff */
    opened: {} as Record<string, string | null>,
    mode: readSidebarMode(),
  }),

  getters: {
    tree(state) {
      return (repoId: string): ProjectTreeState => state.trees[repoId] ?? emptyTree();
    },
    content(state) {
      return (repoId: string): FileContentState => state.contents[repoId] ?? emptyContent();
    },
    search(state) {
      return (repoId: string): FileSearchState => state.searches[repoId] ?? emptySearch();
    },
    openedPath(state) {
      return (repoId: string): string | null => state.opened[repoId] ?? null;
    },
  },

  actions: {
    ensure(repoId: string): ProjectTreeState {
      if (!this.trees[repoId]) {
        this.trees[repoId] = emptyTree();
      }
      // 必须经由 store 状态重新读取：首次写入时 Pinia 存的是**裸对象**，
      // 直接返回它会绕过 Proxy，后续 children/expanded 的改动不会被追踪（树永远空白）。
      return this.trees[repoId];
    },

    setMode(mode: SidebarMode): void {
      this.mode = mode;
      writeSidebarMode(mode);
    },

    /** 加载某个目录的直接子项（已加载则跳过，`force` 时重取） */
    async loadDir(repoId: string, path: string, force = false): Promise<void> {
      const tree = this.ensure(repoId);
      if (!force && tree.children[path]) {
        return;
      }
      tree.loading = true;
      tree.error = null;
      try {
        const list = await filesApi.list(repoId, path, tree.showIgnored);
        // 用新对象触发响应式更新（直接改属性在 Set/Record 上不够可靠）
        tree.children = { ...tree.children, [path]: sortEntries(list.entries) };
      } catch (error) {
        tree.error = error instanceof Error ? error.message : String(error);
      } finally {
        tree.loading = false;
      }
    },

    /** 首次进入文件模式：加载根目录 */
    async ensureRoot(repoId: string): Promise<void> {
      await this.loadDir(repoId, '');
    },

    /** 展开/折叠目录；展开时按需加载子项 */
    async toggleDir(repoId: string, path: string): Promise<void> {
      const tree = this.ensure(repoId);
      const next = new Set(tree.expanded);
      if (next.has(path)) {
        next.delete(path);
      } else {
        next.add(path);
        tree.expanded = next;
        await this.loadDir(repoId, path);
        return;
      }
      tree.expanded = next;
    },

    /** 切换「显示忽略项」：清空已加载缓存后重取根目录 */
    async setShowIgnored(repoId: string, value: boolean): Promise<void> {
      const tree = this.ensure(repoId);
      tree.showIgnored = value;
      writeShowIgnored(value);
      await this.reloadRoot(repoId);
    },

    /** 刷新：清空缓存并重取根目录（展开状态一并重置，避免出现「已展开但没有子项」的假象） */
    async refresh(repoId: string): Promise<void> {
      await this.reloadRoot(repoId);
    },

    reloadRoot(repoId: string): Promise<void> {
      const tree = this.ensure(repoId);
      tree.children = {};
      tree.expanded = new Set();
      return this.loadDir(repoId, '');
    },

    /** 双击文件：加载内容并切换右列 */
    async openFile(repoId: string, path: string): Promise<void> {
      this.opened[repoId] = path;
      if (!this.contents[repoId]) {
        this.contents[repoId] = emptyContent();
      }
      // 与 ensure() 同理：必须从 store 状态取回（首次写入拿到的是裸对象）
      const state = this.contents[repoId];
      state.loading = true;
      state.error = null;
      state.data = null;
      try {
        state.data = await filesApi.content(repoId, path);
      } catch (error) {
        state.error = error instanceof Error ? error.message : String(error);
      } finally {
        state.loading = false;
      }
    },

    /** 关闭文件查看，右列回到 Diff */
    closeFile(repoId: string): void {
      this.opened[repoId] = null;
      this.contents[repoId] = emptyContent();
    },

    /**
     * 关键字搜索：命中未展开的目录，所以必须走后端（前端只负责防抖与丢弃乱序响应）。
     *
     * <p>序号守卫是必要的：输入快时会有多个请求在飞，晚到的旧响应会把新结果覆盖掉。</p>
     */
    async runSearch(repoId: string, query: string): Promise<void> {
      if (!this.searches[repoId]) {
        this.searches[repoId] = emptySearch();
      }
      const state = this.searches[repoId];
      const seq = (this.searchSeq[repoId] ?? 0) + 1;
      this.searchSeq[repoId] = seq;

      const keyword = query.trim();
      state.error = null;
      if (!keyword) {
        state.loading = false;
        state.matches = [];
        state.truncated = false;
        return;
      }

      state.loading = true;
      try {
        const result = await filesApi.search(repoId, keyword, this.ensure(repoId).showIgnored);
        if (this.searchSeq[repoId] !== seq) {
          return;
        }
        state.matches = result.matches;
        state.truncated = result.truncated;
      } catch (error) {
        if (this.searchSeq[repoId] !== seq) {
          return;
        }
        state.error = error instanceof Error ? error.message : String(error);
        state.matches = [];
      } finally {
        if (this.searchSeq[repoId] === seq) {
          state.loading = false;
        }
      }
    },

    /** 仓库关闭时清理 */
    invalidate(repoId: string): void {
      delete this.trees[repoId];
      delete this.contents[repoId];
      delete this.searches[repoId];
      delete this.searchSeq[repoId];
      delete this.opened[repoId];
    },
  },
});

export type { RepoFileEntry };
