package com.sonsure.synclavis4j.git;

import com.sonsure.synclavis4j.git.dto.FetchRequest;
import com.sonsure.synclavis4j.git.dto.OperationHandleDto;
import com.sonsure.synclavis4j.git.dto.PullRequest;
import com.sonsure.synclavis4j.git.dto.PushRequest;
import com.sonsure.synclavis4j.shared.dto.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Git 写操作接口：fetch / pull。
 *
 * <p>异步语义：立即返回 {@code { operationId }}，进度与结果经 WebSocket 推送。</p>
 */
@RestController
@RequestMapping("/api/repos/{id}")
public class GitOperationController {

    private final GitOperationService operationService;

    public GitOperationController(GitOperationService operationService) {
        this.operationService = operationService;
    }

    @PostMapping("/fetch")
    public ApiResponse<OperationHandleDto> fetch(@PathVariable("id") String repoId,
                                                 @RequestBody(required = false) FetchRequest request) {
        OperationHandleDto handle = operationService.fetchAsync(repoId, request);
        return ApiResponse.ok(handle, handle.getOperationId());
    }

    @PostMapping("/pull")
    public ApiResponse<OperationHandleDto> pull(@PathVariable("id") String repoId,
                                                @RequestBody(required = false) PullRequest request) {
        OperationHandleDto handle = operationService.pullAsync(repoId, request);
        return ApiResponse.ok(handle, handle.getOperationId());
    }

    @PostMapping("/push")
    public ApiResponse<OperationHandleDto> push(@PathVariable("id") String repoId,
                                                @RequestBody(required = false) PushRequest request) {
        OperationHandleDto handle = operationService.pushAsync(repoId, request);
        return ApiResponse.ok(handle, handle.getOperationId());
    }
}
