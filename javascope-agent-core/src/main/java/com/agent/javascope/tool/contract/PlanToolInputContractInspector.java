package com.agent.javascope.tool.contract;

import com.agent.javascope.contract.tool.AgentToolDefinition;
import com.agent.javascope.json.AgentJsonCodecUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 规划阶段校验每个工具步骤的字面输入字段；$ref 的来源与类型由引用检查器校验。 */
public final class PlanToolInputContractInspector {

    private static final JsonSchemaToolContractValidator VALIDATOR = new JsonSchemaToolContractValidator();
    private static final AgentJsonCodecUtil JSON = new AgentJsonCodecUtil();

    private PlanToolInputContractInspector() {}

    public static List<String> validate(
            AgentToolDefinition definition, Map<String, Object> input, String planPath) {
        if (definition == null || definition.getInputSchema().isEmpty()) {
            return List.of();
        }
        Map<String, Object> schema = definition.getInputSchema();
        Map<String, Object> properties = schema.get("properties") instanceof Map<?, ?> raw
                ? stringMap(raw)
                : Map.of();
        Map<String, Object> values = input == null ? Map.of() : input;
        List<String> errors = new ArrayList<>();

        if (schema.get("required") instanceof List<?> required) {
            for (Object field : required) {
                if (!values.containsKey(String.valueOf(field))) {
                    errors.add(planPath + ".input." + field + " 为必填字段");
                }
            }
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Object propertySchema = properties.get(entry.getKey());
            if (!(propertySchema instanceof Map<?, ?> rawProperty)) {
                if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
                    errors.add(planPath + ".input." + entry.getKey() + " 未在工具 "
                            + definition.getName() + " 的 input_schema 中声明");
                }
                continue;
            }
            if (isReference(entry.getValue())) {
                continue;
            }
            errors.addAll(VALIDATOR.validate(
                    stringMap(rawProperty),
                    JSON.toTree(entry.getValue()),
                    planPath + ".input." + entry.getKey()));
        }
        return errors;
    }

    private static boolean isReference(Object value) {
        return value instanceof Map<?, ?> map
                && map.size() == 1
                && map.get("$ref") instanceof String;
    }

    private static Map<String, Object> stringMap(Map<?, ?> raw) {
        Map<String, Object> mapped = new LinkedHashMap<>();
        raw.forEach((key, value) -> mapped.put(String.valueOf(key), value));
        return mapped;
    }
}
