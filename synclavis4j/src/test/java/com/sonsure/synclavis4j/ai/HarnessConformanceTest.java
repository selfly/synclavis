package com.sonsure.synclavis4j.ai;

import com.sonsure.synclavis4j.ai.AiTestSupport.AiSessions;
import com.sonsure.synclavis4j.shared.exception.GitOperationException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * harness 一致性测试（见《详细设计文档》§6.5）。
 *
 * <p>任何新的 {@link HarnessProvider} 都应能通过同一条断言链：描述完整、会话可创建、
 * 事件有序且以 {@code turn.completed} 收尾、取消能收敛、关闭后不可再访问。</p>
 */
public class HarnessConformanceTest {

    @Test
    void mockHarnessConforms() {
        assertConforms(new MockHarnessProvider(2L));
    }

    public static void assertConforms(HarnessProvider provider) {
        ExecutorService executor = AiTestSupport.singleThreadExecutor();
        try {
            AiSessionService service = AiTestSupport.service(
                    provider, new AiTestSupport.FakeStateStore(), executor);

            HarnessDescriptor descriptor = provider.descriptor();
            assertNotNull(descriptor);
            assertNotNull(descriptor.getId());
            assertFalse(descriptor.getId().trim().isEmpty());
            assertNotNull(descriptor.getCapabilities());
            assertTrue(descriptor.getCapabilities().isPrompt());

            CreateAiSessionRequest create = new CreateAiSessionRequest();
            create.setHarnessId(descriptor.getId());
            create.setRepoId("repo-conformance");
            create.setTitle("conformance");
            AiSessionDto session = service.createSession(create);
            String sessionRef = session.getSessionRef();
            assertTrue(sessionRef.startsWith("ai_"));
            assertEquals(descriptor.getId(), session.getHarnessId());
            assertEquals("idle", session.getStatus());
            assertNull(session.getCurrentTurnId());

            AiSessions first = runPrompt(service, sessionRef, "conformance hello");
            assertTurnShape(service, sessionRef, first.turn.get(0).getTurnId(), first, true);

            List<AiEvent> turn = first.turn;
            assertEquals(AiEventTypes.TURN_STARTED, turn.get(0).getType());
            assertEquals(AiEventTypes.TURN_COMPLETED, turn.get(turn.size() - 1).getType());
            if (descriptor.getCapabilities().isStreaming()) {
                assertTrue(hasType(turn, AiEventTypes.MESSAGE_DELTA),
                        "声明 streaming 的 harness 必须至少产生一条 message.delta");
            }
            assertTrue(hasType(turn, AiEventTypes.MESSAGE_COMPLETED),
                    "每轮必须产生 message.completed");

            long afterSeq = turn.get(0).getSeq();
            int remaining = turn.size() - 1;
            int replayLimit = Math.min(2, Math.max(1, remaining - 1));
            AiEventsDto replay = service.events(sessionRef, afterSeq, replayLimit);
            assertEquals(replayLimit, replay.getEvents().size());
            assertTrue(replay.getEvents().get(0).getSeq() > afterSeq);
            if (remaining > replayLimit) {
                assertTrue(replay.isTruncated(), "limit 截断时必须标记 truncated");
            }

            if (descriptor.getCapabilities().isCancel()) {
                AiPromptRequest longPrompt = new AiPromptRequest();
                longPrompt.setText(AiTestSupport.repeat("x", 20000));
                AiPromptResultDto pending = service.prompt(sessionRef, longPrompt);
                assertTrue(service.cancel(sessionRef, pending.getTurnId()),
                        "取消当前 turn 应成功");

                AiSessions cancelled = AiTestSupport.awaitTurn(
                        service, sessionRef, pending.getTurnId(), 5000L);
                assertTurnShape(service, sessionRef, pending.getTurnId(), cancelled, false);
                assertTrue(hasType(cancelled.turn, AiEventTypes.ERROR));
                assertEquals(Boolean.FALSE, cancelled.turn.get(cancelled.turn.size() - 1)
                        .getPayload().get("ok"));
                assertNull(service.getSession(sessionRef).getCurrentTurnId());
            }

            service.deleteSession(sessionRef);
            assertThrows(GitOperationException.class, () -> service.getSession(sessionRef));
        } finally {
            AiTestSupport.shutdown(executor);
        }
    }

    private static AiSessions runPrompt(AiSessionService service, String sessionRef, String text) {
        AiPromptRequest prompt = new AiPromptRequest();
        prompt.setText(text);
        AiPromptResultDto result = service.prompt(sessionRef, prompt);
        assertNotNull(result.getTurnId());
        AiSessions sessions = AiTestSupport.awaitTurn(
                service, sessionRef, result.getTurnId(), 5000L);
        assertTurnShape(service, sessionRef, result.getTurnId(), sessions, true);
        return sessions;
    }

    /**
     * 校验事件盖章与顺序：sessionRef/turnId 一致、seq 连续、时间非空、最后一条是 turn.completed。
     */
    private static void assertTurnShape(AiSessionService service, String sessionRef,
                                        String turnId, AiSessions sessions, boolean expectOk) {
        assertFalse(sessions.turn.isEmpty(), "turn 事件不能为空");
        long expected = sessions.turn.get(0).getSeq();
        for (AiEvent event : sessions.turn) {
            assertEquals(sessionRef, event.getSessionRef());
            assertEquals(turnId, event.getTurnId());
            assertEquals(expected++, event.getSeq(), "同一会话 seq 必须连续");
            assertNotNull(event.getAt());
            assertFalse(event.getAt().isEmpty());
            assertNotNull(event.getPayload());
        }
        AiEvent last = sessions.turn.get(sessions.turn.size() - 1);
        assertEquals(AiEventTypes.TURN_COMPLETED, last.getType());
        assertEquals(Boolean.valueOf(expectOk), last.getPayload().get("ok"));
        assertEquals(last.getSeq(), service.getSession(sessionRef).getLastSeq());
        assertEquals(last.getSeq(), sessions.all.getLastSeq());
    }

    private static boolean hasType(List<AiEvent> events, String type) {
        for (AiEvent event : events) {
            if (type.equals(event.getType())) {
                return true;
            }
        }
        return false;
    }
}
