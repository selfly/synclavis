package com.sonsure.synclavis4j.git;

import com.sonsure.synclavis4j.git.dto.FetchRequest;
import com.sonsure.synclavis4j.git.dto.OperationHandleDto;
import com.sonsure.synclavis4j.git.dto.PullRequest;
import com.sonsure.synclavis4j.git.dto.PushPreviewDto;
import com.sonsure.synclavis4j.git.dto.PushRequest;
import com.sonsure.synclavis4j.shared.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Git 远程操作接口：fetch / pull / push（写）+ push 预览（读）。
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

    /** 推送预览：本次 push 会推上去的提交（同步只读，供推送前确认）。 */
    @GetMapping("/push/preview")
    public ApiResponse<PushPreviewDto> pushPreview(@PathVariable("id") String repoId,
                                                   @RequestParam(value = "remote", required = false) String remote,
                                                   @RequestParam(value = "branch", required = false) String branch) {
        PushRequest request = new PushRequest();
        request.setRemote(remote);
        request.setBranch(branch);
        return ApiResponse.ok(operationService.pushPreview(repoId, request));
    }
}
