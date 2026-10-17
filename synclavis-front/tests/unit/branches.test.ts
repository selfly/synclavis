import { describe, expect, it } from 'vitest';
import { extractConflictFiles, isValidBranchName } from '@/utils/branches';

describe('isValidBranchName', () => {
  it('接受常规分支名并裁掉首尾空白', () => {
    expect(isValidBranchName('main')).toBe(true);
    expect(isValidBranchName('feature/login')).toBe(true);
    expect(isValidBranchName('release-1.0')).toBe(true);
    expect(isValidBranchName('origin/dev')).toBe(true);
    expect(isValidBranchName('  feature/login  ')).toBe(true);
  });

  it('允许含 / ——与 remote 名校验规则不同', () => {
    expect(isValidBranchName('a/b/c')).toBe(true);
    // 同样的名字对 remote 是非法（remote 禁用 /）
    expect(isValidBranchName('team/repo')).toBe(true);
  });

  it('拒绝空值、HEAD、@ 与以 - 开头', () => {
    expect(isValidBranchName('')).toBe(false);
    expect(isValidBranchName('   ')).toBe(false);
    expect(isValidBranchName('HEAD')).toBe(false);
    expect(isValidBranchName('@')).toBe(false);
    expect(isValidBranchName('-foo')).toBe(false);
  });

  it('拒绝空白与 ref 特殊字符', () => {
    expect(isValidBranchName('bad name')).toBe(false);
    expect(isValidBranchName('a~b')).toBe(false);
    expect(isValidBranchName('a^b')).toBe(false);
    expect(isValidBranchName('a:b')).toBe(false);
    expect(isValidBranchName('a?b')).toBe(false);
    expect(isValidBranchName('a*b')).toBe(false);
    expect(isValidBranchName('a[b')).toBe(false);
    expect(isValidBranchName('a\\b')).toBe(false);
    expect(isValidBranchName('a\tb')).toBe(false);
  });

  it('拒绝 .. / @{ / // / .lock / 首尾斜杠', () => {
    expect(isValidBranchName('a..b')).toBe(false);
    expect(isValidBranchName('a@{1}')).toBe(false);
    expect(isValidBranchName('a//b')).toBe(false);
    expect(isValidBranchName('/a')).toBe(false);
    expect(isValidBranchName('a/')).toBe(false);
    expect(isValidBranchName('a.lock')).toBe(false);
  });

  it('拒绝任一层级以 . 开头或结尾', () => {
    expect(isValidBranchName('.foo')).toBe(false);
    expect(isValidBranchName('foo.')).toBe(false);
    expect(isValidBranchName('a/.b')).toBe(false);
    expect(isValidBranchName('a/b.')).toBe(false);
  });
});

describe('extractConflictFiles', () => {
  it('从 details.files 取出路径数组', () => {
    const error = { code: 'CHECKOUT_CONFLICT', details: { files: ['README.md', 'a/b.txt'] } };
    expect(extractConflictFiles(error)).toEqual(['README.md', 'a/b.txt']);
  });

  it('非数组 / 缺 details / 非对象时返回空数组', () => {
    expect(extractConflictFiles({ details: { files: 'README.md' } })).toEqual([]);
    expect(extractConflictFiles({ details: null })).toEqual([]);
    expect(extractConflictFiles({ code: 'INTERNAL' })).toEqual([]);
    expect(extractConflictFiles(new Error('boom'))).toEqual([]);
    expect(extractConflictFiles(null)).toEqual([]);
    expect(extractConflictFiles(undefined)).toEqual([]);
  });

  it('把非字符串元素转成字符串', () => {
    expect(extractConflictFiles({ details: { files: [1, 'b'] } })).toEqual(['1', 'b']);
  });
});
