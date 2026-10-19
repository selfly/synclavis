package com.sonsure.synclavis4j.git.dto;

/**
 * 远程仓库配置（见《详细设计文档》§6.2「远程仓库」）。
 *
 * <p>{@code pushUrl} 只读：仅当 {@code remote.<name>.pushurl} 有配置时非空，
 * 本期不提供编辑。</p>
 */
public class RemoteInfoDto {

    private String name;
    private String url;
    private String pushUrl;

    public RemoteInfoDto() {
    }

    public RemoteInfoDto(String name, String url, String pushUrl) {
        this.name = name;
        this.url = url;
        this.pushUrl = pushUrl;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getPushUrl() {
        return pushUrl;
    }

    public void setPushUrl(String pushUrl) {
        this.pushUrl = pushUrl;
    }
}
