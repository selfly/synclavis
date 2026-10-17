package com.sonsure.synclavis4j.git;

import com.sonsure.synclavis4j.git.dto.BranchInfoDto;
import com.sonsure.synclavis4j.git.dto.CheckoutRequest;
import com.sonsure.synclavis4j.git.dto.CommitDto;
import com.sonsure.synclavis4j.git.dto.CommitRequest;
import com.sonsure.synclavis4j.git.dto.CommitResultDto;
import com.sonsure.synclavis4j.git.dto.DiffResponseDto;
import com.sonsure.synclavis4j.git.dto.LogResponseDto;
import com.sonsure.synclavis4j.git.dto.PathsRequest;
import com.sonsure.synclavis4j.git.dto.RepoStatusDto;
import com.sonsure.synclavis4j.git.dto.StashCreateRequest;
import com.sonsure.synclavis4j.git.dto.StashInfoDto;
import com.sonsure.synclavis4j.git.dto.TagInfoDto;
import com.sonsure.synclavis4j.shared.dto.ApiResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仓库只读查询与暂存操作接口（见《详细设计文档》§6.2）。
 */
@RestController
@RequestMapping("/api/repos/{id}")
public class GitController {

    private final GitStatusService statusService;
    private final GitBranchService branchService;
    private final GitLogService logService;
    private final GitDiffService diffService;
    private final GitStashService stashService;
    private final GitIndexService indexService;
    private final GitCommitService commitService;

    public GitController(GitStatusService statusService,
                         GitBranchService branchService,
                         GitLogService logService,
                         GitDiffService diffService,
                         GitStashService stashService,
                         GitIndexService indexService,
                         GitCommitService commitService) {
        this.statusService = statusService;
        this.branchService = branchService;
        this.logService = logService;
        this.diffService = diffService;
        this.stashService = stashService;
        this.indexService = indexService;
        this.commitService = commitService;
    }

    @GetMapping("/status")
    public ApiResponse<RepoStatusDto> status(@PathVariable("id") String repoId) {
        return ApiResponse.ok(statusService.status(repoId));
    }

    @GetMapping("/branches")
    public ApiResponse<List<BranchInfoDto>> branches(@PathVariable("id") String repoId) {
        return ApiResponse.ok(branchService.branches(repoId));
    }

    /** 切换分支（`create=true` 时新建并切换）；同步返回切换后的仓库状态。 */
    @PostMapping("/checkout")
    public ApiResponse<RepoStatusDto> checkout(@PathVariable("id") String repoId,
                                               @RequestBody(required = false) CheckoutRequest request) {
        return ApiResponse.ok(branchService.checkout(repoId, request));
    }

    @GetMapping("/tags")
    public ApiResponse<List<TagInfoDto>> tags(@PathVariable("id") String repoId) {
        return ApiResponse.ok(branchService.tags(repoId));
    }

    @GetMapping("/log")
    public ApiResponse<LogResponseDto> log(@PathVariable("id") String repoId,
                                           @RequestParam(value = "skip", defaultValue = "0") int skip,
                                           @RequestParam(value = "limit", defaultValue = "100") int limit,
                                           @RequestParam(value = "author", required = false) String author,
                                           @RequestParam(value = "q", required = false) String keyword,
                                           @RequestParam(value = "ref", required = false) String ref) {
        return ApiResponse.ok(logService.log(repoId, skip, limit, author, keyword, ref));
    }

    @GetMapping("/commits/{commitId}/diff")
    public ApiResponse<DiffResponseDto> commitDiff(@PathVariable("id") String repoId,
                                                   @PathVariable("commitId") String commitId,
                                                   @RequestParam(value = "path", required = false) String path) {
        return ApiResponse.ok(diffService.commitDiff(repoId, commitId, path));
    }

    /**
     * 暂存区/工作区单文件差异：{@code staged=true} 为 HEAD↔索引，{@code false} 为 索引↔工作区。
     *
     * <p>见《详细设计文档》§6.2「暂存区/工作区单文件差异」。</p>
     */
    @GetMapping("/worktree/diff")
    public ApiResponse<DiffResponseDto> worktreeDiff(@PathVariable("id") String repoId,
                                                     @RequestParam("path") String path,
                                                     @RequestParam(value = "staged", defaultValue = "false") boolean staged) {
        return ApiResponse.ok(diffService.worktreeDiff(repoId, path, staged));
    }

    @GetMapping("/stash")
    public ApiResponse<List<StashInfoDto>> stashList(@PathVariable("id") String repoId) {
        return ApiResponse.ok(stashService.list(repoId));
    }

    @PostMapping("/stash")
    public ApiResponse<StashInfoDto> stashCreate(@PathVariable("id") String repoId,
                                                 @RequestBody(required = false) StashCreateRequest request) {
        return ApiResponse.ok(stashService.create(repoId, request));
    }

    @PostMapping("/stash/{index}/apply")
    public ApiResponse<Map<String, Object>> stashApply(@PathVariable("id") String repoId,
                                                       @PathVariable("index") int index) {
        stashService.apply(repoId, index);
        return ApiResponse.ok(stashResult(index, "applied"));
    }

    @PostMapping("/stash/{index}/drop")
    public ApiResponse<Map<String, Object>> stashDrop(@PathVariable("id") String repoId,
                                                      @PathVariable("index") int index) {
        stashService.drop(repoId, index);
        return ApiResponse.ok(stashResult(index, "dropped"));
    }

    @PostMapping("/stash/{index}/pop")
    public ApiResponse<Map<String, Object>> stashPop(@PathVariable("id") String repoId,
                                                     @PathVariable("index") int index) {
        stashService.pop(repoId, index);
        return ApiResponse.ok(stashResult(index, "popped"));
    }

    private Map<String, Object> stashResult(int index, String action) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("index", index);
        body.put("action", action);
        return body;
    }

    @PostMapping("/stage")
    public ApiResponse<Map<String, Object>> stage(@PathVariable("id") String repoId,
                                                  @RequestBody PathsRequest request) {
        List<String> paths = request == null ? null : request.getPaths();
        indexService.stage(repoId, paths);
        return ApiResponse.ok(count(paths, "staged"));
    }

    @PostMapping("/unstage")
    public ApiResponse<Map<String, Object>> unstage(@PathVariable("id") String repoId,
                                                    @RequestBody PathsRequest request) {
        List<String> paths = request == null ? null : request.getPaths();
        indexService.unstage(repoId, paths);
        return ApiResponse.ok(count(paths, "unstaged"));
    }

    @PostMapping("/commit")
    public ApiResponse<CommitResultDto> commit(@PathVariable("id") String repoId,
                                               @RequestBody CommitRequest request) {
        String message = request == null ? null : request.getMessage();
        boolean amend = request != null && request.isAmend();
        List<String> files = request == null ? null : request.getFiles();
        return ApiResponse.ok(commitService.commit(repoId, message, amend, files));
    }

    private Map<String, Object> count(List<String> paths, String key) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put(key, paths == null ? 0 : paths.size());
        return body;
    }
}
