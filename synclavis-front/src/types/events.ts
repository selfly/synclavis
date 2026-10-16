import type { RepoStatus } from '@/types/repo';

/** WebSocket 消息联合类型（见《详细设计文档》§4.1 / §8.4） */
export type WsEvent =
  | {
      type: 'progress';
      repoId: string;
      operationId: string;
      payload: { title: string; completed: number; total: number };
    }
  | {
      type: 'log';
      repoId: string;
      operationId: string;
      payload: { level: 'info' | 'warn' | 'error'; text: string };
    }
  | { type: 'status'; repoId: string; operationId?: string; payload: RepoStatus }
  | {
      type: 'error';
      repoId: string;
      operationId: string;
      payload: { code: string; message: string; details?: unknown };
    }
  | { type: 'done'; repoId: string; operationId: string; payload: { ok: boolean } }
  | { type: 'pong' };
