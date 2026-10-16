package com.sonsure.synclavis4j.git;

import com.sonsure.synclavis4j.git.dto.RebaseStatusDto;
import com.sonsure.synclavis4j.shared.dto.ApiResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rebase 中间态接口：{@code /api/repos/{id}/rebase/*}。
 */
@RestController
@RequestMapping("/api/repos/{id}/rebase")
public class GitRebaseController {

    private final GitRebaseService rebaseService;

    public GitRebaseController(GitRebaseService rebaseService) {
        this.rebaseService = rebaseService;
    }

    @GetMapping("/status")
    public ApiResponse<RebaseStatusDto> status(@PathVariable("id") String repoId) {
        return ApiResponse.ok(rebaseService.status(repoId));
    }

    @PostMapping("/continue")
    public ApiResponse<Map<String, Object>> continueRebase(@PathVariable("id") String repoId) {
        rebaseService.continueRebase(repoId);
        return ApiResponse.ok(result("continued"));
    }

    @PostMapping("/skip")
    public ApiResponse<Map<String, Object>> skipRebase(@PathVariable("id") String repoId) {
        rebaseService.skipRebase(repoId);
        return ApiResponse.ok(result("skipped"));
    }

    @PostMapping("/abort")
    public ApiResponse<Map<String, Object>> abortRebase(@PathVariable("id") String repoId) {
        rebaseService.abortRebase(repoId);
        return ApiResponse.ok(result("aborted"));
    }

    private Map<String, Object> result(String action) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("action", action);
        return body;
    }
}
