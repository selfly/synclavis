package com.sonsure.synclavis4j.fs.dto;

/**
 * 路径面包屑：由后端生成（根 → 当前），前端不自行拼接路径。
 */
public class FsBreadcrumbDto {

    private String name;
    private String path;

    public FsBreadcrumbDto() {
    }

    public FsBreadcrumbDto(String name, String path) {
        this.name = name;
        this.path = path;
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
}
