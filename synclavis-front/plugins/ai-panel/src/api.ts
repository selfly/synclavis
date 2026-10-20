/**
 * AI 面板插件自己的 API 客户端。
 *
 * <p>外部插件只依赖宿主公开的 HTTP/WS 契约，不 import 宿主源码；
 * 因此这里保留一份最小的类型与 fetch 封装。</p>
 */

export interface HarnessCapabilities {
  prompt: boolean;
  streaming: boolean;
  cancel: boolean;
  resume: boolean;
  permissions: boolean;
  diff: boolean;
  tools: boolean;
}

export interface HarnessInfo {
  id: string;
  name: string;
  version: string | null;
  available: boolean;
  reason: string | null;
  capabilities: HarnessCapabilities;
}

export interface HarnessList {
  harnesses: HarnessInfo[];
  activeHarness: Record<string, string>;
}

export interface AiSession {
  sessionRef: string;
  harnessId: string;
  pluginId: string | null;
  repoId: string | null;
  title: string;
  status: string;
  createdAt: string;
  updatedAt: string;
  lastSeq: number;
  currentTurnId: string | null;
  lastError: { code?: string; message?: string } | null;
}

export interface AiSessionList {
  sessions: AiSession[];
}

export interface AiEvent {
  type: string;
  sessionRef: string;
  turnId: string;
  seq: number;
  at: string;
  payload: Record<string, unknown>;
}

export interface AiEvents {
  sessionRef: string;
  lastSeq: number;
  truncated: boolean;
  events: AiEvent[];
}

export interface PromptResult {
  sessionRef: string;
  turnId: string;
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

function sessionPath(sessionRef: string, suffix = ''): string {
  return `/api/ai/sessions/${encodeURIComponent(sessionRef)}${suffix}`;
}

export const aiApi = {
  harnesses: (): Promise<HarnessList> => request<HarnessList>('GET', '/api/ai/harnesses'),

  setActiveHarness: async (pluginId: string, harnessId: string): Promise<Record<string, string>> => {
    const data = await request<{ activeHarness: Record<string, string> }>(
      'PUT',
      '/api/ai/harnesses/active',
      { pluginId, harnessId },
    );
    return data.activeHarness;
  },

  sessions: (repoId: string): Promise<AiSessionList> =>
    request<AiSessionList>('GET', `/api/ai/sessions?repoId=${encodeURIComponent(repoId)}`),

  session: (sessionRef: string): Promise<AiSession> =>
    request<AiSession>('GET', sessionPath(sessionRef)),

  createSession: (repoId: string, pluginId: string, harnessId: string): Promise<AiSession> =>
    request<AiSession>('POST', '/api/ai/sessions', { repoId, pluginId, harnessId }),

  deleteSession: (sessionRef: string): Promise<Record<string, unknown>> =>
    request<Record<string, unknown>>('DELETE', sessionPath(sessionRef)),

  prompt: (sessionRef: string, text: string): Promise<PromptResult> =>
    request<PromptResult>('POST', sessionPath(sessionRef, '/prompt'), { text }),

  cancel: (sessionRef: string, turnId?: string): Promise<{ cancelled: boolean }> =>
    request<{ cancelled: boolean }>('POST', sessionPath(sessionRef, '/cancel'),
      turnId ? { turnId } : {}),

  respondPermission: (
    sessionRef: string,
    requestId: string,
    decision: 'allow' | 'deny',
  ): Promise<{ requestId: string; decision: string }> =>
    request<{ requestId: string; decision: string }>(
      'POST',
      sessionPath(sessionRef, `/permissions/${encodeURIComponent(requestId)}`),
      { decision },
    ),

  events: (sessionRef: string, afterSeq: number, limit = 1000): Promise<AiEvents> =>
    request<AiEvents>(
      'GET',
      sessionPath(sessionRef, `/events?afterSeq=${afterSeq}&limit=${limit}`),
    ),
};
