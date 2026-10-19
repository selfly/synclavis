import type { ApiResponse } from '@/types/api';

/** 默认走同源（dev 下由 Vite proxy 转发到后端），可用 VITE_API_BASE 覆盖 */
const BASE = import.meta.env.VITE_API_BASE ?? '';

export class ApiRequestError extends Error {
  readonly code: string;
  readonly details?: unknown;

  constructor(code: string, message: string, details?: unknown) {
    super(message);
    this.name = 'ApiRequestError';
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

  const response = await fetch(`${BASE}${path}`, init);

  let envelope: ApiResponse<T>;
  try {
    envelope = (await response.json()) as ApiResponse<T>;
  } catch {
    throw new ApiRequestError('INTERNAL', `响应解析失败 (HTTP ${response.status})`);
  }

  if (!envelope.ok || envelope.error) {
    const error = envelope.error;
    throw new ApiRequestError(
      error ? error.code : 'INTERNAL',
      error ? error.message : `HTTP ${response.status}`,
      error ? error.details : undefined,
    );
  }

  return envelope.data as T;
}

export const http = {
  get: <T>(path: string): Promise<T> => request<T>('GET', path),
  post: <T>(path: string, body?: unknown): Promise<T> => request<T>('POST', path, body),
  put: <T>(path: string, body?: unknown): Promise<T> => request<T>('PUT', path, body),
  del: <T>(path: string): Promise<T> => request<T>('DELETE', path),
};
