import { defineStore } from 'pinia';

export type OperationKind =
  | 'fetch'
  | 'pull'
  | 'push'
  | 'stash'
  | 'clone'
  | 'commit'
  | 'rebase';

/** 写操作：同仓库互斥（最终一致性由后端 repoId 锁保证） */
const WRITE_KINDS: OperationKind[] = ['fetch', 'pull', 'push', 'stash', 'commit', 'rebase'];

export interface OperationProgress {
  title: string;
  completed: number;
  total: number;
}

export interface RunningOp {
  operationId: string;
  repoId: string;
  kind: OperationKind;
  startedAt: number;
  progress: OperationProgress | null;
}

export interface LogLine {
  id: string;
  time: string;
  level: 'info' | 'warn' | 'error';
  text: string;
}

const MAX_LOG_LINES = 500;

let logSeq = 0;

/**
 * 取消请求出口。由 WebSocket 订阅方（useRepoEvents）注册，
 * 避免 store 直接依赖连接细节。
 */
let cancelHandler: ((operationId: string) => void) | null = null;

export function registerCancelHandler(handler: ((operationId: string) => void) | null): void {
  cancelHandler = handler;
}

export function requestOperationCancel(operationId: string): boolean {
  if (!cancelHandler) {
    return false;
  }
  cancelHandler(operationId);
  return true;
}

function nowText(): string {
  const date = new Date();
  const pad = (n: number): string => String(n).padStart(2, '0');
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

export const useOperationsStore = defineStore('operations', {
  state: () => ({
    running: {} as Record<string, RunningOp>,
    byRepo: {} as Record<string, string[]>,
    log: {} as Record<string, LogLine[]>,
  }),

  getters: {
    isRepoBusy(state) {
      return (repoId: string): boolean => (state.byRepo[repoId]?.length ?? 0) > 0;
    },
    canRun(state) {
      return (repoId: string, kind: OperationKind): boolean => {
        const running = state.byRepo[repoId] ?? [];
        if (running.length === 0) {
          return true;
        }
        // 读类操作不受限；写类操作要求该仓库当前无进行中操作
        return WRITE_KINDS.indexOf(kind) < 0;
      };
    },
    isRunning(state) {
      return (repoId: string, kind: OperationKind): boolean =>
        (state.byRepo[repoId] ?? []).some((id) => state.running[id]?.kind === kind);
    },
    progressOf(state) {
      return (repoId: string): OperationProgress | null => {
        const ids = state.byRepo[repoId] ?? [];
        for (const id of ids) {
          const op = state.running[id];
          if (op?.progress) {
            return op.progress;
          }
        }
        return null;
      };
    },
  },

  actions: {
    begin(operationId: string, repoId: string, kind: OperationKind): void {
      this.running[operationId] = {
        operationId,
        repoId,
        kind,
        startedAt: Date.now(),
        progress: null,
      };
      const list = this.byRepo[repoId] ?? (this.byRepo[repoId] = []);
      if (list.indexOf(operationId) < 0) {
        list.push(operationId);
      }
    },

    progress(operationId: string, payload: OperationProgress): void {
      const op = this.running[operationId];
      if (op) {
        op.progress = payload;
      }
    },

    fail(operationId: string, code: string, message: string): void {
      const op = this.running[operationId];
      this.appendLog(op ? op.repoId : '', {
        level: 'error',
        text: message ? `${code}: ${message}` : code,
      });
    },

    finish(operationId: string): void {
      const op = this.running[operationId];
      if (!op) {
        return;
      }
      delete this.running[operationId];
      const list = this.byRepo[op.repoId];
      if (list) {
        this.byRepo[op.repoId] = list.filter((id) => id !== operationId);
      }
    },

    appendLog(repoId: string, line: { level: LogLine['level']; text: string }): void {
      if (!repoId) {
        return;
      }
      const lines = this.log[repoId] ?? (this.log[repoId] = []);
      lines.push({
        id: `l${(logSeq += 1)}`,
        time: nowText(),
        level: line.level,
        text: line.text,
      });
      if (lines.length > MAX_LOG_LINES) {
        lines.splice(0, lines.length - MAX_LOG_LINES);
      }
    },

    clearLog(repoId: string): void {
      this.log[repoId] = [];
    },
  },
});
