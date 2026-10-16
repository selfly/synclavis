package com.sonsure.synclavis4j.shared.dto;

/**
 * 统一响应信封：{@code ok / data / error / operationId}。
 *
 * <p>该结构属于跨后端共用契约（见《详细设计文档》§6.1），任何后端实现不得改动字段名。</p>
 */
public class ApiResponse<T> {

    private boolean ok;
    private T data;
    private ErrorBody error;
    private String operationId;

    public ApiResponse() {
    }

    public static <T> ApiResponse<T> ok(T data) {
        return ok(data, null);
    }

    public static <T> ApiResponse<T> ok(T data, String operationId) {
        ApiResponse<T> response = new ApiResponse<T>();
        response.ok = true;
        response.data = data;
        response.operationId = operationId;
        return response;
    }

    public static <T> ApiResponse<T> fail(ErrorBody error, String operationId) {
        ApiResponse<T> response = new ApiResponse<T>();
        response.ok = false;
        response.error = error;
        response.operationId = operationId;
        return response;
    }

    public boolean isOk() {
        return ok;
    }

    public void setOk(boolean ok) {
        this.ok = ok;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public ErrorBody getError() {
        return error;
    }

    public void setError(ErrorBody error) {
        this.error = error;
    }

    public String getOperationId() {
        return operationId;
    }

    public void setOperationId(String operationId) {
        this.operationId = operationId;
    }
}
