package com.sonsure.synclavis4j.repo.dto;

import java.util.List;

/**
 * 重排 Tab 请求：{@code { ids }}，必须是当前全部 Tab id 的一个排列。
 */
public class ReorderSessionRequest {

    private List<String> ids;

    public List<String> getIds() {
        return ids;
    }

    public void setIds(List<String> ids) {
        this.ids = ids;
    }
}
