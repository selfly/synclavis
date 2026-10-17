import type { BranchInfo } from '@/types/repo';

/**
 * 远程仓库的纯函数：表单校验与侧栏分组。
 *
 * 校验规则必须与后端 `GitRemoteService#validateName / validateUrl` 保持一致，
 * 前端只做即时提示，最终以后端为准。
 */

/** 远程名合法：非空、不以 `-` 开头、不含 `..`/`@{`/空白/`/`/`~^:?*[\`。 */
export function isValidRemoteName(name: string): boolean {
  const value = name.trim();
  if (value.length === 0) {
    return false;
  }
  if (value.startsWith('-')) {
    return false;
  }
  if (value.includes('..') || value.includes('@{')) {
    return false;
  }
  return !/[\s/~^:?*[\\]/.test(value);
}

/** URL 合法：scp 形式 / 带 scheme 的地址 / 本地绝对路径，且不含空白。 */
export function isValidRemoteUrl(url: string): boolean {
  const value = url.trim();
  if (value.length === 0) {
    return false;
  }
  if (/\s/.test(value)) {
    return false;
  }
  if (value.startsWith('/') || value.startsWith('~')) {
    return true;
  }
  if (/^[A-Za-z]:[\\/]/.test(value)) {
    return true;
  }
  return /^(?:[a-zA-Z][a-zA-Z0-9+.-]*:\/\/.+|[^/@\s]+@[^/\s:]+:.+)$/.test(value);
}

export interface RemoteBranchGroup {
  /** remote 名；匹配不到任何 remote 的分支归入「其他」 */
  name: string;
  branches: BranchInfo[];
}

/**
 * 按 remote 名分组：用**完整 remote 名**做前缀匹配，
 * 所以 remote 名本身含 `/`（如 `team/repo`）也不会歧义；组内按分支名排序。
 */
export function groupRemoteBranches(
  branches: BranchInfo[],
  remoteNames: string[],
): RemoteBranchGroup[] {
  // 长名优先，避免 `team` 抢走 `team/repo/xxx` 的分支
  const owners = [...remoteNames].sort((left, right) => right.length - left.length);
  const groups = new Map<string, BranchInfo[]>();
  for (const name of remoteNames) {
    groups.set(name, []);
  }

  const others: BranchInfo[] = [];
  for (const branch of branches) {
    const owner = owners.find((name) => branch.name.startsWith(`${name}/`));
    if (owner) {
      groups.get(owner)?.push(branch);
    } else {
      others.push(branch);
    }
  }

  const sortByName = (list: BranchInfo[]): BranchInfo[] =>
    [...list].sort((left, right) => left.name.localeCompare(right.name));

  const result: RemoteBranchGroup[] = [...remoteNames]
    .sort((left, right) => left.localeCompare(right))
    .map((name) => ({ name, branches: sortByName(groups.get(name) ?? []) }))
    .filter((group) => group.branches.length > 0);

  if (others.length > 0) {
    result.push({ name: '其他', branches: sortByName(others) });
  }
  return result;
}

/** 远程分支的短名：`origin/main` → `main`。 */
export function shortRemoteBranchName(fullName: string, remoteName: string): string {
  const prefix = `${remoteName}/`;
  return fullName.startsWith(prefix) ? fullName.slice(prefix.length) : fullName;
}
