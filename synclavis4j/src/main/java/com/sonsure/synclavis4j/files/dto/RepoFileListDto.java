package com.sonsure.synclavis4j.files.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 仓库目录列表（见《详细设计文档》§6.2「仓库文件浏览（只读）」）。
 */
public class RepoFileListDto {

    /** 当前目录的仓库内相对路径，仓库根为空串 */
    private String path;
    private List<RepoFileEntryDto> entries = new ArrayList<RepoFileEntryDto>();
    /** 条目数超过上限时为 true */
    private boolean truncated;

    public RepoFileListDto() {
    }

    public RepoFileListDto(String path, List<RepoFileEntryDto> entries, boolean truncated) {
        this.path = path;
        this.entries = entries;
        this.truncated = truncated;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public List<RepoFileEntryDto> getEntries() {
        return entries;
    }

    public void setEntries(List<RepoFileEntryDto> entries) {
        this.entries = entries;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public void setTruncated(boolean truncated) {
        this.truncated = truncated;
    }
}
