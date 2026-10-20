package com.sonsure.synclavis4j.terminal;

/**
 * 终端输出回调；由 WebSocket 处理器实现，服务层不直接依赖 Spring WebSocket。
 */
public interface TerminalOutputSink {

    void output(String data);

    void exit(int code);

    void error(String code, String message);
}
