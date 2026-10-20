package com.sonsure.synclavis4j.ai;

import com.sonsure.synclavis4j.shared.exception.ErrorCode;
import com.sonsure.synclavis4j.shared.exception.GitOperationException;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 会话选择、busy、取消与能力门禁。
 */
class AiSessionServiceTest {

    @Test
    void selectsActiveHarnessForPluginAndPersistsIt() {
        AiTestSupport.FakeStateStore state = new AiTestSupport.FakeStateStore();
        ExecutorService executor = AiTestSupport.singleThreadExecutor();
        try {
            AiSessionService service = AiTestSupport.service(new MockHarnessProvider(1L), state, executor);
            service.setActiveHarness("plugin.demo", "mock");

            CreateAiSessionRequest request = new CreateAiSessionRequest();
            request.setPluginId("plugin.demo");
            request.setTitle("active harness");
            AiSessionDto session = service.createSession(request);

            assertEquals("mock", session.getHarnessId());
            assertEquals("mock", state.load().getGlobalSettings().getPlugins()
                    .getActiveHarness().get("plugin.demo"));
            service.deleteSession(session.getSessionRef());
        } finally {
            AiTestSupport.shutdown(executor);
        }
    }

    @Test
    void rejectsUnknownHarnessAndUnavailableRegistry() {
        ExecutorService executor = AiTestSupport.singleThreadExecutor();
        try {
            AiSessionService service = AiTestSupport.service(
                    new MockHarnessProvider(1L), new AiTestSupport.FakeStateStore(), executor);
            CreateAiSessionRequest unknown = new CreateAiSessionRequest();
            unknown.setHarnessId("missing");
            GitOperationException error = assertThrows(GitOperationException.class,
                    () -> service.createSession(unknown));
            assertEquals(ErrorCode.HARNESS_NOT_FOUND, error.getCode());

            AiSessionService empty = AiTestSupport.service(
                    Collections.<HarnessProvider>emptyList(),
                    new AiTestSupport.FakeStateStore(), executor);
            GitOperationException unavailable = assertThrows(GitOperationException.class,
                    () -> empty.createSession(new CreateAiSessionRequest()));
            assertEquals(ErrorCode.HARNESS_UNAVAILABLE, unavailable.getCode());
        } finally {
            AiTestSupport.shutdown(executor);
        }
    }

    @Test
    void rejectsSecondPromptWhileBusyAndCancels() {
        ExecutorService executor = AiTestSupport.singleThreadExecutor();
        try {
            AiSessionService service = AiTestSupport.service(
                    new MockHarnessProvider(20L), new AiTestSupport.FakeStateStore(), executor);
            AiSessionDto session = service.createSession(new CreateAiSessionRequest());

            AiPromptRequest longPrompt = new AiPromptRequest();
            longPrompt.setText(AiTestSupport.repeat("x", 20000));
            AiPromptResultDto pending = service.prompt(session.getSessionRef(), longPrompt);

            AiPromptRequest second = new AiPromptRequest();
            second.setText("second");
            GitOperationException busy = assertThrows(GitOperationException.class,
                    () -> service.prompt(session.getSessionRef(), second));
            assertEquals(ErrorCode.AI_BUSY, busy.getCode());

            assertTrue(service.cancel(session.getSessionRef(), pending.getTurnId()));
            AiTestSupport.AiSessions cancelled = AiTestSupport.awaitTurn(
                    service, session.getSessionRef(), pending.getTurnId(), 5000L);
            AiEvent last = cancelled.turn.get(cancelled.turn.size() - 1);
            assertEquals(AiEventTypes.TURN_COMPLETED, last.getType());
            assertEquals(Boolean.FALSE, last.getPayload().get("ok"));
            assertEquals("idle", service.getSession(session.getSessionRef()).getStatus());
        } finally {
            AiTestSupport.shutdown(executor);
        }
    }

    @Test
    void rejectsEmptyPromptAndPermissionWhenUnsupported() {
        ExecutorService executor = AiTestSupport.singleThreadExecutor();
        try {
            AiSessionService service = AiTestSupport.service(
                    new MockHarnessProvider(1L), new AiTestSupport.FakeStateStore(), executor);
            AiSessionDto session = service.createSession(new CreateAiSessionRequest());

            AiPromptRequest empty = new AiPromptRequest();
            empty.setText("   ");
            GitOperationException invalid = assertThrows(GitOperationException.class,
                    () -> service.prompt(session.getSessionRef(), empty));
            assertEquals(ErrorCode.INVALID_PARAM, invalid.getCode());

            AiPermissionRequest permission = new AiPermissionRequest();
            permission.setDecision("allow");
            GitOperationException unsupported = assertThrows(GitOperationException.class,
                    () -> service.respondPermission(session.getSessionRef(), "perm-1", permission));
            assertEquals(ErrorCode.UNSUPPORTED, unsupported.getCode());

            GitOperationException missing = assertThrows(GitOperationException.class,
                    () -> service.getSession("ai_missing"));
            assertEquals(ErrorCode.NOT_FOUND, missing.getCode());
        } finally {
            AiTestSupport.shutdown(executor);
        }
    }

    @Test
    void eventsAreEmptyAfterLastSeqAndSessionsCanBeFilteredByRepo() {
        AiTestSupport.FakeStateStore state = new AiTestSupport.FakeStateStore();
        ExecutorService executor = AiTestSupport.singleThreadExecutor();
        try {
            AiSessionService service = AiTestSupport.service(new MockHarnessProvider(1L), state, executor);
            CreateAiSessionRequest request = new CreateAiSessionRequest();
            request.setRepoId("repo-a");
            AiSessionDto session = service.createSession(request);

            AiPromptRequest prompt = new AiPromptRequest();
            prompt.setText("repo filter");
            AiPromptResultDto result = service.prompt(session.getSessionRef(), prompt);
            AiTestSupport.awaitTurn(service, session.getSessionRef(), result.getTurnId(), 5000L);

            AiEventsDto all = service.events(session.getSessionRef(), 0, 100);
            AiEvent started = all.getEvents().get(0);
            assertEquals(AiEventTypes.TURN_STARTED, started.getType());
            assertEquals("repo filter", started.getPayload().get("text"));

            AiEventsDto tail = service.events(session.getSessionRef(),
                    service.getSession(session.getSessionRef()).getLastSeq(), 100);
            assertTrue(tail.getEvents().isEmpty());
            assertFalse(tail.isTruncated());
            assertNotNull(service.listSessions("repo-a").getSessions().get(0));
            assertTrue(service.listSessions("repo-b").getSessions().isEmpty());
        } finally {
            AiTestSupport.shutdown(executor);
        }
    }
}
