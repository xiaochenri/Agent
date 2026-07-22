package com.stockmind.bootstrap.business.tool;

import com.agent.javascope.tool.error.DefaultToolErrorClassifier;
import com.agent.javascope.tool.middleware.ToolResultFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

abstract class StockToolSupport {
    protected static final java.time.ZoneId CHINA_ZONE = java.time.ZoneId.of("Asia/Shanghai");
    /** Frequently used company names accepted by the conversational entry point. */
    private static final Map<String, String> COMPANY_ALIASES = Map.of();
    protected String firstNonBlank(String primary, String fallback) {
        return primary != null && !primary.isBlank() ? primary.trim() : fallback == null ? "" : fallback.trim();
    }

    protected String asString(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    protected String symbol(Map<String, Object> input, String rawInput) {
        String candidate = firstNonBlank(asString(input.get("symbol")), extractSymbol(rawInput));
        if (!candidate.isBlank()) return COMPANY_ALIASES.getOrDefault(candidate, candidate);
        String raw = rawInput == null ? "" : rawInput;
        return COMPANY_ALIASES.entrySet().stream()
                .filter(entry -> raw.contains(entry.getKey())).map(Map.Entry::getValue).findFirst().orElse("");
    }

    protected int positiveInt(Object value, int fallback) {
        if (value == null || asString(value).isBlank()) return fallback;
        try {
            return Integer.parseInt(asString(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("指标周期必须是整数");
        }
    }

    protected double positiveDouble(Object value, double fallback) {
        if (value == null || asString(value).isBlank()) return fallback;
        try {
            return Double.parseDouble(asString(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("指标参数必须是数值");
        }
    }

    protected int topK(Object value, int fallback) {
        try {
            return value == null ? fallback : Math.max(1, Integer.parseInt(asString(value)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    protected Double number(Object value) {
        try {
            return value instanceof Number n ? n.doubleValue() : asString(value).isBlank() ? null : Double.parseDouble(asString(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    protected Long wholeNumber(Object value) {
        try {
            return value instanceof Number n ? n.longValue() : asString(value).isBlank() ? null : Long.parseLong(asString(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
    }

    protected String json(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{}";
        }
    }

    protected String success(String tool, Object data, String rules) {
        return "{\"tool\":\"" + escape(tool) + "\",\"status\":\"success\",\"validation_passed\":true,\"validation_rules\":" + rules + ",\"validation_errors\":[],\"retryable\":false,\"error_code\":\"\",\"data\":" + json(data) + ",\"metadata\":{}}";
    }

    protected String fail(String tool, StockToolError error) {
        var toolError = DefaultToolErrorClassifier.INSTANCE.classify(
                error.code(), error.publicMessage(), error.retryable());
        return "{\"tool\":\"" + escape(tool) + "\",\"status\":\"failed\",\"validation_passed\":false,\"validation_rules\":[],\"validation_errors\":[\""
                + escape(error.publicMessage()) + "\"],\"retryable\":" + error.retryable()
                + ",\"error_code\":\"" + escape(error.code()) + "\",\"error\":"
                + json(ToolResultFactory.publicError(toolError)) + ",\"data\":null,\"metadata\":{}}";
    }

    protected String extractSymbol(String text) {
        if (text == null) return "";
        Matcher cn = Pattern.compile("\\b\\d{6}\\b").matcher(text);
        if (cn.find()) return cn.group();
        Matcher us = Pattern.compile("\\$?[A-Z]{1,5}\\b").matcher(text.toUpperCase());
        return us.find() ? us.group().replace("$", "") : "";
    }

    protected String truncate(String text, int length) {
        return text == null ? "" : text.length() <= length ? text : text.substring(0, length);
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
