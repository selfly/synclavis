import { describe, expect, it } from 'vitest';
import {
  groupRemoteBranches,
  isValidRemoteName,
  isValidRemoteUrl,
  shortRemoteBranchName,
} from '@/utils/remotes';
import type { BranchInfo } from '@/types/repo';

function branch(name: string): BranchInfo {
  return {
    name,
    fullName: `refs/remotes/${name}`,
    local: false,
    current: false,
    ahead: 0,
    behind: 0,
  };
}

describe('isValidRemoteName', () => {
  it('接受常规名字并裁掉首尾空白', () => {
    expect(isValidRemoteName('origin')).toBe(true);
    expect(isValidRemoteName('upstream')).toBe(true);
    expect(isValidRemoteName('my-remote_1')).toBe(true);
    expect(isValidRemoteName('  origin  ')).toBe(true);
  });

  it('拒绝空值与含空白的名字', () => {
    expect(isValidRemoteName('')).toBe(false);
    expect(isValidRemoteName('   ')).toBe(false);
    expect(isValidRemoteName('bad name')).toBe(false);
  });

  it('拒绝以 - 开头、含 /、..、@{ 与 ref 特殊字符', () => {
    expect(isValidRemoteName('-origin')).toBe(false);
    expect(isValidRemoteName('a/b')).toBe(false);
    expect(isValidRemoteName('a..b')).toBe(false);
    expect(isValidRemoteName('a@{b')).toBe(false);
    expect(isValidRemoteName('a~b')).toBe(false);
    expect(isValidRemoteName('a^b')).toBe(false);
    expect(isValidRemoteName('a:b')).toBe(false);
    expect(isValidRemoteName('a?b')).toBe(false);
    expect(isValidRemoteName('a*b')).toBe(false);
    expect(isValidRemoteName('a[b')).toBe(false);
    expect(isValidRemoteName('a\\b')).toBe(false);
  });
});

describe('isValidRemoteUrl', () => {
  it('接受 scheme / scp 形式 / 本地绝对路径', () => {
    expect(isValidRemoteUrl('https://github.com/a/b.git')).toBe(true);
    expect(isValidRemoteUrl('ssh://git@host:22/a/b.git')).toBe(true);
    expect(isValidRemoteUrl('git@github.com:a/b.git')).toBe(true);
    expect(isValidRemoteUrl('git://host/a.git')).toBe(true);
    expect(isValidRemoteUrl('file:///srv/git/a.git')).toBe(true);
    expect(isValidRemoteUrl('/srv/git/a.git')).toBe(true);
    expect(isValidRemoteUrl('~/git/a.git')).toBe(true);
    expect(isValidRemoteUrl('  /srv/git/a.git  ')).toBe(true);
  });

  it('接受 Windows 盘符路径', () => {
    expect(isValidRemoteUrl('C:\\git\\a.git')).toBe(true);
    expect(isValidRemoteUrl('D:/git/a.git')).toBe(true);
  });

  it('拒绝空值与含空白的裸串', () => {
    expect(isValidRemoteUrl('')).toBe(false);
    expect(isValidRemoteUrl('   ')).toBe(false);
    expect(isValidRemoteUrl('not a url')).toBe(false);
    expect(isValidRemoteUrl('relative/path')).toBe(false);
  });
});

describe('groupRemoteBranches', () => {
  it('按 remote 前缀分组，组内按名排序', () => {
    const groups = groupRemoteBranches(
      [branch('origin/main'), branch('origin/dev'), branch('upstream/main')],
      ['origin', 'upstream'],
    );
    expect(groups.map((group) => group.name)).toEqual(['origin', 'upstream']);
    expect(groups[0].branches.map((item) => item.name)).toEqual(['origin/dev', 'origin/main']);
    expect(groups[1].branches.map((item) => item.name)).toEqual(['upstream/main']);
  });

  it('remote 名含 / 时按完整名匹配，不被短前缀抢走', () => {
    const groups = groupRemoteBranches(
      [branch('team/repo/main'), branch('team/other')],
      ['team', 'team/repo'],
    );
    // 长名优先：team/repo/main 归 team/repo，team/other 归 team
    expect(
      groups.find((group) => group.name === 'team/repo')?.branches.map((item) => item.name),
    ).toEqual(['team/repo/main']);
    expect(groups.find((group) => group.name === 'team')?.branches.map((item) => item.name)).toEqual(
      ['team/other'],
    );
  });

  it('匹配不到的分支归入「其他」，空组被过滤', () => {
    const groups = groupRemoteBranches([branch('stray/main')], ['origin', 'upstream']);
    expect(groups.map((group) => group.name)).toEqual(['其他']);
    expect(groups[0].branches.map((item) => item.name)).toEqual(['stray/main']);
  });

  it('没有任何 remote 时全部归入「其他」', () => {
    const groups = groupRemoteBranches([branch('origin/main')], []);
    expect(groups).toHaveLength(1);
    expect(groups[0].name).toBe('其他');
  });

  it('没有远程分支时返回空数组', () => {
    expect(groupRemoteBranches([], ['origin'])).toEqual([]);
  });
});

describe('shortRemoteBranchName', () => {
  it('去掉 remote 前缀', () => {
    expect(shortRemoteBranchName('origin/main', 'origin')).toBe('main');
    expect(shortRemoteBranchName('origin/feature/x', 'origin')).toBe('feature/x');
  });

  it('前缀不匹配时原样返回', () => {
    expect(shortRemoteBranchName('upstream/main', 'origin')).toBe('upstream/main');
  });
});
