package com.sonsure.synclavis4j.terminal;

import java.io.IOException;

/**
 * PTY 启动工厂；测试用假实现，生产用 {@link Pty4jTerminalProcessFactory}。
 */
public interface TerminalProcessFactory {

    TerminalProcess start(TerminalLaunchRequest request) throws IOException;
}
