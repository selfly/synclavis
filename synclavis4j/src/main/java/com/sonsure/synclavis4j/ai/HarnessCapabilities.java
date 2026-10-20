package com.sonsure.synclavis4j.ai;

/**
 * harness 能力声明（见《详细设计文档》§6.5.1）。
 *
 * <p>能力必须如实声明：前端据此决定是否显示取消、权限、Diff 等入口，
 * 核心据此拒绝不支持的操作并返回 {@code UNSUPPORTED}。</p>
 */
public class HarnessCapabilities {

    private boolean prompt;
    private boolean streaming;
    private boolean cancel;
    private boolean resume;
    private boolean permissions;
    private boolean diff;
    private boolean tools;

    public boolean isPrompt() {
        return prompt;
    }

    public void setPrompt(boolean prompt) {
        this.prompt = prompt;
    }

    public boolean isStreaming() {
        return streaming;
    }

    public void setStreaming(boolean streaming) {
        this.streaming = streaming;
    }

    public boolean isCancel() {
        return cancel;
    }

    public void setCancel(boolean cancel) {
        this.cancel = cancel;
    }

    public boolean isResume() {
        return resume;
    }

    public void setResume(boolean resume) {
        this.resume = resume;
    }

    public boolean isPermissions() {
        return permissions;
    }

    public void setPermissions(boolean permissions) {
        this.permissions = permissions;
    }

    public boolean isDiff() {
        return diff;
    }

    public void setDiff(boolean diff) {
        this.diff = diff;
    }

    public boolean isTools() {
        return tools;
    }

    public void setTools(boolean tools) {
        this.tools = tools;
    }
}
