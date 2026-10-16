package com.sonsure.synclavis4j.operation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 进行中操作的登记表：operationId -> {@link OperationContext}。
 *
 * <p>用于把异步操作与 WebSocket 进度事件、取消请求关联起来。</p>
 */
@Component
public class OperationRegistry {

    private final Map<String, OperationContext> operations =
            new ConcurrentHashMap<String, OperationContext>();

    public String begin(String repoId, String kind) {
        String operationId = "op-" + UUID.randomUUID().toString().substring(0, 8);
        operations.put(operationId, new OperationContext(operationId, repoId, kind));
        return operationId;
    }

    public OperationContext get(String operationId) {
        return operations.get(operationId);
    }

    public void finish(String operationId) {
        OperationContext context = operations.get(operationId);
        if (context != null && context.getStatus() == OperationContext.Status.RUNNING) {
            context.setStatus(OperationContext.Status.SUCCEEDED);
        }
    }

    public void fail(String operationId, String code, String message) {
        OperationContext context = operations.get(operationId);
        if (context != null) {
            context.setStatus(OperationContext.Status.FAILED);
            context.setErrorCode(code);
            context.setErrorMessage(message);
        }
    }

    /** 请求取消；实际中止由 JGit ProgressMonitor.isCancelled() 检查点完成。 */
    public boolean cancel(String operationId) {
        OperationContext context = operations.get(operationId);
        if (context == null) {
            return false;
        }
        context.requestCancel();
        return true;
    }

    public boolean isCancelled(String operationId) {
        OperationContext context = operations.get(operationId);
        return context != null && context.isCancelRequested();
    }

    public List<OperationContext> byRepo(String repoId) {
        List<OperationContext> result = new ArrayList<OperationContext>();
        for (OperationContext context : operations.values()) {
            if (context.getRepoId().equals(repoId)) {
                result.add(context);
            }
        }
        return result;
    }

    public int size() {
        return operations.size();
    }
}
