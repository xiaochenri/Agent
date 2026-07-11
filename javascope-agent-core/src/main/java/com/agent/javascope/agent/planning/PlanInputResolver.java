package com.agent.javascope.agent.planning;

import com.agent.javascope.entity.plan.PlanStepState;
import com.agent.javascope.plan.PlanStepStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 解析计划步骤对前序成功步骤输出的引用，并把同名证据字段补入空入参。 */
public final class PlanInputResolver {

    private PlanInputResolver() {}

    /**
     * 支持 {@code {"$ref":"steps.1.data.price"}}、{@code previous.data.price} 和
     * {@code tools.market_quote.data.price} 三类引用；步骤序号从 1 开始。
     */
    public static Map<String, Object> resolve(Map<String, Object> rawInput, int stepIndex, List<PlanStepState> steps) {
        Object resolved = resolveValue(rawInput == null ? Map.of() : rawInput, stepIndex, steps);
        Map<String, Object> input = resolved instanceof Map<?, ?> map ? copyMap(map) : new LinkedHashMap<>();
        Map<String, Object> priorData = collectPriorData(stepIndex, steps);
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            Object value = entry.getValue();
            if ((value == null || value instanceof String text && text.isBlank()) && priorData.containsKey(entry.getKey())) {
                entry.setValue(priorData.get(entry.getKey()));
            }
        }
        return input;
    }

    private static Object resolveValue(Object value, int stepIndex, List<PlanStepState> steps) {
        if (value instanceof Map<?, ?> map) {
            if (map.size() == 1 && map.get("$ref") instanceof String reference) {
                return resolveReference(reference, stepIndex, steps);
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), resolveValue(entry.getValue(), stepIndex, steps));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object item : list) {
                copy.add(resolveValue(item, stepIndex, steps));
            }
            return copy;
        }
        if (value instanceof String text && text.startsWith("${") && text.endsWith("}")) {
            return resolveReference(text.substring(2, text.length() - 1), stepIndex, steps);
        }
        return value;
    }

    private static Object resolveReference(String reference, int stepIndex, List<PlanStepState> steps) {
        String[] parts = reference == null ? new String[0] : reference.trim().split("\\.");
        if (parts.length < 2) {
            return null;
        }
        PlanStepState source = null;
        int pathStart;
        if ("previous".equals(parts[0]) && stepIndex > 0) {
            source = steps.get(stepIndex - 1);
            pathStart = 1;
        } else if ("steps".equals(parts[0]) && parts.length >= 3) {
            try {
                int sourceIndex = Integer.parseInt(parts[1]) - 1;
                if (sourceIndex >= 0 && sourceIndex < stepIndex) {
                    source = steps.get(sourceIndex);
                }
            } catch (NumberFormatException ignored) {
                // 无法解析的引用保留为 null，由工具入参校验与执行日志报告。
            }
            pathStart = 2;
        } else if ("tools".equals(parts[0]) && parts.length >= 3) {
            for (int i = stepIndex - 1; i >= 0; i--) {
                if (parts[1].equals(steps.get(i).getToolName()) && steps.get(i).getStatus() == PlanStepStatus.COMPLETED) {
                    source = steps.get(i);
                    break;
                }
            }
            pathStart = 2;
        } else {
            return null;
        }
        if (source == null || source.getStatus() != PlanStepStatus.COMPLETED) {
            return null;
        }
        Object current = source.getActualOutput();
        for (int i = pathStart; i < parts.length && current != null; i++) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(parts[i]);
            } else if (current instanceof List<?> list) {
                try {
                    current = list.get(Integer.parseInt(parts[i]));
                } catch (NumberFormatException | IndexOutOfBoundsException ignored) {
                    return null;
                }
            } else {
                return null;
            }
        }
        return current;
    }

    private static Map<String, Object> collectPriorData(int stepIndex, List<PlanStepState> steps) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (int i = 0; i < stepIndex; i++) {
            PlanStepState step = steps.get(i);
            if (step.getStatus() != PlanStepStatus.COMPLETED) {
                continue;
            }
            Object outputData = step.getActualOutput().get("data");
            if (outputData instanceof Map<?, ?> map) {
                data.putAll(copyMap(map));
            }
        }
        return data;
    }

    private static Map<String, Object> copyMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            copy.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return copy;
    }
}
