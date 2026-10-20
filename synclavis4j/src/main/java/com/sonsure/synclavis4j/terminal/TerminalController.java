package com.sonsure.synclavis4j.terminal;

import com.sonsure.synclavis4j.shared.dto.ApiResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 终端会话接口（见《详细设计文档》§6.6.2）。
 *
 * <p>只负责会话登记与删除，真正的 PTY 在 WebSocket 连接建立后启动。</p>
 */
@RestController
@RequestMapping("/api/terminal")
public class TerminalController {

    private final TerminalSessionService terminals;

    public TerminalController(TerminalSessionService terminals) {
        this.terminals = terminals;
    }

    @PostMapping("/sessions")
    public ApiResponse<TerminalSessionDto> create(
            @RequestBody(required = false) CreateTerminalRequest request) {
        return ApiResponse.ok(terminals.create(request));
    }

    @DeleteMapping("/sessions/{terminalId}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable("terminalId") String terminalId) {
        terminals.delete(terminalId);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("terminalId", terminalId);
        return ApiResponse.ok(data);
    }
}
