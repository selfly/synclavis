package com.sonsure.synclavis4j.fs.dto;

/**
 * 文件系统条目（仅元信息，不含文件内容）。
 */
public class FsEntryDto {

    private String name;
    private String path;
    private boolean dir;
    private boolean repo;
    private boolean hidden;

    public FsEntryDto() {
    }

    public FsEntryDto(String name, String path, boolean dir, boolean repo, boolean hidden) {
        this.name = name;
        this.path = path;
        this.dir = dir;
        this.repo = repo;
        this.hidden = hidden;
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

    public boolean isRepo() {
        return repo;
    }

    public void setRepo(boolean repo) {
        this.repo = repo;
    }

    public boolean isHidden() {
        return hidden;
    }

    public void setHidden(boolean hidden) {
        this.hidden = hidden;
    }
}
