/** 仓库相关数据模型（与后端契约一一对应） */
export type PullStrategy = 'merge' | 'rebase' | 'ff-only';

/**
 * 仓库状态枚举。
 *
 * 注意：取值为 JGit 5.x `org.eclipse.jgit.lib.RepositoryState` 的真实常量名
 * （是 SAFE / MERGING / REBASING_REBASING，而不是 CLEAN / MERGE / REBASING_REBASE）。
 */
export type RepoState =
  | 'BARE'
  | 'SAFE'
  | 'MERGING'
  | 'MERGING_RESOLVED'
  | 'CHERRY_PICKING'
  | 'CHERRY_PICKING_RESOLVED'
  | 'REVERTING'
  | 'REVERTING_RESOLVED'
  | 'REBASING'
  | 'REBASING_REBASING'
  | 'REBASING_MERGE'
  | 'REBASING_INTERACTIVE'
  | 'APPLY'
  | 'BISECTING';

export type ChangeType = 'ADD' | 'MODIFY' | 'DELETE' | 'RENAME' | 'COPY';

export interface FileChange {
  path: string;
  changeType: ChangeType;
  oldPath?: string;
}

export interface RepoStatus {
  branch: string | null;
  detached: boolean;
  ahead: number;
  behind: number;
  staged: FileChange[];
  unstaged: FileChange[];
  untracked: string[];
  conflicts: string[];
  remotes: string[];
  hasUpstream: boolean;
  upstream: string | null;
  rebaseInProgress: boolean;
  repositoryState: RepoState;
}

export interface RepoSummary {
  id: string;
  path: string;
  name: string;
  sshKeyPath: string | null;
  pullStrategy: PullStrategy;
}

/** 远程仓库配置（见《详细设计文档》§6.2「远程仓库」） */
export interface RemoteInfo {
  name: string;
  url: string;
  /** 只读：remote.<name>.pushurl 有配置时非空 */
  pushUrl: string | null;
}

export interface BranchInfo {
  name: string;
  fullName: string;
  local: boolean;
  current: boolean;
  ahead: number;
  behind: number;
}

export interface TagInfo {
  name: string;
  commitId: string;
  message: string | null;
}

export interface StashInfo {
  index: number;
  message: string;
  commitId: string;
  time: string;
}

export interface Commit {
  id: string;
  shortId: string;
  parents: string[];
  author: string;
  email: string;
  time: string;
  subject: string;
  refs: string[];
}

export interface DiffEntry {
  path: string;
  oldPath: string | null;
  changeType: ChangeType;
  insertions: number;
  deletions: number;
  binary: boolean;
}

export interface DiffResponse {
  entries: DiffEntry[];
  patch: string | null;
}

export interface LogResponse {
  total: number;
  commits: Commit[];
}

export interface RebaseStatus {
  inProgress: boolean;
  state: RepoState;
}

export const REBASING_STATES: RepoState[] = [
  'REBASING',
  'REBASING_REBASING',
  'REBASING_MERGE',
  'REBASING_INTERACTIVE',
];
