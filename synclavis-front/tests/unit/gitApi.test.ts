import { afterEach, describe, expect, it, vi } from 'vitest';
import { gitApi } from '@/api/git';

function stubFetch(): ReturnType<typeof vi.fn> {
  const fetchMock = vi.fn().mockResolvedValue({
    json: async () => ({ ok: true, data: { total: 0, commits: [] } }),
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

describe('api/git 的 log 查询串', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('引用范围显式序列化：all=true 与 all=false 都带上', async () => {
    const fetchMock = stubFetch();

    await gitApi.log('repo1', { all: true });
    expect(fetchMock.mock.calls[0][0]).toBe('/api/repos/repo1/log?all=true');

    await gitApi.log('repo1', { all: false });
    expect(fetchMock.mock.calls[1][0]).toBe('/api/repos/repo1/log?all=false');
  });

  it('分页 / 关键字 / 作者 / ref 与范围可叠加，关键字做 URL 编码', async () => {
    const fetchMock = stubFetch();

    await gitApi.log('repo1', { skip: 100, limit: 100, q: '修复 保存', author: 'a b', ref: 'refs/heads/dev', all: true });

    expect(fetchMock.mock.calls[0][0]).toBe(
      '/api/repos/repo1/log?skip=100&limit=100&author=a%20b&q=%E4%BF%AE%E5%A4%8D%20%E4%BF%9D%E5%AD%98&ref=refs%2Fheads%2Fdev&all=true',
    );
  });

  it('排序显式序列化：order=date（缺省值也带）与 order=topo', async () => {
    const fetchMock = stubFetch();

    await gitApi.log('repo1', { all: true, order: 'date' });
    expect(fetchMock.mock.calls[0][0]).toBe('/api/repos/repo1/log?all=true&order=date');

    await gitApi.log('repo1', { all: true, order: 'topo' });
    expect(fetchMock.mock.calls[1][0]).toBe('/api/repos/repo1/log?all=true&order=topo');
  });

  it('未指定范围时不追加 all 参数（后端按缺省 false 处理）', async () => {
    const fetchMock = stubFetch();

    await gitApi.log('repo1', { limit: 1 });

    expect(fetchMock.mock.calls[0][0]).toBe('/api/repos/repo1/log?limit=1');
  });
});
