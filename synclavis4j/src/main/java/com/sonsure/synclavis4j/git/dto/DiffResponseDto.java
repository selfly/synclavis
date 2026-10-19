package com.sonsure.synclavis4j.git.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 提交 Diff 响应：结构化条目列表 + 可选单文件 patch 文本。
 */
public class DiffResponseDto {

    private List<DiffEntryDto> entries = new ArrayList<DiffEntryDto>();
    private String patch;

    public DiffResponseDto() {
    }

    public DiffResponseDto(List<DiffEntryDto> entries, String patch) {
        this.entries = entries;
        this.patch = patch;
    }

    public List<DiffEntryDto> getEntries() {
        return entries;
    }

    public void setEntries(List<DiffEntryDto> entries) {
        this.entries = entries;
    }

    public String getPatch() {
        return patch;
    }

    public void setPatch(String patch) {
        this.patch = patch;
    }
}
