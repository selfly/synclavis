package com.sonsure.synclavis4j.git.dto;

/**
 * 修改远程仓库 URL 请求：{@code { url }}（远程名在路径里）。
 */
public class RemoteUpdateRequest {

    private String url;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }
}
