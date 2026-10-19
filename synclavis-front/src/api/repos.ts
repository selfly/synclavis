import { http } from '@/api/http';
import type { PullStrategy } from '@/types/repo';

export interface SessionTabDto {
  id: string;
  path: string;
  name: string;
  sshKeyPath: string | null;
  pullStrategy: PullStrategy;
  order: number;
}

export interface SessionDto {
  activeId: string | null;
  tabs: SessionTabDto[];
}

export interface RecentRepoDto {
  path: string;
  name: string;
  lastOpenedAt: string;
}

export interface RepoSettingsDto {
  sshKeyPath: string | null;
  pullStrategy: PullStrategy;
  httpsUsername: string | null;
  hasHttpsPassword: boolean;
}

/** 更新仓库设置：httpsPassword 为 null 表示不改动，空串表示清除 */
export interface UpdateRepoSettingsBody {
  sshKeyPath?: string | null;
  pullStrategy?: PullStrategy;
  httpsUsername?: string | null;
  httpsPassword?: string | null;
}

export interface CloneRepoBody {
  url: string;
  path: string;
  sshKeyPath?: string | null;
  httpsUsername?: string | null;
  httpsPassword?: string | null;
}

/** 会话与仓库相关接口（见《详细设计文档》§6.2） */
export const reposApi = {
  session: () => http.get<SessionDto>('/api/session'),

  setActive: (repoId: string | null) => http.put<SessionDto>('/api/session/active', { repoId }),

  /** 重排 Tab：ids 必须是当前全部 Tab id 的一个排列 */
  reorder: (ids: string[]) => http.put<SessionDto>('/api/session/order', { ids }),

  recent: (limit = 10) => http.get<RecentRepoDto[]>(`/api/repos/recent?limit=${limit}`),

  open: (path: string, sshKeyPath?: string | null) =>
    http.post<SessionTabDto>('/api/repos/open', { path, sshKeyPath: sshKeyPath ?? null }),

  /** 克隆：异步，返回 operationId；进度走全局事件流 /ws/events */
  clone: (body: CloneRepoBody) =>
    http.post<{ operationId: string }>('/api/repos/clone', body),

  close: (repoId: string) => http.del<{ id: string }>(`/api/repos/${repoId}`),

  getSettings: (repoId: string) => http.get<RepoSettingsDto>(`/api/repos/${repoId}/settings`),

  updateSettings: (repoId: string, patch: UpdateRepoSettingsBody) =>
    http.put<RepoSettingsDto>(`/api/repos/${repoId}/settings`, patch),
};
