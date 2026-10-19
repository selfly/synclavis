import { describe, expect, it } from 'vitest';
import type { RepoFileEntry } from '@/api/files';
import {
  flattenProjectTree,
  formatBytes,
  sortEntries,
  splitPath,
  type ProjectChildren,
} from '@/utils/projectTree';

function entry(path: string, dir = false, extra: Partial<RepoFileEntry> = {}): RepoFileEntry {
  const index = path.lastIndexOf('/');
  return {
    name: index >= 0 ? path.slice(index + 1) : path,
    path,
    dir,
    size: dir ? 0 : 10,
    ignored: false,
    symlink: false,
    ...extra,
  };
}

function children(map: Record<string, RepoFileEntry[]>): ProjectChildren {
  return map;
}

const sample = children({
  '': [entry('src', true), entry('README.md'), entry('.gitignore')],
  src: [entry('src/components', true), entry('src/main.ts')],
  'src/components': [entry('src/components/App.vue')],
});

describe('sortEntries（项目文件树）', () => {
  it('目录在前、文件在后，各自按名称自然排序', () => {
    const sorted = sortEntries([entry('b.ts'), entry('zdir', true), entry('a.ts'), entry('adir', true)]);
    expect(sorted.map((node) => node.name)).toEqual(['adir', 'zdir', 'a.ts', 'b.ts']);
  });

  it('带数字的名称按数值排序（file2 在 file10 之前）', () => {
    const sorted = sortEntries([entry('file10.ts'), entry('file2.ts')]);
    expect(sorted.map((node) => node.name)).toEqual(['file2.ts', 'file10.ts']);
  });

  it('不修改入参数组', () => {
    const input = [entry('b.ts'), entry('a.ts')];
    sortEntries(input);
    expect(input.map((node) => node.name)).toEqual(['b.ts', 'a.ts']);
  });
});

describe('flattenProjectTree', () => {
  it('只渲染已加载且已展开的目录的后代', () => {
    const rows = flattenProjectTree(sample, new Set(['src']));
    expect(rows.map((row) => row.node.path)).toEqual([
      'src',
      'src/components',
      'src/main.ts',
      'README.md',
      '.gitignore',
    ]);
    // components 未展开，其子项不出现
    expect(rows.some((row) => row.node.path === 'src/components/App.vue')).toBe(false);
  });

  it('未展开任何目录时只有根层', () => {
    const rows = flattenProjectTree(sample, new Set());
    expect(rows.map((row) => row.node.path)).toEqual(['src', 'README.md', '.gitignore']);
  });

  it('深度随层级递增', () => {
    const rows = flattenProjectTree(sample, new Set(['src', 'src/components']));
    const byPath = new Map(rows.map((row) => [row.node.path, row.depth]));
    expect(byPath.get('src')).toBe(0);
    expect(byPath.get('src/components')).toBe(1);
    expect(byPath.get('src/components/App.vue')).toBe(2);
  });

  it('目录未加载时不渲染占位行', () => {
    const rows = flattenProjectTree(sample, new Set(['src', 'src/unknown']));
    expect(rows.some((row) => row.node.path === 'src/unknown')).toBe(false);
  });

  it('根目录未加载时返回空列表', () => {
    expect(flattenProjectTree(children({}), new Set())).toEqual([]);
  });
});

describe('splitPath', () => {
  it('拆出目录前缀与文件名', () => {
    expect(splitPath('src/components/App.vue')).toEqual({
      dir: 'src/components',
      name: 'App.vue',
    });
  });

  it('根层文件没有目录前缀', () => {
    expect(splitPath('README.md')).toEqual({ dir: '', name: 'README.md' });
  });
});

describe('formatBytes', () => {
  it('小于 1KB 按字节展示', () => {
    expect(formatBytes(512)).toBe('512 B');
  });

  it('按 1024 进制进位', () => {
    expect(formatBytes(1024)).toBe('1.0 KB');
    expect(formatBytes(1536)).toBe('1.5 KB');
    expect(formatBytes(1024 * 1024)).toBe('1.0 MB');
  });

  it('非法值返回空串', () => {
    expect(formatBytes(-1)).toBe('');
    expect(formatBytes(Number.NaN)).toBe('');
  });
});
