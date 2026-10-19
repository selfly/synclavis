import { http } from '@/api/http';
import type { RemoteInfo } from '@/types/repo';

/**
 * 远程仓库管理（见《详细设计文档》§6.2「远程仓库」）。
 *
 * 增 / 改 / 删都返回**更新后的完整列表**，调用方直接整体替换即可。
 */
export const remotesApi = {
  list: (repoId: string) => http.get<RemoteInfo[]>(`/api/repos/${repoId}/remotes`),

  add: (repoId: string, name: string, url: string) =>
    http.post<RemoteInfo[]>(`/api/repos/${repoId}/remotes`, { name, url }),

  updateUrl: (repoId: string, name: string, url: string) =>
    http.put<RemoteInfo[]>(`/api/repos/${repoId}/remotes/${encodeURIComponent(name)}`, { url }),

  remove: (repoId: string, name: string) =>
    http.del<RemoteInfo[]>(`/api/repos/${repoId}/remotes/${encodeURIComponent(name)}`),
};
