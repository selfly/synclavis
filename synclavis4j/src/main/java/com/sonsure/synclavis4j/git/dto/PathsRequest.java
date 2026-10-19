package com.sonsure.synclavis4j.git.dto;

import java.util.List;

/**
 * 暂存 / 取消暂存请求：{@code { paths: [...] }}。
 */
public class PathsRequest {

    private List<String> paths;

    public List<String> getPaths() {
        return paths;
    }

    public void setPaths(List<String> paths) {
        this.paths = paths;
    }
}
