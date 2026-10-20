package com.sonsure.synclavis4j.ai;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code POST /api/ai/sessions/{ref}/prompt} 请求。
 */
public class AiPromptRequest {

    private String text;
    private String model;
    private Map<String, Object> metadata = new LinkedHashMap<String, Object>();

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata == null
                ? new LinkedHashMap<String, Object>()
                : metadata;
    }
}
