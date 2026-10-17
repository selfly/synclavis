package com.sonsure.synclavis4j.git.dto;

/**
 * 新增远程仓库请求：{@code { name, url }}。
 */
public class RemoteAddRequest {

    private String name;
    private String url;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }
}
