import { afterEach, describe, expect, it, vi } from 'vitest';
import { fsApi } from '@/api/fs';
import type { FsEntryDto } from '@/api/fs';
import {
  basenameOf,
  dirnameOf,
  readPickerPrefs,
  resolvePickerPath,
  visibleEntries,
  writePickerPrefs,
} from '@/utils/pathPicker';

function entry(name: string, overrides: Partial<FsEntryDto> = {}): FsEntryDto {
  return {
    name,
    path: `/tmp/${name}`,
    dir: true,
    repo: false,
    hidden: false,
    ...overrides,
  };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('utils/pathPicker', () => {
  it('visibleEntries 默认过滤 . 开头的隐藏项', () => {
    const entries = [entry('src'), entry('.git', { hidden: true }), entry('README.md', { dir: false })];
    expect(visibleEntries(entries, false).map((item) => item.name)).toEqual(['src', 'README.md']);
  });

  it('visibleEntries 打开开关后保留全部条目', () => {
    const entries = [entry('src'), entry('.git', { hidden: true })];
    expect(visibleEntries(entries, true)).toHaveLength(2);
  });

  it('resolvePickerPath 优先输入框路径并去除首尾空白', () => {
    expect(resolvePickerPath('  /Users/me/projects  ', { lastDir: '/tmp', showHidden: false })).toBe(
      '/Users/me/projects',
    );
  });

  it('resolvePickerPath 输入为空时回退上次浏览目录，仍为空则交给后端', () => {
    expect(resolvePickerPath('', { lastDir: '/tmp', showHidden: false })).toBe('/tmp');
    expect(resolvePickerPath(null, { lastDir: null, showHidden: false })).toBeNull();
  });

  it('dirnameOf 处理 POSIX、根目录、Windows 盘符与相对路径', () => {
    expect(dirnameOf('/Users/me/.ssh/id_ed25519')).toBe('/Users/me/.ssh');
    expect(dirnameOf('/a')).toBe('/');
    expect(dirnameOf('/')).toBe('/');
    expect(dirnameOf('C:\\Users\\me\\.ssh\\key')).toBe('C:\\Users\\me\\.ssh');
    expect(dirnameOf('C:\\')).toBe('C:\\');
    expect(dirnameOf('id_ed25519')).toBeNull();
    expect(dirnameOf('')).toBeNull();
  });

  it('basenameOf 取末段名称', () => {
    expect(basenameOf('/Users/me/.ssh/id_ed25519')).toBe('id_ed25519');
    expect(basenameOf('C:\\Users\\me\\key')).toBe('key');
    expect(basenameOf('README.md')).toBe('README.md');
    expect(basenameOf(null)).toBe('');
  });

  it('readPickerPrefs 容错非法 JSON 与非法字段', () => {
    expect(readPickerPrefs(null)).toEqual({ lastDir: null, showHidden: false });
    expect(readPickerPrefs('{bad json')).toEqual({ lastDir: null, showHidden: false });
    expect(readPickerPrefs('null')).toEqual({ lastDir: null, showHidden: false });
    expect(readPickerPrefs(JSON.stringify({ lastDir: 42, showHidden: 'yes' }))).toEqual({
      lastDir: null,
      showHidden: false,
    });
  });

  it('writePickerPrefs 写入后可以读回', () => {
    const raw = writePickerPrefs({ lastDir: '/Users/me/projects', showHidden: true });
    expect(readPickerPrefs(raw)).toEqual({ lastDir: '/Users/me/projects', showHidden: true });
  });

  it('fsApi.list 正确编码 path 与 kind 查询串', async () => {
    const fetchMock = vi.fn(async () => ({
      status: 200,
      json: async () => ({
        ok: true,
        data: { path: '/a b', parent: '/', roots: ['/'], breadcrumbs: [], entries: [], truncated: false },
      }),
    }));
    vi.stubGlobal('fetch', fetchMock);

    await fsApi.list('/a b', 'file');

    expect(fetchMock).toHaveBeenCalledWith('/api/fs/list?path=%2Fa+b&kind=file', { method: 'GET' });
  });

  it('fsApi.list 省略空路径时只发 kind', async () => {
    const fetchMock = vi.fn(async () => ({
      status: 200,
      json: async () => ({
        ok: true,
        data: { path: '/', parent: null, roots: ['/'], breadcrumbs: [], entries: [], truncated: false },
      }),
    }));
    vi.stubGlobal('fetch', fetchMock);

    await fsApi.list('  ', 'dir');

    expect(fetchMock).toHaveBeenCalledWith('/api/fs/list?kind=dir', { method: 'GET' });
  });
});
