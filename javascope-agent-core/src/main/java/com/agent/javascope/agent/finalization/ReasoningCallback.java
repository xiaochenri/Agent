package com.agent.javascope.agent.finalization;

import java.util.Map;

/**
 * FinalAnswerSynthesizer 在轮次耗尽后需要再触发一次 reasoning 合成最终答案。
 */
public interface ReasoningCallback {
    /** 使用指定轮次号发起一次 reasoning。 */
    Map<String, Object> reason(int round);
}
