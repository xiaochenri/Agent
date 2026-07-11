package com.agent.javascope.context.trace;

import java.util.List;

/** 保存和检索完整执行轨迹的存储抽象。 */
public interface ExecutionLogStore {

    /**
     * 追加一条不可变执行事件。
     *
     * @param event 待保存的完整事件
     */
    void append(ExecutionEvent event);

    /**
     * 查询某次执行的所有事件，按事件序号返回。
     *
     * @param executionId 执行标识
     * @return 该执行的完整事件序列；不存在时返回空列表
     */
    List<ExecutionEvent> findByExecutionId(String executionId);
}
