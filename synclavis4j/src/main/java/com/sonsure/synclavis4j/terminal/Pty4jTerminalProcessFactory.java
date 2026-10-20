package com.sonsure.synclavis4j.terminal;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import java.io.IOException;
import org.springframework.stereotype.Component;

/**
 * 生产 PTY 工厂：使用 pty4j 创建真正的伪终端。
 */
@Component
public class Pty4jTerminalProcessFactory implements TerminalProcessFactory {

    @Override
    public TerminalProcess start(TerminalLaunchRequest request) throws IOException {
        PtyProcessBuilder builder = new PtyProcessBuilder(request.command())
                .setEnvironment(request.getEnvironment())
                .setDirectory(request.getCwd())
                .setInitialColumns(Integer.valueOf(request.getCols()))
                .setInitialRows(Integer.valueOf(request.getRows()))
                .setRedirectErrorStream(true);
        PtyProcess process = builder.start();
        return new Pty4jTerminalProcess(process);
    }
}
