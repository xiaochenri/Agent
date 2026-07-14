package com.agent.javascope.tool.contract;

import com.agent.javascope.contract.plan.PlanOutputRequirement;
import com.agent.javascope.contract.tool.AgentToolDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 规划阶段检查 required_outputs 是否真实存在于业务工具声明的严格输出契约中。 */
public final class ToolOutputContractInspector {

    private ToolOutputContractInspector() {}

    public static List<String> validate(
            AgentToolDefinition definition, List<PlanOutputRequirement> requirements, String planPath) {
        List<String> errors = new ArrayList<>();
        if (definition == null || !definition.isStrictOutputContract()) {
            return errors;
        }
        for (int i = 0; i < requirements.size(); i++) {
            PlanOutputRequirement requirement = requirements.get(i);
            Map<String, Object> fieldSchema = resolve(definition.getOutputSchema(), requirement.getPath());
            String path = planPath + ".required_outputs[" + i + "]";
            if (fieldSchema.isEmpty()) {
                errors.add(path + ".path 不存在于工具 " + definition.getName() + " 的 output_schema: "
                        + requirement.getPath());
                continue;
            }
            if (!isCompatibleType(requirement.getType(), fieldSchema.get("type"))) {
                errors.add(path + ".type 与工具 output_schema 不兼容: " + requirement.getType());
            }
            if (!requirement.isNullable() && onlyNull(fieldSchema.get("type"))) {
                errors.add(path + " 要求非空，但工具输出契约只允许 null");
            }
        }
        return errors;
    }

    public static List<String> validateReference(
            AgentToolDefinition definition, String outputPath, String referencePath) {
        if (definition == null || !definition.isStrictOutputContract()) return List.of();
        return resolve(definition.getOutputSchema(), outputPath).isEmpty()
                ? List.of(referencePath + " 引用了工具 " + definition.getName()
                        + " 的 output_schema 中不存在的字段: " + outputPath)
                : List.of();
    }

    private static Map<String, Object> resolve(Map<String, Object> schema, String path) {
        Map<String, Object> current = schema == null ? Map.of() : schema;
        for (String segment : path == null ? new String[0] : path.split("\\.")) {
            Object properties = current.get("properties");
            if (!(properties instanceof Map<?, ?> propertyMap)
                    || !(propertyMap.get(segment) instanceof Map<?, ?> child)) {
                return Map.of();
            }
            current = stringMap(child);
        }
        return current;
    }

    private static boolean isCompatibleType(String requiredType, Object schemaType) {
        if (requiredType == null || requiredType.isBlank() || "any".equals(requiredType)) return true;
        if (schemaType instanceof List<?> types) {
            return types.stream().anyMatch(type -> requiredType.equals(String.valueOf(type)));
        }
        return requiredType.equals(String.valueOf(schemaType));
    }

    private static boolean onlyNull(Object schemaType) {
        if (schemaType instanceof List<?> types) {
            return types.size() == 1 && "null".equals(String.valueOf(types.get(0)));
        }
        return "null".equals(String.valueOf(schemaType));
    }

    private static Map<String, Object> stringMap(Map<?, ?> raw) {
        java.util.LinkedHashMap<String, Object> mapped = new java.util.LinkedHashMap<>();
        raw.forEach((key, value) -> mapped.put(String.valueOf(key), value));
        return mapped;
    }
}
