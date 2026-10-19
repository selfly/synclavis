import { http } from '@/api/http';

/** 仓库文件树条目（见《详细设计文档》§6.2「仓库文件浏览（只读）」） */
export interface RepoFileEntry {
  name: string;
  /** 仓库内相对路径，统一 `/` 分隔 */
  path: string;
  dir: boolean;
  size: number;
  /** 命中 .gitignore（仅 showIgnored=true 时才会返回） */
  ignored: boolean;
  symlink: boolean;
}

export interface RepoFileList {
  /** 当前目录相对路径，仓库根为空串 */
  path: string;
  entries: RepoFileEntry[];
  truncated: boolean;
}

export interface RepoFileContent {
  path: string;
  size: number;
  binary: boolean;
  tooLarge: boolean;
  encoding: string | null;
  content: string | null;
}

/** 搜索结果：扁平的文件列表（能命中尚未展开的目录） */
export interface RepoFileSearch {
  /** 已规范化的关键字 */
  query: string;
  matches: RepoFileEntry[];
  truncated: boolean;
}

/** 仓库内文件浏览（只读） */
export const filesApi = {
  list: (repoId: string, path: string, showIgnored: boolean) => {
    const params = new URLSearchParams();
    if (path) {
      params.set('path', path);
    }
    if (showIgnored) {
      params.set('showIgnored', 'true');
    }
    const query = params.toString();
    return http.get<RepoFileList>(`/api/repos/${repoId}/files${query ? `?${query}` : ''}`);
  },

  content: (repoId: string, path: string) =>
    http.get<RepoFileContent>(
      `/api/repos/${repoId}/files/content?path=${encodeURIComponent(path)}`,
    ),

  search: (repoId: string, query: string, showIgnored: boolean) => {
    const params = new URLSearchParams({ q: query });
    if (showIgnored) {
      params.set('showIgnored', 'true');
    }
    return http.get<RepoFileSearch>(`/api/repos/${repoId}/files/search?${params.toString()}`);
  },
};
