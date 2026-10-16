package com.sonsure.synclavis4j.settings;

import com.sonsure.synclavis4j.repo.dto.UpdateGlobalSettingsRequest;
import com.sonsure.synclavis4j.shared.dto.ApiResponse;
import com.sonsure.synclavis4j.state.GlobalSettings;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全局设置接口：{@code GET/PUT /api/settings}。
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public ApiResponse<GlobalSettings> get() {
        return ApiResponse.ok(settingsService.get());
    }

    @PutMapping
    public ApiResponse<GlobalSettings> update(@RequestBody UpdateGlobalSettingsRequest request) {
        if (request == null) {
            return ApiResponse.ok(settingsService.get());
        }
        return ApiResponse.ok(settingsService.update(
                request.getTheme(), request.getShortcuts(), request.getRecentRepos()));
    }
}
