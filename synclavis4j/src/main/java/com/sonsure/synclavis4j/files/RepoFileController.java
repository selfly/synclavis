package com.sonsure.synclavis4j.files;

import com.sonsure.synclavis4j.files.dto.RepoFileContentDto;
import com.sonsure.synclavis4j.files.dto.RepoFileListDto;
import com.sonsure.synclavis4j.files.dto.RepoFileSearchDto;
import com.sonsure.synclavis4j.shared.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仓库内文件浏览接口（只读，见《详细设计文档》§6.2「仓库文件浏览（只读）」）。
 */
@RestController
@RequestMapping("/api/repos/{id}/files")
public class RepoFileController {

    private final RepoFileService repoFileService;

    public RepoFileController(RepoFileService repoFileService) {
        this.repoFileService = repoFileService;
    }

    /** 列目录：{@code path} 省略为仓库根，默认过滤 .gitignore 忽略项。 */
    @GetMapping
    public ApiResponse<RepoFileListDto> list(@PathVariable("id") String repoId,
                                             @RequestParam(value = "path", required = false) String path,
                                             @RequestParam(value = "showIgnored", defaultValue = "false") boolean showIgnored) {
        return ApiResponse.ok(repoFileService.list(repoId, path, showIgnored));
    }

    /** 读文件内容：二进制或超大文件不返回内容，由标记位提示。 */
    @GetMapping("/content")
    public ApiResponse<RepoFileContentDto> content(@PathVariable("id") String repoId,
                                                   @RequestParam("path") String path) {
        return ApiResponse.ok(repoFileService.content(repoId, path));
    }

    /** 全仓库搜索（命中未展开的目录），返回扁平的匹配文件列表。 */
    @GetMapping("/search")
    public ApiResponse<RepoFileSearchDto> search(@PathVariable("id") String repoId,
                                                 @RequestParam(value = "q", required = false) String query,
                                                 @RequestParam(value = "showIgnored", defaultValue = "false")
                                                 boolean showIgnored) {
        return ApiResponse.ok(repoFileService.search(repoId, query, showIgnored));
    }
}
