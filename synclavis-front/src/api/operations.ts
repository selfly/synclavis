import { http } from '@/api/http';
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

/** 写操作接口：均返回 operationId，结果经 WebSocket 推送 */
export const operationsApi = {
  fetch: (repoId: string, body: FetchBody = {}) =>
    http.post<OperationHandleDto>(`/api/repos/${repoId}/fetch`, body),

  pull: (repoId: string, body: PullBody) =>
    http.post<OperationHandleDto>(`/api/repos/${repoId}/pull`, body),

  push: (repoId: string, body: PushBody = {}) =>
    http.post<OperationHandleDto>(`/api/repos/${repoId}/push`, body),
};
