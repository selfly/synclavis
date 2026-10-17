import { defineStore } from 'pinia';
import { gitApi, type LogQuery } from '@/api/git';
import type {
  BranchInfo,
  Commit,
  DiffResponse,
  RepoStatus,
  StashInfo,
  TagInfo,
} from '@/types/repo';

export interface LogFilter {
  author: string;
  q: string;
  ref: string;
}

/** 选中的工作区文件：`staged` 区分「已暂存」与「未暂存/未跟踪」两个分区 */
export interface WorktreeFileRef {
  path: string;
  staged: boolean;
}

export interface RepoDetail {
  status: RepoStatus | null;
  branches: BranchInfo[];
  tags: TagInfo[];
  stashes: StashInfo[];
  commits: Commit[];
  total: number;
  diff: DiffResponse | null;
  loading: boolean;
}

function emptyDetail(): RepoDetail {
  return {
    status: null,
    branches: [],
    tags: [],
    stashes: [],
    commits: [],
    total: 0,
    diff: null,
    loading: false,
  };
}

function emptyFilter(): LogFilter {
  return { author: '', q: '', ref: '' };
}

export const useReposStore = defineStore('repos', {
  state: () => ({
    details: {} as Record<string, RepoDetail>,
    selectedCommit: {} as Record<string, string | null>,
    /** 与 selectedCommit 互斥：选提交看提交差异，选文件看工作区差异 */
    selectedFile: {} as Record<string, WorktreeFileRef | null>,
    logFilter: {} as Record<string, LogFilter>,
  }),

  getters: {
    detail(state) {
      return (repoId: string): RepoDetail => state.details[repoId] ?? emptyDetail();
    },
    selected(state) {
      return (repoId: string): string | null => state.selectedCommit[repoId] ?? null;
    },
    worktreeFile(state) {
      return (repoId: string): WorktreeFileRef | null => state.selectedFile[repoId] ?? null;
    },
    filter(state) {
      return (repoId: string): LogFilter => state.logFilter[repoId] ?? emptyFilter();
    },
  },

  actions: {
    ensure(repoId: string): RepoDetail {
      const existing = this.details[repoId];
      if (existing) {
        return existing;
      }
      const created = emptyDetail();
      this.details[repoId] = created;
      return created;
    },

    async refreshStatus(repoId: string): Promise<RepoStatus> {
      const detail = this.ensure(repoId);
      const status = await gitApi.status(repoId);
      detail.status = status;
      return status;
    },

    /** 由 WebSocket status 事件写入 */
    setStatus(repoId: string, status: RepoStatus): void {
      this.ensure(repoId).status = status;
    },

    /** 写操作成功后刷新该仓库的可见数据 */
    async refreshAfterOperation(repoId: string): Promise<void> {
      try {
        await this.refreshAll(repoId);
      } catch {
        // 刷新失败不覆盖操作本身的成功结果
      }
    },

    /**
     * 一次性刷新工作区相关数据（打开/切换仓库时调用）。
     *
     * <p>用 allSettled 而非 all：任一接口失败（例如某些仓库的边界情况）不应把
     * 其它已成功的数据一起清空，否则整个工作区会变成空白。</p>
     */
    async refreshAll(repoId: string): Promise<void> {
      const detail = this.ensure(repoId);
      detail.loading = true;
      try {
        const [status, branches, tags, stashes, log] = await Promise.allSettled([
          gitApi.status(repoId),
          gitApi.branches(repoId),
          gitApi.tags(repoId),
          gitApi.stashes(repoId),
          gitApi.log(repoId, { limit: 100, ...this.toQueryFilter(repoId) }),
        ]);
        if (status.status === 'fulfilled') {
          detail.status = status.value;
        }
        if (branches.status === 'fulfilled') {
          detail.branches = branches.value;
        }
        if (tags.status === 'fulfilled') {
          detail.tags = tags.value;
        }
        if (stashes.status === 'fulfilled') {
          detail.stashes = stashes.value;
        }
        if (log.status === 'fulfilled') {
          detail.commits = log.value.commits;
          detail.total = log.value.total;
        }
        // 选中的工作区文件：刷新后重新取一次，避免展示过期内容
        await this.resyncWorktreeSelection(repoId, true);
      } finally {
        detail.loading = false;
      }
    },

    async loadLog(repoId: string, query: LogQuery = {}): Promise<void> {
      const detail = this.ensure(repoId);
      const log = await gitApi.log(repoId, { limit: 100, ...query });
      detail.commits = log.commits;
      detail.total = log.total;
    },

    /** 加载下一页并追加（提交图「加载更多」）。 */
    async loadLogMore(repoId: string, pageSize = 100): Promise<boolean> {
      const detail = this.ensure(repoId);
      const skip = detail.commits.length;
      if (skip >= detail.total) {
        return false;
      }
      const log = await gitApi.log(repoId, {
        skip,
        limit: pageSize,
        ...this.toQueryFilter(repoId),
      });
      detail.commits = [...detail.commits, ...log.commits];
      detail.total = log.total;
      return true;
    },

    async applyFilter(repoId: string, patch: Partial<LogFilter>): Promise<void> {
      const current = this.logFilter[repoId] ?? emptyFilter();
      this.logFilter[repoId] = { ...current, ...patch };
      await this.loadLog(repoId, this.toQueryFilter(repoId));
    },

    toQueryFilter(repoId: string): LogQuery {
      const filter = this.logFilter[repoId] ?? emptyFilter();
      const query: LogQuery = {};
      if (filter.author) {
        query.author = filter.author;
      }
      if (filter.q) {
        query.q = filter.q;
      }
      if (filter.ref) {
        query.ref = filter.ref;
      }
      return query;
    },

    async selectCommit(repoId: string, commitId: string): Promise<void> {
      const detail = this.ensure(repoId);
      this.selectedCommit[repoId] = commitId;
      this.selectedFile[repoId] = null;
      // 先清空：切换来源时不要把上一个来源的 patch 挂在新标题下
      detail.diff = null;
      await this.loadDiff(repoId, commitId);
    },

    /**
     * 选中暂存区/工作区文件并加载其差异。
     *
     * <p>同一路径可能同时出现在两个分区（部分暂存），所以 `staged` 由点击的那个分区决定，
     * 不能用「已暂存优先」之类的猜测。</p>
     */
    async selectWorktreeFile(repoId: string, path: string, staged: boolean): Promise<void> {
      const detail = this.ensure(repoId);
      this.selectedFile[repoId] = { path, staged };
      this.selectedCommit[repoId] = null;
      detail.diff = null;
      detail.diff = await gitApi.worktreeDiff(repoId, path, staged);
    },

    /**
     * 索引/工作区变化后校正工作区文件选择。
     *
     * <p>暂存/取消暂存会把同一文件从一个分区换到另一个分区，提交则会让它彻底消失：
     * 前者要把选择与 Diff 跟着切过去，后者要清掉，避免展示过期差异。</p>
     *
     * @param force 即使分区没变也重新拉取（F5 之类的手动刷新语义）
     */
    async resyncWorktreeSelection(repoId: string, force = false): Promise<void> {
      const current = this.selectedFile[repoId];
      if (!current) {
        return;
      }
      const detail = this.ensure(repoId);
      const status = detail.status;
      const inStaged = status ? status.staged.some((file) => file.path === current.path) : false;
      const inUnstaged = status
        ? status.unstaged.some((file) => file.path === current.path)
              || status.untracked.includes(current.path)
        : false;

      let nextStaged: boolean | null = null;
      if (inStaged && inUnstaged) {
        // 两侧都有（部分暂存）：尊重用户点的那个分区
        nextStaged = current.staged;
      } else if (inStaged) {
        nextStaged = true;
      } else if (inUnstaged) {
        nextStaged = false;
      }

      if (nextStaged === null) {
        this.selectedFile[repoId] = null;
        detail.diff = null;
        return;
      }
      if (nextStaged === current.staged && !force) {
        return;
      }
      this.selectedFile[repoId] = { path: current.path, staged: nextStaged };
      detail.diff = await gitApi.worktreeDiff(repoId, current.path, nextStaged);
    },

    async loadDiff(repoId: string, commitId: string, path?: string): Promise<void> {
      const detail = this.ensure(repoId);
      detail.diff = await gitApi.commitDiff(repoId, commitId, path);
    },

    async stage(repoId: string, paths: string[]): Promise<void> {
      await gitApi.stage(repoId, paths);
      await this.afterIndexChange(repoId);
    },

    async unstage(repoId: string, paths: string[]): Promise<void> {
      await gitApi.unstage(repoId, paths);
      await this.afterIndexChange(repoId);
    },

    async commit(repoId: string, message: string, amend = false): Promise<void> {
      await gitApi.commit(repoId, message, amend);
      await this.afterIndexChange(repoId);
    },

    async afterIndexChange(repoId: string): Promise<void> {
      const detail = this.ensure(repoId);
      detail.status = await gitApi.status(repoId);
      await this.loadLog(repoId, this.toQueryFilter(repoId));
      await this.resyncWorktreeSelection(repoId);
    },

    /** 写操作后清理缓存 */
    invalidate(repoId: string): void {
      this.details[repoId] = emptyDetail();
      this.selectedCommit[repoId] = null;
      this.selectedFile[repoId] = null;
    },
  },
});
