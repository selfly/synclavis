package com.sonsure.synclavis4j.terminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 已启动的 PTY 进程抽象。
 *
 * <p>把 pty4j 隔离在实现类里，服务层与单测可以用假进程验证生命周期、上限与清理。</p>
 */
public interface TerminalProcess {

    InputStream getInputStream();

    OutputStream getOutputStream();

    void resize(int cols, int rows) throws IOException;

    boolean isAlive();

    int waitFor() throws InterruptedException;

    void destroy();

    long pid();
}
