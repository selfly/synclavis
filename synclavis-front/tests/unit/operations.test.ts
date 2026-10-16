import { beforeEach, describe, expect, it } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import {
  registerCancelHandler,
  requestOperationCancel,
  useOperationsStore,
} from '@/stores/operations';

const REPO_A = 'repo-a';
const REPO_B = 'repo-b';

describe('operations store', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  it('begin/finish 维护仓库维度的进行中操作', () => {
    const store = useOperationsStore();
    store.begin('op-1', REPO_A, 'pull');

    expect(store.isRepoBusy(REPO_A)).toBe(true);
    expect(store.isRunning(REPO_A, 'pull')).toBe(true);

    store.finish('op-1');

    expect(store.isRepoBusy(REPO_A)).toBe(false);
    expect(store.running['op-1']).toBeUndefined();
  });

  it('写操作互斥：同仓库有操作进行中时不能再发起写操作', () => {
    const store = useOperationsStore();
    store.begin('op-1', REPO_A, 'fetch');

    expect(store.canRun(REPO_A, 'pull')).toBe(false);
    expect(store.canRun(REPO_A, 'push')).toBe(false);
  });

  it('跨仓库互不影响', () => {
    const store = useOperationsStore();
    store.begin('op-1', REPO_A, 'fetch');

    expect(store.canRun(REPO_B, 'pull')).toBe(true);
    expect(store.isRepoBusy(REPO_B)).toBe(false);
  });

  it('空闲时任何操作都允许', () => {
    const store = useOperationsStore();
    expect(store.canRun(REPO_A, 'pull')).toBe(true);
    expect(store.canRun(REPO_A, 'commit')).toBe(true);
  });

  it('progressOf 返回该仓库当前进度', () => {
    const store = useOperationsStore();
    store.begin('op-1', REPO_A, 'fetch');
    expect(store.progressOf(REPO_A)).toBeNull();

    store.progress('op-1', { title: 'Receiving objects', completed: 10, total: 100 });
    expect(store.progressOf(REPO_A)).toEqual({
      title: 'Receiving objects',
      completed: 10,
      total: 100,
    });
  });

  it('progress 对未知 operationId 安全忽略', () => {
    const store = useOperationsStore();
    expect(() => store.progress('nope', { title: 'x', completed: 1, total: 2 })).not.toThrow();
  });

  it('appendLog 超过 500 行时裁剪旧行', () => {
    const store = useOperationsStore();
    for (let i = 0; i < 520; i += 1) {
      store.appendLog(REPO_A, { level: 'info', text: `line-${i}` });
    }
    const lines = store.log[REPO_A];
    expect(lines).toHaveLength(500);
    expect(lines[0].text).toBe('line-20');
  });

  it('clearLog 清空指定仓库日志', () => {
    const store = useOperationsStore();
    store.appendLog(REPO_A, { level: 'info', text: 'a' });
    store.appendLog(REPO_B, { level: 'info', text: 'b' });

    store.clearLog(REPO_A);

    expect(store.log[REPO_A]).toHaveLength(0);
    expect(store.log[REPO_B]).toHaveLength(1);
  });

  it('fail 写入错误日志并保留原操作直到 done', () => {
    const store = useOperationsStore();
    store.begin('op-1', REPO_A, 'pull');
    store.fail('op-1', 'MERGE_CONFLICT', '合并存在冲突');

    expect(store.running['op-1']).toBeDefined();
    const lines = store.log[REPO_A];
    expect(lines).toHaveLength(1);
    expect(lines[0].level).toBe('error');
    expect(lines[0].text).toContain('MERGE_CONFLICT');
    expect(lines[0].text).toContain('合并存在冲突');
  });

  it('finish 对未知 operationId 安全忽略', () => {
    const store = useOperationsStore();
    expect(() => store.finish('nope')).not.toThrow();
  });
});

describe('operation cancel plumbing', () => {
  beforeEach(() => {
    registerCancelHandler(null);
  });

  it('未注册处理器时 requestOperationCancel 返回 false', () => {
    expect(requestOperationCancel('op-1')).toBe(false);
  });

  it('注册后把取消请求下发到连接层', () => {
    const calls: string[] = [];
    registerCancelHandler((operationId) => calls.push(operationId));

    expect(requestOperationCancel('op-1')).toBe(true);
    expect(calls).toEqual(['op-1']);

    registerCancelHandler(null);
    expect(requestOperationCancel('op-2')).toBe(false);
  });
});
