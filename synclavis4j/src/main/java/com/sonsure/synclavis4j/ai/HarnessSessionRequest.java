package com.sonsure.synclavis4j.ai;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 创建 harness 会话的入参。
 *
 * <p>{@code repoPath} 由宿主根据已打开仓库解析，客户端不能直接指定工作目录。</p>
 */
public class HarnessSessionRequest {

    private String hostSessionRef;
    private String pluginId;
    private String repoId;
    private String repoPath;
    private String title;
    private Map<String, Object> metadata = new LinkedHashMap<String, Object>();

    public String getHostSessionRef() {
        return hostSessionRef;
    }

    public void setHostSessionRef(String hostSessionRef) {
        this.hostSessionRef = hostSessionRef;
    }

    public String getPluginId() {
        return pluginId;
    }

    public void setPluginId(String pluginId) {
        this.pluginId = pluginId;
    }

    public String getRepoId() {
        return repoId;
    }

    public void setRepoId(String repoId) {
        this.repoId = repoId;
    }

    public String getRepoPath() {
        return repoPath;
    }

    public void setRepoPath(String repoPath) {
        this.repoPath = repoPath;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata == null ? new LinkedHashMap<String, Object>() : metadata;
    }
}
