/**
 * 终端插件自己的 API 客户端。
 *
 * <p>只依赖宿主公开的 /api/terminal 与 /ws/terminal 契约，不 import 宿主源码。</p>
 */

export interface TerminalSession {
  terminalId: string;
  repoId: string;
  shell: string;
  cwd: string;
  cols: number;
  rows: number;
}

interface ApiErrorBody {
  code: string;
  message: string;
  details?: unknown;
}

interface ApiEnvelope<T> {
  ok: boolean;
  data: T;
  error: ApiErrorBody | null;
}

export class ApiError extends Error {
  readonly code: string;
  readonly details?: unknown;

  constructor(code: string, message: string, details?: unknown) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.details = details;
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const init: RequestInit = { method };
  if (body !== undefined) {
    init.headers = { 'Content-Type': 'application/json' };
    init.body = JSON.stringify(body);
  }
  const response = await fetch(path, init);
  let envelope: ApiEnvelope<T>;
  try {
    envelope = (await response.json()) as ApiEnvelope<T>;
  } catch {
    throw new ApiError('INTERNAL', `响应解析失败 (HTTP ${response.status})`);
  }
  if (!envelope.ok || envelope.error) {
    throw new ApiError(
      envelope.error?.code ?? 'INTERNAL',
      envelope.error?.message ?? `HTTP ${response.status}`,
      envelope.error?.details,
    );
  }
  return envelope.data;
}

export const terminalApi = {
  create: (repoId: string, cols: number, rows: number): Promise<TerminalSession> =>
    request<TerminalSession>('POST', '/api/terminal/sessions', { repoId, cols, rows }),

  delete: (terminalId: string): Promise<Record<string, unknown>> =>
    request<Record<string, unknown>>(
      'DELETE',
      `/api/terminal/sessions/${encodeURIComponent(terminalId)}`,
    ),
};
