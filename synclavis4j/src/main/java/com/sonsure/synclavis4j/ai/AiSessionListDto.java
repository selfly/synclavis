package com.sonsure.synclavis4j.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code GET /api/ai/sessions} 的响应数据。
 */
public class AiSessionListDto {

    private List<AiSessionDto> sessions = new ArrayList<AiSessionDto>();

    public List<AiSessionDto> getSessions() {
        return sessions;
    }

    public void setSessions(List<AiSessionDto> sessions) {
        this.sessions = sessions == null ? new ArrayList<AiSessionDto>() : sessions;
    }
}
