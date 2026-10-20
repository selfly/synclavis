package com.sonsure.synclavis4j.ai;

/**
 * AI 归一化事件类型（见《详细设计文档》§6.5.2）。
 *
 * <p>这些字符串是跨后端契约的一部分，不得改名；新增类型必须先更新文档。</p>
 */
public final class AiEventTypes {

    public static final String TURN_STARTED = "turn.started";
    public static final String MESSAGE_DELTA = "message.delta";
    public static final String MESSAGE_COMPLETED = "message.completed";
    public static final String TOOL_STARTED = "tool.started";
    public static final String TOOL_COMPLETED = "tool.completed";
    public static final String PERMISSION_REQUESTED = "permission.requested";
    public static final String PERMISSION_RESOLVED = "permission.resolved";
    public static final String DIFF_UPDATED = "diff.updated";
    public static final String ERROR = "error";
    public static final String TURN_COMPLETED = "turn.completed";

    private AiEventTypes() {
    }
}
