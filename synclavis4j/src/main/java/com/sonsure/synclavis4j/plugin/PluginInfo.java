package com.sonsure.synclavis4j.plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * 返回给前端的插件信息（见《详细设计文档》§6.4.3）。
 */
public class PluginInfo {

    private String id;
    private String name;
    private String version;
    private Integer apiVersion;
    private String publisher;
    private String description;
    private String source = "local";
    private boolean enabled;
    private boolean compatible;
    private String error;
    private List<String> permissions = new ArrayList<String>();
    private List<String> capabilities = new ArrayList<String>();
    private String assetBase;
    private PluginManifest manifest;

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

    public Integer getApiVersion() {
        return apiVersion;
    }

    public void setApiVersion(Integer apiVersion) {
        this.apiVersion = apiVersion;
    }

    public String getPublisher() {
        return publisher;
    }

    public void setPublisher(String publisher) {
        this.publisher = publisher;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isCompatible() {
        return compatible;
    }

    public void setCompatible(boolean compatible) {
        this.compatible = compatible;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public List<String> getPermissions() {
        return permissions;
    }

    public void setPermissions(List<String> permissions) {
        this.permissions = permissions == null ? new ArrayList<String>() : permissions;
    }

    public List<String> getCapabilities() {
        return capabilities;
    }

    public void setCapabilities(List<String> capabilities) {
        this.capabilities = capabilities == null ? new ArrayList<String>() : capabilities;
    }

    public String getAssetBase() {
        return assetBase;
    }

    public void setAssetBase(String assetBase) {
        this.assetBase = assetBase;
    }

    public PluginManifest getManifest() {
        return manifest;
    }

    public void setManifest(PluginManifest manifest) {
        this.manifest = manifest;
    }
}
