import { beforeEach, describe, expect, it } from 'vitest';
import {
  buildFileTree,
  collectFilePaths,
  flattenVisible,
  readFileView,
  writeFileView,
  type FileTreeNode,
  type WorktreeFileItem,
} from '@/utils/fileTree';
import type { ChangeType } from '@/types/repo';

function item(path: string, changeType: ChangeType = 'MODIFY'): WorktreeFileItem {
  return { path, changeType };
}

function find(nodes: FileTreeNode[], path: string): FileTreeNode | undefined {
  for (const node of nodes) {
    if (node.path === path) {
      return node;
    }
    const hit = find(node.children, path);
    if (hit) {
      return hit;
    }
  }
  return undefined;
}

describe('buildFileTree（F-27）', () => {
  it('按 "/" 分层建树，目录聚合后代文件数', () => {
    const tree = buildFileTree([
      item('src/utils/layout.ts'),
      item('src/components/App.vue'),
      item('README.md', 'ADD'),
    ]);

    // 根：目录在前，文件在后
    expect(tree.map((node) => node.name)).toEqual(['src', 'README.md']);
    expect(find(tree, 'src')?.count).toBe(2);
    expect(find(tree, 'src/utils')?.count).toBe(1);

    // 叶子保留 changeType
    expect(find(tree, 'README.md')?.changeType).toBe('ADD');
    expect(find(tree, 'README.md')?.dir).toBe(false);
    expect(find(tree, 'src/utils/layout.ts')?.changeType).toBe('MODIFY');
  });

  it('同一层目录在前、文件在后，各自按名称排序', () => {
    const tree = buildFileTree([
      item('b.txt'),
      item('z/inner.txt'),
      item('a.txt'),
      item('a-dir/inner.txt'),
    ]);
    expect(tree.map((node) => node.name)).toEqual(['a-dir', 'z', 'a.txt', 'b.txt']);
  });

  it('深路径逐级展开，不做单子目录链压缩', () => {
    const tree = buildFileTree([item('a/b/c/d.txt')]);
    const chain = ['a', 'a/b', 'a/b/c', 'a/b/c/d.txt'].map((path) => find(tree, path));
    expect(chain.every((node) => node !== undefined)).toBe(true);
    expect(find(tree, 'a')?.count).toBe(1);
  });

  it('空输入返回空树', () => {
    expect(buildFileTree([])).toEqual([]);
  });

  it('目录节点 changeType 恒为 null', () => {
    const tree = buildFileTree([item('dir/a.txt')]);
    expect(find(tree, 'dir')?.changeType).toBeNull();
  });
});

describe('collectFilePaths（F-27）', () => {
  it('目录返回全部后代文件路径，文件返回自身', () => {
    const tree = buildFileTree([
      item('src/components/App.vue'),
      item('src/utils/layout.ts'),
      item('README.md'),
    ]);

    const dir = find(tree, 'src');
    expect(dir && collectFilePaths(dir)).toEqual([
      'src/components/App.vue',
      'src/utils/layout.ts',
    ]);

    const leaf = find(tree, 'README.md');
    expect(leaf && collectFilePaths(leaf)).toEqual(['README.md']);
  });
});

describe('flattenVisible（F-27）', () => {
  const tree = buildFileTree([
    item('src/components/App.vue'),
    item('src/utils/layout.ts'),
    item('README.md'),
  ]);
  const depthOf = (rows: ReturnType<typeof flattenVisible>, path: string) =>
    rows.find((row) => row.node.path === path)?.depth;

  it('默认全展开，深度随层级递增', () => {
    const rows = flattenVisible(tree, () => false);
    expect(rows.map((row) => row.node.path)).toEqual([
      'src',
      'src/components',
      'src/components/App.vue',
      'src/utils',
      'src/utils/layout.ts',
      'README.md',
    ]);
    expect(depthOf(rows, 'src/utils/layout.ts')).toBe(2);
  });

  it('折叠的目录不展开其后代', () => {
    const rows = flattenVisible(tree, (path) => path === 'src/components');
    expect(rows.map((row) => row.node.path)).not.toContain('src/components/App.vue');
    expect(rows.map((row) => row.node.path)).toContain('src/utils/layout.ts');
  });
});

describe('展示偏好读写（F-27）', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('空值与非法值回退「列表」', () => {
    expect(readFileView()).toBe('list');
    localStorage.setItem('synclavis.commitFileView', 'whatever');
    expect(readFileView()).toBe('list');
  });

  it('写入后可读回「树形」', () => {
    writeFileView('tree');
    expect(readFileView()).toBe('tree');
    writeFileView('list');
    expect(readFileView()).toBe('list');
  });
});
