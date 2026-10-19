package com.sonsure.synclavis4j.files.dto;

/**
 * 仓库文件树条目（见《详细设计文档》§6.2「仓库文件浏览（只读）」）。
 *
 * <p>{@code path} 为仓库内相对路径（统一使用 {@code /}），前端据此懒加载子目录。</p>
 */
public class RepoFileEntryDto {

    private String name;
    private String path;
    private boolean dir;
    private long size;
    /** 命中 .gitignore / .git/info/exclude 时为 true（仅在 showIgnored=true 时才会返回） */
    private boolean ignored;
    private boolean symlink;

    public RepoFileEntryDto() {
    }

    public RepoFileEntryDto(String name, String path, boolean dir, long size, boolean ignored, boolean symlink) {
        this.name = name;
        this.path = path;
        this.dir = dir;
        this.size = size;
        this.ignored = ignored;
        this.symlink = symlink;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public boolean isDir() {
        return dir;
    }

    public void setDir(boolean dir) {
        this.dir = dir;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public boolean isIgnored() {
        return ignored;
    }

    public void setIgnored(boolean ignored) {
        this.ignored = ignored;
    }

    public boolean isSymlink() {
        return symlink;
    }

    public void setSymlink(boolean symlink) {
        this.symlink = symlink;
    }
}
