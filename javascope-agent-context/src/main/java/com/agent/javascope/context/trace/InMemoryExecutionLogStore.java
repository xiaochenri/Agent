package com.agent.javascope.context.trace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 调试阶段默认实现；生产环境可替换为数据库或遥测适配器。 */
public class InMemoryExecutionLogStore implements ExecutionLogStore {

    /** 按执行标识分组保存的事件序列，适用于单进程调试。 */
    private final Map<String, List<ExecutionEvent>> events = Collections.synchronizedMap(new LinkedHashMap<>());

    /** {@inheritDoc} */
    @Override
    public void append(ExecutionEvent event) {
        events.computeIfAbsent(event.executionId(), ignored -> new ArrayList<>()).add(event);
    }

    /** {@inheritDoc} */
    @Override
    public List<ExecutionEvent> findByExecutionId(String executionId) {
        return List.copyOf(events.getOrDefault(executionId, List.of()));
    }
}
