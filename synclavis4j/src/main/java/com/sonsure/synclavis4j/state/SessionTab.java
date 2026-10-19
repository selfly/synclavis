package com.sonsure.synclavis4j.state;

/**
 * 会话中的一个仓库 Tab。字段与《详细设计文档》附录 B 的状态文件格式一致。
 */
public class SessionTab {

    private String id;
    private String path;
    private String name;
    private String sshKeyPath;
    private String pullStrategy = "merge";
    private int order;

    public SessionTab() {
    }

    public SessionTab(String id, String path, String name, String sshKeyPath, String pullStrategy, int order) {
        this.id = id;
        this.path = path;
        this.name = name;
        this.sshKeyPath = sshKeyPath;
        this.pullStrategy = pullStrategy;
        this.order = order;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public String getSshKeyPath() {
        return sshKeyPath;
    }

    public void setSshKeyPath(String sshKeyPath) {
        this.sshKeyPath = sshKeyPath;
    }

    public String getPullStrategy() {
        return pullStrategy;
    }

    public void setPullStrategy(String pullStrategy) {
        this.pullStrategy = pullStrategy;
    }

    public int getOrder() {
        return order;
    }

    public void setOrder(int order) {
        this.order = order;
    }
}
