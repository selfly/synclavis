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
    logFilter: {} as Record<string, LogFilter>,
  }),

  getters: {
    detail(state) {
      return (repoId: string): RepoDetail => state.details[repoId] ?? emptyDetail();
    },
    selected(state) {
      return (repoId: string): string | null => state.selectedCommit[repoId] ?? null;
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
      this.selectedCommit[repoId] = commitId;
      await this.loadDiff(repoId, commitId);
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
    },

    /** 写操作后清理缓存 */
    invalidate(repoId: string): void {
      this.details[repoId] = emptyDetail();
      this.selectedCommit[repoId] = null;
    },
  },
});
