package com.sonsure.synclavis4j.terminal;

import com.pty4j.PtyProcess;
import com.pty4j.WinSize;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * pty4j 进程包装。
 *
 * <p>0.12.10-jdk8 是最后一个 JDK 8 可用的 pty4j 版本；不要升级到 0.12.12+。</p>
 */
public class Pty4jTerminalProcess implements TerminalProcess {

    private static final Logger log = LoggerFactory.getLogger(Pty4jTerminalProcess.class);

    private final PtyProcess process;

    public Pty4jTerminalProcess(PtyProcess process) {
        this.process = process;
    }

    @Override
    public InputStream getInputStream() {
        return process.getInputStream();
    }

    @Override
    public OutputStream getOutputStream() {
        return process.getOutputStream();
    }

    @Override
    public void resize(int cols, int rows) throws IOException {
        process.setWinSize(new WinSize(cols, rows));
    }

    @Override
    public boolean isAlive() {
        return process.isAlive();
    }

    @Override
    public int waitFor() throws InterruptedException {
        return process.waitFor();
    }

    @Override
    public void destroy() {
        try {
            process.destroy();
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        } catch (RuntimeException e) {
            log.debug("关闭 PTY 进程失败 pid={}", process.pid(), e);
            process.destroyForcibly();
        }
    }

    @Override
    public long pid() {
        return process.pid();
    }
}
