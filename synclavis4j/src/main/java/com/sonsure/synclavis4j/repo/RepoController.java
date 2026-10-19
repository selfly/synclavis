package com.sonsure.synclavis4j.repo;

import com.sonsure.synclavis4j.git.GitCloneService;
import com.sonsure.synclavis4j.git.dto.OperationHandleDto;
import com.sonsure.synclavis4j.repo.dto.CloneRepoRequest;
import com.sonsure.synclavis4j.repo.dto.OpenRepoRequest;
import com.sonsure.synclavis4j.repo.dto.ReorderSessionRequest;
import com.sonsure.synclavis4j.repo.dto.RepoSettingsDto;
import com.sonsure.synclavis4j.repo.dto.SetActiveRequest;
import com.sonsure.synclavis4j.repo.dto.UpdateRepoSettingsRequest;
import com.sonsure.synclavis4j.shared.dto.ApiResponse;
import com.sonsure.synclavis4j.state.RecentRepo;
import com.sonsure.synclavis4j.state.RepoSettings;
import com.sonsure.synclavis4j.state.SessionState;
import com.sonsure.synclavis4j.state.SessionTab;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仓库与会话接口（见《详细设计文档》§6.2）。
 */
@RestController
@RequestMapping("/api")
public class RepoController {

    private final RepoService repoService;
    private final GitCloneService cloneService;

    public RepoController(RepoService repoService, GitCloneService cloneService) {
        this.repoService = repoService;
        this.cloneService = cloneService;
    }

    @GetMapping("/session")
    public ApiResponse<SessionState> session() {
        return ApiResponse.ok(repoService.session());
    }

    @PutMapping("/session/active")
    public ApiResponse<SessionState> setActive(@RequestBody SetActiveRequest request) {
        return ApiResponse.ok(repoService.setActive(request == null ? null : request.getRepoId()));
    }

    @PutMapping("/session/order")
    public ApiResponse<SessionState> reorder(@RequestBody ReorderSessionRequest request) {
        return ApiResponse.ok(repoService.reorder(request == null ? null : request.getIds()));
    }

    @GetMapping("/repos/recent")
    public ApiResponse<List<RecentRepo>> recent(@RequestParam(value = "limit", defaultValue = "10") int limit) {
        return ApiResponse.ok(repoService.recent(limit));
    }

    @PostMapping("/repos/open")
    public ApiResponse<SessionTab> open(@RequestBody OpenRepoRequest request) {
        if (request == null) {
            return ApiResponse.fail(
                    new com.sonsure.synclavis4j.shared.dto.ErrorBody("INVALID_PATH", "请求体不能为空", null), null);
        }
        return ApiResponse.ok(repoService.open(request.getPath(), request.getSshKeyPath()));
    }

    @PostMapping("/repos/clone")
    public ApiResponse<OperationHandleDto> clone(@RequestBody CloneRepoRequest request) {
        if (request == null) {
            return ApiResponse.fail(
                    new com.sonsure.synclavis4j.shared.dto.ErrorBody("INVALID_PATH", "请求体不能为空", null), null);
        }
        OperationHandleDto handle = cloneService.cloneAsync(
                request.getUrl(), request.getPath(), request.getSshKeyPath(),
                request.getHttpsUsername(), request.getHttpsPassword());
        return ApiResponse.ok(handle, handle.getOperationId());
    }

    @DeleteMapping("/repos/{id}")
    public ApiResponse<Map<String, Object>> close(@PathVariable("id") String repoId) {
        repoService.close(repoId);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("id", repoId);
        return ApiResponse.ok(body);
    }

    @GetMapping("/repos/{id}/settings")
    public ApiResponse<RepoSettingsDto> repoSettings(@PathVariable("id") String repoId) {
        return ApiResponse.ok(repoService.repoSettings(repoId));
    }

    @PutMapping("/repos/{id}/settings")
    public ApiResponse<RepoSettingsDto> updateRepoSettings(@PathVariable("id") String repoId,
                                                           @RequestBody UpdateRepoSettingsRequest request) {
        String sshKeyPath = request == null ? null : request.getSshKeyPath();
        String pullStrategy = request == null ? null : request.getPullStrategy();
        String httpsUsername = request == null ? null : request.getHttpsUsername();
        String httpsPassword = request == null ? null : request.getHttpsPassword();
        return ApiResponse.ok(repoService.updateRepoSettings(
                repoId, sshKeyPath, pullStrategy, httpsUsername, httpsPassword));
    }
}
