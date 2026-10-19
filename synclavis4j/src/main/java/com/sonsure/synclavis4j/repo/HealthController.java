package com.sonsure.synclavis4j.repo;

import com.sonsure.synclavis4j.shared.dto.ApiResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查：{@code GET /api/health}（见《详细设计文档》§6.2）。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final ObjectProvider<BuildProperties> buildProperties;

    public HealthController(ObjectProvider<BuildProperties> buildProperties) {
        this.buildProperties = buildProperties;
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("status", "UP");
        body.put("version", version());
        body.put("backend", "synclavis4j");
        return ApiResponse.ok(body);
    }

    /**
     * 版本取自构建产物（spring-boot-maven-plugin 的 build-info 目标写入 pom 的 {@code <version>}），
     * 避免在代码里硬编码一份、与 pom 漂移。
     *
     * <p>用 {@link ObjectProvider} 而非直接注入：build-info 资源缺失时（例如非 Maven 方式启动）
     * 应用仍能正常启动，版本降级为 {@code unknown}。</p>
     */
    private String version() {
        BuildProperties properties = buildProperties.getIfAvailable();
        String version = properties == null ? null : properties.getVersion();
        return version == null || version.isEmpty() ? "unknown" : version;
    }
}
