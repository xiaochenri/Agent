package com.agent.javascope.tool.contract;

import com.agent.javascope.contract.plan.PlanStepDefinition;
import com.agent.javascope.contract.tool.AgentToolDefinition;
import com.agent.javascope.tool.runtime.AgentToolExecutor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** 创建和修订计划共用的工具输出引用校验器。 */
public final class PlanToolReferenceInspector {

    private PlanToolReferenceInspector() {}

    public static List<String> validate(List<PlanStepDefinition> plan, AgentToolExecutor toolExecutor) {
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < plan.size(); i++) {
            validateValue(plan.get(i).getInput(), plan, i, "plan[" + i + "].input", toolExecutor, errors);
        }
        return errors;
    }

    private static void validateValue(
            Object value,
            List<PlanStepDefinition> plan,
            int currentIndex,
            String path,
            AgentToolExecutor toolExecutor,
            List<String> errors) {
        if (value instanceof Map<?, ?> map) {
            if (map.size() == 1 && map.get("$ref") instanceof String reference) {
                validateReference(reference, plan, currentIndex, path + ".$ref", toolExecutor, errors);
                return;
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                validateValue(entry.getValue(), plan, currentIndex,
                        path + "." + entry.getKey(), toolExecutor, errors);
            }
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                validateValue(list.get(i), plan, currentIndex, path + "[" + i + "]", toolExecutor, errors);
            }
        }
    }

    private static void validateReference(
            String reference,
            List<PlanStepDefinition> plan,
            int currentIndex,
            String path,
            AgentToolExecutor toolExecutor,
            List<String> errors) {
        String[] parts = reference == null ? new String[0] : reference.split("\\.");
        AgentToolDefinition sourceDefinition;
        String outputPath;
        if (parts.length >= 3 && "steps".equals(parts[0])) {
            try {
                int sourceIndex = Integer.parseInt(parts[1]) - 1;
                if (sourceIndex < 0 || sourceIndex >= currentIndex) {
                    errors.add(path + " 必须引用已定义的前序步骤: " + reference);
                    return;
                }
                sourceDefinition = toolExecutor.getToolDefinition(plan.get(sourceIndex).getTool());
                outputPath = String.join(".", Arrays.copyOfRange(parts, 2, parts.length));
            } catch (NumberFormatException e) {
                errors.add(path + " 步骤序号非法: " + reference);
                return;
            }
        } else if (parts.length >= 2 && "previous".equals(parts[0]) && currentIndex > 0) {
            sourceDefinition = toolExecutor.getToolDefinition(plan.get(currentIndex - 1).getTool());
            outputPath = String.join(".", Arrays.copyOfRange(parts, 1, parts.length));
        } else if (parts.length >= 3 && "tools".equals(parts[0])) {
            sourceDefinition = toolExecutor.getToolDefinition(parts[1]);
            outputPath = String.join(".", Arrays.copyOfRange(parts, 2, parts.length));
        } else {
            errors.add(path + " 引用格式非法: " + reference);
            return;
        }
        errors.addAll(ToolOutputContractInspector.validateReference(sourceDefinition, outputPath, path));
    }
}
