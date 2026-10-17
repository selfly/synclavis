import { http } from '@/api/http';
import type { Commit } from '@/types/repo';
import type { OperationHandleDto } from '@/types/api';

export interface FetchBody {
  remote?: string | null;
  all?: boolean;
  prune?: boolean;
  tags?: boolean;
}

export type PullStrategyName = 'merge' | 'rebase' | 'ff-only';

export interface PullBody {
  strategy: PullStrategyName;
  remote?: string | null;
  branch?: string | null;
}

export interface PushBody {
  setUpstream?: boolean;
  tags?: boolean;
  remote?: string | null;
  branch?: string | null;
}

/** 推送预览：本次 push 会推上去的提交（见《详细设计文档》§6.2） */
export interface PushPreview {
  remote: string;
  branch: string;
  targetRef: string;
  /** 上游跟踪名（如 `origin/main`），未设置 upstream 时为 null */
  upstream: string | null;
  /** `ahead` 的基准引用；null 表示「不在任何远程分支上的提交」（全新分支首次推送） */
  base: string | null;
  ahead: number;
  /** 远端领先数；> 0 说明非快进，推送必被拒 */
  behind: number;
  /** 待推送提交（新→旧），至多 100 条；少于 `ahead` 时说明被截断 */
  commits: Commit[];
}

/** 写操作接口：均返回 operationId，结果经 WebSocket 推送 */
export const operationsApi = {
  fetch: (repoId: string, body: FetchBody = {}) =>
    http.post<OperationHandleDto>(`/api/repos/${repoId}/fetch`, body),

  pull: (repoId: string, body: PullBody) =>
    http.post<OperationHandleDto>(`/api/repos/${repoId}/pull`, body),

  push: (repoId: string, body: PushBody = {}) =>
    http.post<OperationHandleDto>(`/api/repos/${repoId}/push`, body),

  /** 推送预览（只读同步查询） */
  pushPreview: (repoId: string, query: { remote?: string; branch?: string } = {}) => {
    const search = new URLSearchParams();
    if (query.remote) {
      search.set('remote', query.remote);
    }
    if (query.branch) {
      search.set('branch', query.branch);
    }
    const suffix = search.toString();
    return http.get<PushPreview>(`/api/repos/${repoId}/push/preview${suffix ? `?${suffix}` : ''}`);
  },
};
