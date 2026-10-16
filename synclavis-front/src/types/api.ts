/** 统一响应信封（见《详细设计文档》§6.1） */
export interface ApiError {
  code: string;
  message: string;
  details?: unknown;
}

export interface ApiResponse<T> {
  ok: boolean;
  data: T | null;
  error: ApiError | null;
  operationId: string | null;
}

/** 异步写操作句柄 */
export interface OperationHandleDto {
  operationId: string;
}
