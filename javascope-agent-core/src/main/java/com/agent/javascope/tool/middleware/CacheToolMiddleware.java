package com.agent.javascope.tool.middleware;

import com.agent.javascope.tool.runtime.ToolExecutionResult;
import com.agent.javascope.tool.runtime.ToolInvocation;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 仅缓存只读且幂等工具的成功结果。 */
public class CacheToolMiddleware implements ToolMiddleware {

    private final ConcurrentMap<String, ToolExecutionResult> cache = new ConcurrentHashMap<>();

    @Override
    public ToolExecutionResult invoke(ToolExecutionContext context, ToolInvocation invocation, ToolInvocationChain chain) {
        if (!context.definition().isReadOnly() || !context.definition().isIdempotent()) {
            return chain.proceed(context, invocation);
        }
        String key = context.definition().getName() + "|" + context.definition().getVersion() + "|" + invocation.input();
        ToolExecutionResult cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        ToolExecutionResult result = chain.proceed(context, invocation);
        if (result.isSuccess()) {
            cache.putIfAbsent(key, result);
        }
        return result;
    }
}
