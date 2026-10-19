package com.sonsure.synclavis4j.state;

/**
 * 最近打开的仓库。
 */
public class RecentRepo {

    private String path;
    private String name;
    private String lastOpenedAt;

    public RecentRepo() {
    }

    public RecentRepo(String path, String name, String lastOpenedAt) {
        this.path = path;
        this.name = name;
        this.lastOpenedAt = lastOpenedAt;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLastOpenedAt() {
        return lastOpenedAt;
    }

    public void setLastOpenedAt(String lastOpenedAt) {
        this.lastOpenedAt = lastOpenedAt;
    }
}
