package com.sonsure.synclavis4j.plugin;

import com.sonsure.synclavis4j.shared.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 宿主能力查询（见《详细设计文档》§6.4.3）。
 *
 * <p>前端用它决定是否显示 AI 面板/终端入口；具体插件是否安装仍以
 * {@code GET /api/plugins} 为准。</p>
 */
@RestController
public class CapabilitiesController {

    private final PluginService pluginService;

    public CapabilitiesController(PluginService pluginService) {
        this.pluginService = pluginService;
    }

    @GetMapping("/api/capabilities")
    public ApiResponse<CapabilitiesDto> get() {
        return ApiResponse.ok(pluginService.capabilities());
    }
}
