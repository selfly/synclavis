package com.sonsure.synclavis4j.ai.opencode;

/**
 * 一个已发现且通过健康检查的 OpenCode 服务端点。
 */
final class OpenCodeEndpoint {

    private final String baseUrl;
    private final String password;
    private final String version;

    OpenCodeEndpoint(String baseUrl, String password, String version) {
        this.baseUrl = baseUrl;
        this.password = password;
        this.version = version;
    }

    String getBaseUrl() {
        return baseUrl;
    }

    String getPassword() {
        return password;
    }

    String getVersion() {
        return version;
    }
}
