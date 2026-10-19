package com.sonsure.synclavis4j.fs;

import com.sonsure.synclavis4j.fs.dto.FsListDto;
import com.sonsure.synclavis4j.shared.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文件系统接口（只读，见《详细设计文档》§6.2）。
 */
@RestController
@RequestMapping("/api")
public class FsController {

    private final FsBrowseService fsBrowseService;

    public FsController(FsBrowseService fsBrowseService) {
        this.fsBrowseService = fsBrowseService;
    }

    @GetMapping("/fs/list")
    public ApiResponse<FsListDto> list(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "kind", required = false) String kind) {
        return ApiResponse.ok(fsBrowseService.list(path, kind));
    }
}
