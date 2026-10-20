package com.sonsure.synclavis4j.plugin;

import com.sonsure.synclavis4j.shared.dto.ApiResponse;
import com.sonsure.synclavis4j.shared.exception.ErrorCode;
import com.sonsure.synclavis4j.shared.exception.GitOperationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 插件管理接口（见《详细设计文档》§6.4.3）。
 */
@RestController
@RequestMapping("/api/plugins")
public class PluginController {

    private final PluginService pluginService;

    public PluginController(PluginService pluginService) {
        this.pluginService = pluginService;
    }

    @GetMapping
    public ApiResponse<PluginListDto> list() {
        return ApiResponse.ok(pluginService.list());
    }

    @PostMapping("/reload")
    public ApiResponse<PluginListDto> reload() {
        return ApiResponse.ok(pluginService.reload());
    }

    @PutMapping("/{id}/enabled")
    public ApiResponse<PluginInfo> setEnabled(@PathVariable("id") String id,
                                              @RequestBody(required = false) PluginToggleRequest request) {
        if (request == null || request.getEnabled() == null) {
            throw new GitOperationException(ErrorCode.INVALID_PARAM, "缺少 enabled 参数");
        }
        return ApiResponse.ok(pluginService.setEnabled(id, request.getEnabled()));
    }

    @PutMapping("/developer-mode")
    public ApiResponse<PluginToggleRequest> setDeveloperMode(
            @RequestBody(required = false) PluginToggleRequest request) {
        if (request == null || request.getEnabled() == null) {
            throw new GitOperationException(ErrorCode.INVALID_PARAM, "缺少 enabled 参数");
        }
        boolean enabled = pluginService.setDeveloperMode(request.getEnabled());
        PluginToggleRequest result = new PluginToggleRequest();
        result.setEnabled(enabled);
        return ApiResponse.ok(result);
    }

    @PutMapping("/active-views")
    public ApiResponse<ActiveViewsRequest> setActiveViews(
            @RequestBody(required = false) ActiveViewsRequest request) {
        ActiveViewsRequest result = new ActiveViewsRequest();
        result.setActiveViews(pluginService.setActiveViews(
                request == null ? null : request.getActiveViews()));
        return ApiResponse.ok(result);
    }
}
