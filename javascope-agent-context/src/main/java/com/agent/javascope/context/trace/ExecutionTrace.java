package com.agent.javascope.context.trace;

import com.agent.javascope.json.AgentJsonCodecUtil;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** 在不影响旧 executionLog 的前提下记录完整执行轨迹。 */
public class ExecutionTrace {

    /** 当前轨迹所属的执行标识。 */
    private final String executionId;
    /** 完整事件的实际持久化位置。 */
    private final ExecutionLogStore store;
    /** 将任意调试载荷转换为 JSON 树的编码器。 */
    private final AgentJsonCodecUtil json;
    /** 为同一执行生成稳定有序事件编号的计数器。 */
    private final AtomicLong sequence = new AtomicLong();

    /**
     * 创建一条与给定存储绑定的执行轨迹。
     *
     * @param executionId 本次执行的唯一标识
     * @param store 完整事件存储实现
     * @param json 调试载荷 JSON 编码器
     */
    public ExecutionTrace(String executionId, ExecutionLogStore store, AgentJsonCodecUtil json) {
        this.executionId = executionId;
        this.store = store;
        this.json = json;
    }

    /**
     * 将输入和输出完整序列化后追加到轨迹。
     *
     * @param type 本次事件类型
     * @param input 事件输入；允许为任意可序列化对象
     * @param output 事件输出；允许为任意可序列化对象
     */
    public void record(ExecutionEventType type, Object input, Object output) {
        store.append(new ExecutionEvent(
                executionId,
                sequence.incrementAndGet(),
                Instant.now(),
                type,
                json.toTree(input),
                json.toTree(output)));
    }

    /**
     * 返回当前执行已记录的完整事件，主要用于调试和回放。
     *
     * @return 按顺序排列的事件快照
     */
    public List<ExecutionEvent> events() {
        return store.findByExecutionId(executionId);
    }

    /**
     * 返回当前执行标识，调用方可据此查询完整轨迹。
     *
     * @return 本次 Agent 执行的唯一标识
     */
    public String executionId() {
        return executionId;
    }
}
