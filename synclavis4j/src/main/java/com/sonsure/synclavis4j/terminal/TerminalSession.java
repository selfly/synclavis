package com.sonsure.synclavis4j.terminal;

import java.util.List;
import java.util.Map;

/**
 * 运行时终端会话。字段由 {@link TerminalSessionService} 的会话锁保护。
 */
class TerminalSession {

    private final String terminalId;
    private final String repoId;
    private final String shell;
    private final List<String> args;
    private final String cwd;
    private final Map<String, String> environment;
    private final long createdAt;

    private int cols;
    private int rows;
    private String ownerId;
    private TerminalProcess process;
    private Thread readerThread;
    private volatile boolean closing;

    TerminalSession(String terminalId, String repoId, String shell, List<String> args,
                    String cwd, Map<String, String> environment, int cols, int rows) {
        this.terminalId = terminalId;
        this.repoId = repoId;
        this.shell = shell;
        this.args = args;
        this.cwd = cwd;
        this.environment = environment;
        this.cols = cols;
        this.rows = rows;
        this.createdAt = System.currentTimeMillis();
    }

    String getTerminalId() {
        return terminalId;
    }

    String getRepoId() {
        return repoId;
    }

    String getShell() {
        return shell;
    }

    List<String> getArgs() {
        return args;
    }

    String getCwd() {
        return cwd;
    }

    Map<String, String> getEnvironment() {
        return environment;
    }

    int getCols() {
        return cols;
    }

    void setCols(int cols) {
        this.cols = cols;
    }

    int getRows() {
        return rows;
    }

    void setRows(int rows) {
        this.rows = rows;
    }

    long getCreatedAt() {
        return createdAt;
    }

    String getOwnerId() {
        return ownerId;
    }

    void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    TerminalProcess getProcess() {
        return process;
    }

    void setProcess(TerminalProcess process) {
        this.process = process;
    }

    Thread getReaderThread() {
        return readerThread;
    }

    void setReaderThread(Thread readerThread) {
        this.readerThread = readerThread;
    }

    boolean isClosing() {
        return closing;
    }

    void setClosing(boolean closing) {
        this.closing = closing;
    }

    TerminalSessionDto toDto() {
        TerminalSessionDto dto = new TerminalSessionDto();
        dto.setTerminalId(terminalId);
        dto.setRepoId(repoId);
        dto.setShell(shell);
        dto.setCwd(cwd);
        dto.setCols(cols);
        dto.setRows(rows);
        return dto;
    }
}
