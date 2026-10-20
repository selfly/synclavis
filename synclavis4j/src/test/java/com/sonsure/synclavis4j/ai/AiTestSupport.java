package com.sonsure.synclavis4j.ai;

import com.sonsure.synclavis4j.state.AppState;
import com.sonsure.synclavis4j.state.GlobalSettings;
import com.sonsure.synclavis4j.state.RepoSettings;
import com.sonsure.synclavis4j.state.SessionState;
import com.sonsure.synclavis4j.state.StateStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 测试夹具：内存状态、服务构造、事件等待。
 */
public final class AiTestSupport {

    private AiTestSupport() {
    }

    public static AiSessionService service(HarnessProvider provider, StateStore state, Executor executor) {
        return service(Collections.singletonList(provider), state, executor);
    }

    public static AiSessionService service(List<HarnessProvider> providers, StateStore state, Executor executor) {
        return new AiSessionService(
                new HarnessRegistry(providers),
                repoId -> "/tmp/" + repoId,
                state,
                event -> {
                },
                executor);
    }

    public static ExecutorService singleThreadExecutor() {
        return Executors.newSingleThreadExecutor();
    }

    public static void shutdown(ExecutorService executor) {
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static AiSessions awaitTurn(AiSessionService service, String sessionRef, String turnId,
                                long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        AiEventsDto events = service.events(sessionRef, 0, 1000);
        while (!containsCompleted(events, turnId) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            events = service.events(sessionRef, 0, 1000);
        }
        assertTrue(containsCompleted(events, turnId), "等待 AI turn.completed 超时: " + turnId);
        return new AiSessions(events, turnEvents(events, turnId));
    }

    public static List<AiEvent> turnEvents(AiEventsDto events, String turnId) {
        List<AiEvent> result = new ArrayList<AiEvent>();
        for (AiEvent event : events.getEvents()) {
            if (turnId.equals(event.getTurnId())) {
                result.add(event);
            }
        }
        return result;
    }

    public static boolean containsCompleted(AiEventsDto events, String turnId) {
        for (AiEvent event : events.getEvents()) {
            if (turnId.equals(event.getTurnId())
                    && AiEventTypes.TURN_COMPLETED.equals(event.getType())) {
                return true;
            }
        }
        return false;
    }

    public static String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }

    public static final class AiSessions {
        public final AiEventsDto all;
        public final List<AiEvent> turn;

        AiSessions(AiEventsDto all, List<AiEvent> turn) {
            this.all = all;
            this.turn = turn;
        }
    }

    public static final class FakeStateStore implements StateStore {

        private final AppState state = new AppState();

        @Override
        public AppState load() {
            return state;
        }

        @Override
        public void save(AppState state) {
            // 单测只关心内存状态
        }

        @Override
        public void updateSession(SessionState session) {
            state.setSession(session);
        }

        @Override
        public void updateGlobalSettings(GlobalSettings settings) {
            state.setGlobalSettings(settings);
        }

        @Override
        public void updateRepoSettings(String repoId, RepoSettings settings) {
            state.getRepos().put(repoId, settings);
        }
    }
}
