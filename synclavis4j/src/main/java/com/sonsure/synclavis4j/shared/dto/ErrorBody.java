package com.sonsure.synclavis4j.shared.dto;

/**
 * 错误体：{@code code / message / details}。
 */
public class ErrorBody {

    private String code;
    private String message;
    private Object details;

    public ErrorBody() {
    }

    public ErrorBody(String code, String message, Object details) {
        this.code = code;
        this.message = message;
        this.details = details;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Object getDetails() {
        return details;
    }

    public void setDetails(Object details) {
        this.details = details;
    }
}
