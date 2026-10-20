package com.sonsure.synclavis4j.plugin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * 插件 manifest（见《详细设计文档》§6.4.1）。
 *
 * <p>只解析宿主需要的字段；未知字段由插件自行保留，宿主不拒绝。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PluginManifest {

    private String id;
    private String name;
    private String version;
    private Integer apiVersion;
    private String publisher;
    private String description;
    private Engines engines;
    private String entry;
    private String style;
    private Ui ui;
    private List<String> permissions = new ArrayList<String>();
    private List<String> capabilities = new ArrayList<String>();
    private JsonNode contributes;
    private Backend backend;

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

    public Engines getEngines() {
        return engines;
    }

    public void setEngines(Engines engines) {
        this.engines = engines;
    }

    public String getEntry() {
        return entry;
    }

    public void setEntry(String entry) {
        this.entry = entry;
    }

    public String getStyle() {
        return style;
    }

    public void setStyle(String style) {
        this.style = style;
    }

    public Ui getUi() {
        return ui;
    }

    public void setUi(Ui ui) {
        this.ui = ui;
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

    public JsonNode getContributes() {
        return contributes;
    }

    public void setContributes(JsonNode contributes) {
        this.contributes = contributes;
    }

    public Backend getBackend() {
        return backend;
    }

    public void setBackend(Backend backend) {
        this.backend = backend;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Engines {
        private String synclavis;

        public String getSynclavis() {
            return synclavis;
        }

        public void setSynclavis(String synclavis) {
            this.synclavis = synclavis;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Ui {
        private String isolation;

        public String getIsolation() {
            return isolation;
        }

        public void setIsolation(String isolation) {
            this.isolation = isolation;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Backend {
        private Integer protocol;
        private String transport;
        private String command;
        private List<String> args = new ArrayList<String>();

        public Integer getProtocol() {
            return protocol;
        }

        public void setProtocol(Integer protocol) {
            this.protocol = protocol;
        }

        public String getTransport() {
            return transport;
        }

        public void setTransport(String transport) {
            this.transport = transport;
        }

        public String getCommand() {
            return command;
        }

        public void setCommand(String command) {
            this.command = command;
        }

        public List<String> getArgs() {
            return args;
        }

        public void setArgs(List<String> args) {
            this.args = args == null ? new ArrayList<String>() : args;
        }
    }
}
