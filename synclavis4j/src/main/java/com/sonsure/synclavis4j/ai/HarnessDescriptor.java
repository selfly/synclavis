package com.sonsure.synclavis4j.ai;

/**
 * harness 描述（见《详细设计文档》§6.5.1 的 {@code HarnessInfo}）。
 *
 * <p>{@code id} 是稳定标识，不得随版本变化；{@code available} 可以在运行时变化，
 * 例如依赖的外部服务尚未安装或未认证。</p>
 */
public class HarnessDescriptor {

    private String id;
    private String name;
    private String version;
    private boolean available;
    private String reason;
    private HarnessCapabilities capabilities = new HarnessCapabilities();

    public HarnessDescriptor() {
    }

    public HarnessDescriptor(String id, String name, String version, boolean available,
                             String reason, HarnessCapabilities capabilities) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.available = available;
        this.reason = reason;
        this.capabilities = capabilities == null ? new HarnessCapabilities() : capabilities;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public boolean isAvailable() {
        return available;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public HarnessCapabilities getCapabilities() {
        return capabilities;
    }

    public void setCapabilities(HarnessCapabilities capabilities) {
        this.capabilities = capabilities == null ? new HarnessCapabilities() : capabilities;
    }
}
