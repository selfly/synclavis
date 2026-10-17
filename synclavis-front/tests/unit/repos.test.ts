import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';

const mocks = vi.hoisted(() => ({
  status: vi.fn(),
  branches: vi.fn(),
  tags: vi.fn(),
  stashes: vi.fn(),
  log: vi.fn(),
  commitDiff: vi.fn(),
  stage: vi.fn(),
  unstage: vi.fn(),
  commit: vi.fn(),
  worktreeDiff: vi.fn(),
}));

vi.mock('@/api/git', () => ({ gitApi: mocks }));

import { useReposStore } from '@/stores/repos';
import type { RepoStatus } from '@/types/repo';

const REPO = 'repo-1';

function statusFixture(overrides: Partial<RepoStatus> = {}): RepoStatus {
  return {
    branch: 'main',
    detached: false,
    ahead: 0,
    behind: 0,
    staged: [],
    unstaged: [],
    untracked: [],
    conflicts: [],
    remotes: [],
    hasUpstream: false,
    upstream: null,
    rebaseInProgress: false,
    repositoryState: 'SAFE',
    ...overrides,
  };
}

describe('repos store', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    Object.values(mocks).forEach((fn) => fn.mockReset());
  });

  it('refreshAll 并行填充 status/branches/tags/stashes/commits', async () => {
    mocks.status.mockResolvedValue(statusFixture({ ahead: 2 }));
    mocks.branches.mockResolvedValue([{ name: 'main', fullName: 'refs/heads/main', local: true, current: true, ahead: 2, behind: 0 }]);
    mocks.tags.mockResolvedValue([{ name: 'v1', commitId: 'abc', message: null }]);
    mocks.stashes.mockResolvedValue([{ index: 0, message: 'wip', commitId: 'def', time: '2024-01-01T00:00:00+08:00' }]);
    mocks.log.mockResolvedValue({ total: 1, commits: [{ id: 'abc', shortId: 'abc', parents: [], author: 'a', email: 'a@b', time: 't', subject: 's', refs: [] }] });

    const store = useReposStore();
    await store.refreshAll(REPO);

    const detail = store.detail(REPO);
    expect(detail.status?.ahead).toBe(2);
    expect(detail.branches).toHaveLength(1);
    expect(detail.tags).toHaveLength(1);
    expect(detail.stashes).toHaveLength(1);
    expect(detail.commits).toHaveLength(1);
    expect(detail.loading).toBe(false);
  });

  it('refreshAll 部分失败不影响其他数据，并复位 loading（allSettled）', async () => {
    mocks.status.mockRejectedValue(new Error('boom'));
    mocks.branches.mockResolvedValue([
      { name: 'main', fullName: 'refs/heads/main', local: true, current: true, ahead: 0, behind: 0 },
    ]);
    mocks.tags.mockResolvedValue([]);
    mocks.stashes.mockResolvedValue([]);
    mocks.log.mockResolvedValue({
      total: 1,
      commits: [
        { id: 'a', shortId: 'a', parents: [], author: 'x', email: 'x@y', time: 't', subject: 's', refs: [] },
      ],
    });

    const store = useReposStore();
    await expect(store.refreshAll(REPO)).resolves.toBeUndefined();

    const detail = store.detail(REPO);
    expect(detail.status).toBeNull(); // 失败项保持未填充
    expect(detail.branches).toHaveLength(1); // 成功项照常填充
    expect(detail.commits).toHaveLength(1);
    expect(detail.total).toBe(1);
    expect(detail.loading).toBe(false);
  });

  it('selectCommit 记录选中并拉取 diff', async () => {
    mocks.commitDiff.mockResolvedValue({ entries: [{ path: 'a.txt', oldPath: null, changeType: 'ADD', insertions: 1, deletions: 0, binary: false }], patch: '@@ -0,0 +1 @@' });

    const store = useReposStore();
    await store.selectCommit(REPO, 'c0ffee');

    expect(store.selected(REPO)).toBe('c0ffee');
    expect(store.detail(REPO).diff?.entries[0].path).toBe('a.txt');
    expect(mocks.commitDiff).toHaveBeenCalledWith(REPO, 'c0ffee', undefined);
  });

  it('stage 之后刷新 status 与 log', async () => {
    mocks.stage.mockResolvedValue({ staged: 1 });
    mocks.status.mockResolvedValue(statusFixture());
    mocks.log.mockResolvedValue({ total: 0, commits: [] });

    const store = useReposStore();
    await store.stage(REPO, ['a.txt']);

    expect(mocks.stage).toHaveBeenCalledWith(REPO, ['a.txt']);
    expect(mocks.status).toHaveBeenCalledWith(REPO);
    expect(mocks.log).toHaveBeenCalled();
  });

  it('loadLogMore 在已取完时不发请求', async () => {
    mocks.log.mockResolvedValueOnce({ total: 1, commits: [{ id: 'a', shortId: 'a', parents: [], author: '', email: '', time: '', subject: '', refs: [] }] });

    const store = useReposStore();
    await store.loadLog(REPO);
    mocks.log.mockClear();

    const more = await store.loadLogMore(REPO);
    expect(more).toBe(false);
    expect(mocks.log).not.toHaveBeenCalled();
  });

  it('loadLogMore 追加下一页', async () => {
    mocks.log.mockResolvedValueOnce({
      total: 3,
      commits: [
        { id: 'a', shortId: 'a', parents: [], author: '', email: '', time: '', subject: '', refs: [] },
        { id: 'b', shortId: 'b', parents: [], author: '', email: '', time: '', subject: '', refs: [] },
      ],
    });
    const store = useReposStore();
    await store.loadLog(REPO);

    mocks.log.mockResolvedValueOnce({
      total: 3,
      commits: [{ id: 'c', shortId: 'c', parents: [], author: '', email: '', time: '', subject: '', refs: [] }],
    });
    const more = await store.loadLogMore(REPO);

    expect(more).toBe(true);
    expect(store.detail(REPO).commits.map((c) => c.id)).toEqual(['a', 'b', 'c']);
    expect(mocks.log).toHaveBeenLastCalledWith(REPO, expect.objectContaining({ skip: 2 }));
  });

  it('selectWorktreeFile 选中文件、清掉提交选择并加载工作区差异（F-22）', async () => {
    mocks.worktreeDiff.mockResolvedValue({
      entries: [{ path: 'a.txt', oldPath: null, changeType: 'MODIFY', insertions: 1, deletions: 0, binary: false }],
      patch: '@@ -1 +1 @@\n-old\n+new',
    });

    const store = useReposStore();
    await store.selectCommit(REPO, 'c0ffee');
    await store.selectWorktreeFile(REPO, 'a.txt', false);

    expect(store.worktreeFile(REPO)).toEqual({ path: 'a.txt', staged: false });
    expect(store.selected(REPO)).toBeNull(); // 两种来源互斥
    expect(mocks.worktreeDiff).toHaveBeenCalledWith(REPO, 'a.txt', false);
    expect(store.detail(REPO).diff?.entries[0].path).toBe('a.txt');
  });

  it('selectCommit 会清掉工作区文件选择（F-22）', async () => {
    mocks.worktreeDiff.mockResolvedValue({ entries: [], patch: null });
    mocks.commitDiff.mockResolvedValue({ entries: [], patch: null });

    const store = useReposStore();
    await store.selectWorktreeFile(REPO, 'a.txt', true);
    await store.selectCommit(REPO, 'c0ffee');

    expect(store.worktreeFile(REPO)).toBeNull();
    expect(store.selected(REPO)).toBe('c0ffee');
  });

  it('暂存后同一文件换到暂存区：选择与 Diff 跟着切换（F-23）', async () => {
    mocks.worktreeDiff.mockResolvedValue({ entries: [], patch: null });
    mocks.stage.mockResolvedValue({ staged: 1 });
    mocks.log.mockResolvedValue({ total: 0, commits: [] });

    const store = useReposStore();
    mocks.status.mockResolvedValue(statusFixture({ unstaged: [{ path: 'a.txt', changeType: 'MODIFY' }] }));
    await store.selectWorktreeFile(REPO, 'a.txt', false);
    expect(store.worktreeFile(REPO)).toEqual({ path: 'a.txt', staged: false });

    // 点「+」暂存后，该文件只存在于暂存区
    mocks.status.mockResolvedValue(statusFixture({ staged: [{ path: 'a.txt', changeType: 'MODIFY' }] }));
    mocks.worktreeDiff.mockClear();
    await store.stage(REPO, ['a.txt']);

    expect(store.worktreeFile(REPO)).toEqual({ path: 'a.txt', staged: true });
    expect(mocks.worktreeDiff).toHaveBeenCalledWith(REPO, 'a.txt', true);
  });

  it('提交后文件离开变更集：清空选择与 Diff，避免展示过期内容（F-23）', async () => {
    mocks.worktreeDiff.mockResolvedValue({ entries: [], patch: 'stale' });
    mocks.commit.mockResolvedValue({ id: 'abc', subject: 's' });
    mocks.log.mockResolvedValue({ total: 0, commits: [] });

    const store = useReposStore();
    mocks.status.mockResolvedValue(statusFixture({ staged: [{ path: 'a.txt', changeType: 'ADD' }] }));
    await store.selectWorktreeFile(REPO, 'a.txt', true);

    // 提交后工作区干净
    mocks.status.mockResolvedValue(statusFixture());
    await store.commit(REPO, 'msg', false);

    expect(store.worktreeFile(REPO)).toBeNull();
    expect(store.detail(REPO).diff).toBeNull();
  });

  it('部分暂存（两侧都有）尊重用户点击的分区，不擅自切换（F-23）', async () => {
    mocks.worktreeDiff.mockResolvedValue({ entries: [], patch: null });

    const store = useReposStore();
    mocks.status.mockResolvedValue(
      statusFixture({
        staged: [{ path: 'a.txt', changeType: 'MODIFY' }],
        unstaged: [{ path: 'a.txt', changeType: 'MODIFY' }],
      }),
    );
    // 真实流程里 status 先由 refreshAll 填充（文件行本身就来自 status）
    await store.refreshStatus(REPO);
    await store.selectWorktreeFile(REPO, 'a.txt', false);

    mocks.worktreeDiff.mockClear();
    await store.resyncWorktreeSelection(REPO);
    expect(store.worktreeFile(REPO)).toEqual({ path: 'a.txt', staged: false });
    expect(mocks.worktreeDiff).not.toHaveBeenCalled(); // 分区没变就不重复请求

    // force（F5 语义）才重新拉取
    await store.resyncWorktreeSelection(REPO, true);
    expect(mocks.worktreeDiff).toHaveBeenCalledWith(REPO, 'a.txt', false);
  });

  it('invalidate 清掉工作区文件选择（F-22）', async () => {
    mocks.worktreeDiff.mockResolvedValue({ entries: [], patch: null });

    const store = useReposStore();
    await store.selectWorktreeFile(REPO, 'a.txt', false);
    store.invalidate(REPO);

    expect(store.worktreeFile(REPO)).toBeNull();
  });

  it('applyFilter 把过滤条件带到日志请求', async () => {
    mocks.log.mockResolvedValue({ total: 0, commits: [] });
    const store = useReposStore();
    await store.applyFilter(REPO, { author: 'tester', q: 'fix' });

    expect(store.filter(REPO).author).toBe('tester');
    expect(mocks.log).toHaveBeenCalledWith(REPO, expect.objectContaining({ author: 'tester', q: 'fix' }));
  });
});
