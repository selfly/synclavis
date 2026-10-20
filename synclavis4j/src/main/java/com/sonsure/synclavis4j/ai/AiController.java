package com.sonsure.synclavis4j.ai;

import com.sonsure.synclavis4j.shared.dto.ApiResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI harness 与会话接口（见《详细设计文档》§6.5.1）。
 *
 * <p>契约中不得出现具体 harness 实现名；前端只依赖本接口。</p>
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiSessionService aiSessions;

    public AiController(AiSessionService aiSessions) {
        this.aiSessions = aiSessions;
    }

    @GetMapping("/harnesses")
    public ApiResponse<HarnessListDto> harnesses() {
        return ApiResponse.ok(aiSessions.harnesses());
    }

    @PutMapping("/harnesses/active")
    public ApiResponse<Map<String, Object>> setActiveHarness(
            @RequestBody(required = false) SetActiveHarnessRequest request) {
        Map<String, String> active = aiSessions.setActiveHarness(
                request == null ? null : request.getPluginId(),
                request == null ? null : request.getHarnessId());
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("activeHarness", active);
        return ApiResponse.ok(data);
    }

    @GetMapping("/sessions")
    public ApiResponse<AiSessionListDto> sessions(
            @RequestParam(value = "repoId", required = false) String repoId) {
        return ApiResponse.ok(aiSessions.listSessions(repoId));
    }

    @PostMapping("/sessions")
    public ApiResponse<AiSessionDto> createSession(
            @RequestBody(required = false) CreateAiSessionRequest request) {
        return ApiResponse.ok(aiSessions.createSession(request));
    }

    @GetMapping("/sessions/{sessionRef}")
    public ApiResponse<AiSessionDto> getSession(@PathVariable("sessionRef") String sessionRef) {
        return ApiResponse.ok(aiSessions.getSession(sessionRef));
    }

    @DeleteMapping("/sessions/{sessionRef}")
    public ApiResponse<Map<String, Object>> deleteSession(
            @PathVariable("sessionRef") String sessionRef) {
        aiSessions.deleteSession(sessionRef);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("sessionRef", sessionRef);
        return ApiResponse.ok(body);
    }

    @PostMapping("/sessions/{sessionRef}/prompt")
    public ApiResponse<AiPromptResultDto> prompt(@PathVariable("sessionRef") String sessionRef,
                                                 @RequestBody(required = false) AiPromptRequest request) {
        AiPromptResultDto result = aiSessions.prompt(sessionRef, request);
        return ApiResponse.ok(result, result.getTurnId());
    }

    @PostMapping("/sessions/{sessionRef}/cancel")
    public ApiResponse<AiCancelResultDto> cancel(@PathVariable("sessionRef") String sessionRef,
                                                 @RequestBody(required = false) AiCancelRequest request) {
        boolean cancelled = aiSessions.cancel(sessionRef, request == null ? null : request.getTurnId());
        return ApiResponse.ok(new AiCancelResultDto(cancelled));
    }

    @PostMapping("/sessions/{sessionRef}/permissions/{requestId}")
    public ApiResponse<AiPermissionResultDto> respondPermission(
            @PathVariable("sessionRef") String sessionRef,
            @PathVariable("requestId") String requestId,
            @RequestBody(required = false) AiPermissionRequest request) {
        return ApiResponse.ok(aiSessions.respondPermission(sessionRef, requestId, request));
    }

    @GetMapping("/sessions/{sessionRef}/events")
    public ApiResponse<AiEventsDto> events(
            @PathVariable("sessionRef") String sessionRef,
            @RequestParam(value = "afterSeq", defaultValue = "0") long afterSeq,
            @RequestParam(value = "limit", defaultValue = "200") int limit) {
        return ApiResponse.ok(aiSessions.events(sessionRef, afterSeq, limit));
    }
}
