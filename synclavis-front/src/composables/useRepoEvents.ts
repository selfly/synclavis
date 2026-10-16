import { onUnmounted, ref, watch, type Ref } from 'vue';
import { connectRepoEvents, type WsConnection } from '@/api/ws';
import { registerCancelHandler, useOperationsStore } from '@/stores/operations';
import { useReposStore } from '@/stores/repos';
import { useTabsStore } from '@/stores/tabs';

export type WsStatus = 'idle' | 'open' | 'closed';

/**
 * 订阅当前仓库的 WebSocket 事件并分发到各 store。
 *
 * - progress/log/error/done -> operations store
 * - status -> tabs + repos store
 * - 断线重连成功后拉一次 status 做补偿（设计文档 §8.3）
 */
export function useRepoEvents(repoId: Ref<string | null>) {
  const operations = useOperationsStore();
  const repos = useReposStore();
  const tabs = useTabsStore();

  const status = ref<WsStatus>('idle');
  let connection: WsConnection | null = null;
  let lastRepoId: string | null = null;

  function close(): void {
    connection?.close();
    connection = null;
    status.value = 'idle';
  }

  function connect(id: string): void {
    close();
    lastRepoId = id;
    // 取消请求经当前连接下发（服务端在 ProgressMonitor 检查点中止 JGit 操作）
    registerCancelHandler((operationId) => {
      connection?.send({ type: 'cancel', operationId });
    });
    connection = connectRepoEvents(
      id,
      (event) => {
        switch (event.type) {
          case 'progress':
            operations.progress(event.operationId, event.payload);
            break;
          case 'log':
            operations.appendLog(event.repoId, event.payload);
            break;
          case 'status':
            tabs.upsertStatus(event.repoId, event.payload);
            repos.setStatus(event.repoId, event.payload);
            break;
          case 'error':
            operations.fail(event.operationId, event.payload.code, event.payload.message);
            break;
          case 'done':
            operations.finish(event.operationId);
            if (event.payload.ok) {
              void repos.refreshAfterOperation(event.repoId);
            }
            break;
          default:
            break;
        }
      },
      (state) => {
        status.value = state;
        // 重连成功：补偿一次状态（断线期间可能丢失 status 事件）
        if (state === 'open' && id === lastRepoId) {
          void repos.refreshStatus(id).then((next) => tabs.upsertStatus(id, next)).catch(() => undefined);
        }
      },
    );
  }

  watch(
    repoId,
    (id) => {
      if (id) {
        connect(id);
      } else {
        close();
      }
    },
    { immediate: true },
  );

  onUnmounted(() => {
    registerCancelHandler(null);
    close();
  });

  return { status, reconnect: () => (lastRepoId ? connect(lastRepoId) : undefined) };
}
