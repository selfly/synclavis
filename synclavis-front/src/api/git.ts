import { http } from '@/api/http';
import type {
  BranchInfo,
  Commit,
  DiffResponse,
  LogResponse,
  RebaseStatus,
  RepoStatus,
  StashInfo,
  TagInfo,
} from '@/types/repo';

export interface LogQuery {
  skip?: number;
  limit?: number;
  author?: string;
  q?: string;
  ref?: string;
}

function toQuery(query: LogQuery): string {
  const parts: string[] = [];
  if (query.skip !== undefined) {
    parts.push(`skip=${query.skip}`);
  }
  if (query.limit !== undefined) {
    parts.push(`limit=${query.limit}`);
  }
  if (query.author) {
    parts.push(`author=${encodeURIComponent(query.author)}`);
  }
  if (query.q) {
    parts.push(`q=${encodeURIComponent(query.q)}`);
  }
  if (query.ref) {
    parts.push(`ref=${encodeURIComponent(query.ref)}`);
  }
  return parts.length > 0 ? `?${parts.join('&')}` : '';
}

/** 仓库只读查询与暂存/提交接口（见《详细设计文档》§6.2） */
export const gitApi = {
  status: (repoId: string) => http.get<RepoStatus>(`/api/repos/${repoId}/status`),

  branches: (repoId: string) => http.get<BranchInfo[]>(`/api/repos/${repoId}/branches`),

  tags: (repoId: string) => http.get<TagInfo[]>(`/api/repos/${repoId}/tags`),

  log: (repoId: string, query: LogQuery = {}) =>
    http.get<LogResponse>(`/api/repos/${repoId}/log${toQuery(query)}`),

  commitDiff: (repoId: string, commitId: string, path?: string) =>
    http.get<DiffResponse>(
      `/api/repos/${repoId}/commits/${commitId}/diff${path ? `?path=${encodeURIComponent(path)}` : ''}`,
    ),

  stashes: (repoId: string) => http.get<StashInfo[]>(`/api/repos/${repoId}/stash`),

  stashCreate: (repoId: string, body: { includeUntracked?: boolean; message?: string } = {}) =>
    http.post<StashInfo>(`/api/repos/${repoId}/stash`, body),

  stashApply: (repoId: string, index: number) =>
    http.post<{ index: number; action: string }>(`/api/repos/${repoId}/stash/${index}/apply`),

  stashDrop: (repoId: string, index: number) =>
    http.post<{ index: number; action: string }>(`/api/repos/${repoId}/stash/${index}/drop`),

  stashPop: (repoId: string, index: number) =>
    http.post<{ index: number; action: string }>(`/api/repos/${repoId}/stash/${index}/pop`),

  stage: (repoId: string, paths: string[]) =>
    http.post<{ staged: number }>(`/api/repos/${repoId}/stage`, { paths }),

  unstage: (repoId: string, paths: string[]) =>
    http.post<{ unstaged: number }>(`/api/repos/${repoId}/unstage`, { paths }),

  commit: (repoId: string, message: string, amend = false, files?: string[]) =>
    http.post<{ id: string; subject: string }>(`/api/repos/${repoId}/commit`, {
      message,
      amend,
      files: files ?? null,
    }),

  rebaseStatus: (repoId: string) =>
    http.get<RebaseStatus>(`/api/repos/${repoId}/rebase/status`),

  rebaseContinue: (repoId: string) =>
    http.post<{ action: string }>(`/api/repos/${repoId}/rebase/continue`),

  rebaseSkip: (repoId: string) =>
    http.post<{ action: string }>(`/api/repos/${repoId}/rebase/skip`),

  rebaseAbort: (repoId: string) =>
    http.post<{ action: string }>(`/api/repos/${repoId}/rebase/abort`),
};

export type { Commit };
