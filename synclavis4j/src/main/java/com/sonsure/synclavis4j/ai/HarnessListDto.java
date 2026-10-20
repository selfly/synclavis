package com.sonsure.synclavis4j.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/ai/harnesses} 的响应数据。
 */
public class HarnessListDto {

    private List<HarnessDescriptor> harnesses = new ArrayList<HarnessDescriptor>();
    private Map<String, String> activeHarness = new LinkedHashMap<String, String>();

    public List<HarnessDescriptor> getHarnesses() {
        return harnesses;
    }

    public void setHarnesses(List<HarnessDescriptor> harnesses) {
        this.harnesses = harnesses == null ? new ArrayList<HarnessDescriptor>() : harnesses;
    }

    public Map<String, String> getActiveHarness() {
        return activeHarness;
    }

    public void setActiveHarness(Map<String, String> activeHarness) {
        this.activeHarness = activeHarness == null
                ? new LinkedHashMap<String, String>()
                : activeHarness;
    }
}
