package com.sonsure.synclavis4j.terminal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 启动 PTY 所需的宿主侧参数；字段全部由服务端生成，客户端不可直接控制。
 */
public class TerminalLaunchRequest {

    private final String shell;
    private final List<String> args;
    private final String cwd;
    private final Map<String, String> environment;
    private final int cols;
    private final int rows;

    public TerminalLaunchRequest(String shell, List<String> args, String cwd,
                                 Map<String, String> environment, int cols, int rows) {
        this.shell = shell;
        this.args = args == null ? Collections.<String>emptyList() : args;
        this.cwd = cwd;
        this.environment = environment == null
                ? new LinkedHashMap<String, String>()
                : new LinkedHashMap<String, String>(environment);
        this.cols = cols;
        this.rows = rows;
    }

    public String getShell() {
        return shell;
    }

    public List<String> getArgs() {
        return args;
    }

    public String getCwd() {
        return cwd;
    }

    public Map<String, String> getEnvironment() {
        return environment;
    }

    public int getCols() {
        return cols;
    }

    public int getRows() {
        return rows;
    }

    /** 拼成 {@code ProcessBuilder} 需要的 argv。 */
    public String[] command() {
        String[] command = new String[args.size() + 1];
        command[0] = shell;
        for (int i = 0; i < args.size(); i++) {
            command[i + 1] = args.get(i);
        }
        return command;
    }
}
