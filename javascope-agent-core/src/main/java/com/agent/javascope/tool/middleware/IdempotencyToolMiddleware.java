package com.agent.javascope.tool.middleware;

import com.agent.javascope.tool.runtime.ToolExecutionResult;
import com.agent.javascope.tool.runtime.ToolInvocation;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 对显式幂等键的写工具复用首次结果，避免网络重试造成重复副作用。 */
public class IdempotencyToolMiddleware implements ToolMiddleware {

    private final ConcurrentMap<String, ToolExecutionResult> completed = new ConcurrentHashMap<>();

    @Override
    public ToolExecutionResult invoke(ToolExecutionContext context, ToolInvocation invocation, ToolInvocationChain chain) {
        if (context.definition().isReadOnly() || invocation.idempotencyKey().isBlank()) {
            return chain.proceed(context, invocation);
        }
        String key = context.definition().getName() + "|" + invocation.idempotencyKey();
        ToolExecutionResult existing = completed.get(key);
        if (existing != null) {
            return existing;
        }
        ToolExecutionResult result = chain.proceed(context, invocation);
        if (result.isSuccess()) {
            completed.putIfAbsent(key, result);
        }
        return result;
    }
}
