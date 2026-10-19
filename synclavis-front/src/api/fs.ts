import { http } from '@/api/http';

export type FsKind = 'dir' | 'file';

export interface FsEntryDto {
  name: string;
  path: string;
  dir: boolean;
  repo: boolean;
  hidden: boolean;
}

export interface FsBreadcrumbDto {
  name: string;
  path: string;
}

export interface FsListDto {
  path: string;
  parent: string | null;
  roots: string[];
  breadcrumbs: FsBreadcrumbDto[];
  entries: FsEntryDto[];
  truncated: boolean;
}

/** 只读目录浏览（见《详细设计文档》§6.2「文件系统（只读）」） */
export const fsApi = {
  list: (path?: string | null, kind: FsKind = 'dir') => {
    const params = new URLSearchParams();
    const trimmed = path?.trim() ?? '';
    if (trimmed.length > 0) {
      params.set('path', trimmed);
    }
    params.set('kind', kind);
    return http.get<FsListDto>(`/api/fs/list?${params.toString()}`);
  },
};
