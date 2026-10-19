package com.sonsure.synclavis4j.git;

import com.sonsure.synclavis4j.git.dto.RemoteAddRequest;
import com.sonsure.synclavis4j.git.dto.RemoteInfoDto;
import com.sonsure.synclavis4j.git.dto.RemoteUpdateRequest;
import com.sonsure.synclavis4j.shared.dto.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 远程仓库管理接口（见《详细设计文档》§6.2「远程仓库」）。
 *
 * <p>增 / 改 / 删都返回更新后的完整列表，前端一次刷新即可。</p>
 */
@RestController
@RequestMapping("/api/repos/{id}")
public class GitRemoteController {

    private final GitRemoteService remoteService;

    public GitRemoteController(GitRemoteService remoteService) {
        this.remoteService = remoteService;
    }

    @GetMapping("/remotes")
    public ApiResponse<List<RemoteInfoDto>> list(@PathVariable("id") String repoId) {
        return ApiResponse.ok(remoteService.list(repoId));
    }

    @PostMapping("/remotes")
    public ApiResponse<List<RemoteInfoDto>> add(@PathVariable("id") String repoId,
                                                @RequestBody RemoteAddRequest request) {
        return ApiResponse.ok(remoteService.add(repoId, request.getName(), request.getUrl()));
    }

    @PutMapping("/remotes/{name}")
    public ApiResponse<List<RemoteInfoDto>> update(@PathVariable("id") String repoId,
                                                   @PathVariable("name") String name,
                                                   @RequestBody RemoteUpdateRequest request) {
        return ApiResponse.ok(remoteService.updateUrl(repoId, name, request.getUrl()));
    }

    @DeleteMapping("/remotes/{name}")
    public ApiResponse<List<RemoteInfoDto>> remove(@PathVariable("id") String repoId,
                                                   @PathVariable("name") String name) {
        return ApiResponse.ok(remoteService.remove(repoId, name));
    }
}
