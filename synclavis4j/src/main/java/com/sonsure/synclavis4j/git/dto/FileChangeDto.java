package com.sonsure.synclavis4j.git.dto;

/**
 * 文件变更项。{@code changeType} ∈ ADD / MODIFY / DELETE / RENAME / COPY。
 */
public class FileChangeDto {

    private String path;
    private String changeType;
    private String oldPath;

    public FileChangeDto() {
    }

    public FileChangeDto(String path, String changeType) {
        this.path = path;
        this.changeType = changeType;
    }

    public FileChangeDto(String path, String changeType, String oldPath) {
        this.path = path;
        this.changeType = changeType;
        this.oldPath = oldPath;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getChangeType() {
        return changeType;
    }

    public void setChangeType(String changeType) {
        this.changeType = changeType;
    }

    public String getOldPath() {
        return oldPath;
    }

    public void setOldPath(String oldPath) {
        this.oldPath = oldPath;
    }
}
