package com.sonsure.synclavis4j.ai;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 开发/联调用的确定性 harness（见《详细设计文档》§6.5.4）。
 *
 * <p>默认不注册；设置 {@code SYNCLAVIS_AI_MOCK_ENABLED=true} 后才会出现在 harness 列表中。
 * 它不访问文件系统，也不代表生产 AI 能力。</p>
 */
@Component
@ConditionalOnProperty(prefix = "synclavis.ai.mock", name = "enabled", havingValue = "true")
public class MockHarnessProvider implements HarnessProvider {

    private static final int CHUNK_SIZE = 8;

    private final HarnessDescriptor descriptor;
    private final long chunkDelayMillis;

    public MockHarnessProvider() {
        this(15L);
    }

    MockHarnessProvider(long chunkDelayMillis) {
        this.chunkDelayMillis = Math.max(0L, chunkDelayMillis);
        HarnessCapabilities capabilities = new HarnessCapabilities();
        capabilities.setPrompt(true);
        capabilities.setStreaming(true);
        capabilities.setCancel(true);
        this.descriptor = new HarnessDescriptor("mock", "Mock Harness", "0.1.0",
                true, null, capabilities);
    }

    @Override
    public HarnessDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public HarnessSessionHandle createSession(HarnessSessionRequest request) {
        return new HarnessSessionHandle("mock_" + UUID.randomUUID().toString().replace("-", ""));
    }

    @Override
    public void prompt(HarnessSessionHandle handle, HarnessPrompt prompt, HarnessEventSink sink) {
        String response = "Mock 回复：\n" + (prompt == null ? "" : prompt.getText());
        for (int offset = 0; offset < response.length(); offset += CHUNK_SIZE) {
            if (sink.isCancelled()) {
                throw HarnessException.cancelled();
            }
            int end = Math.min(response.length(), offset + CHUNK_SIZE);
            sink.messageDelta(response.substring(offset, end));
            sleep(chunkDelayMillis);
        }
        if (sink.isCancelled()) {
            throw HarnessException.cancelled();
        }
        sink.messageCompleted(response);
    }

    @Override
    public void cancel(HarnessSessionHandle handle) {
        // 取消由核心清除 currentTurnId + sink.isCancelled() 协作完成
    }

    private void sleep(long millis) {
        if (millis <= 0L) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw HarnessException.cancelled();
        }
    }
}
