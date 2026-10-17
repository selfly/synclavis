package com.sonsure.synclavis4j.fs.dto;

import java.util.List;

/**
 * 目录列表（见《详细设计文档》§6.2「文件系统（只读）」）。
 */
public class FsListDto {

    private String path;
    private String parent;
    private List<String> roots;
    private List<FsBreadcrumbDto> breadcrumbs;
    private List<FsEntryDto> entries;
    private boolean truncated;

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getParent() {
        return parent;
    }

    public void setParent(String parent) {
        this.parent = parent;
    }

    public List<String> getRoots() {
        return roots;
    }

    public void setRoots(List<String> roots) {
        this.roots = roots;
    }

    public List<FsBreadcrumbDto> getBreadcrumbs() {
        return breadcrumbs;
    }

    public void setBreadcrumbs(List<FsBreadcrumbDto> breadcrumbs) {
        this.breadcrumbs = breadcrumbs;
    }

    public List<FsEntryDto> getEntries() {
        return entries;
    }

    public void setEntries(List<FsEntryDto> entries) {
        this.entries = entries;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public void setTruncated(boolean truncated) {
        this.truncated = truncated;
    }
}
