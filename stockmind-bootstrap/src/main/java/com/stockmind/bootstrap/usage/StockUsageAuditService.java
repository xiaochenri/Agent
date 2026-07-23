package com.stockmind.bootstrap.usage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 股票业务的上线使用数据记录。此类只消费 Agent 返回的兼容视图，并将其转换为脱敏摘要；
 * 不依赖、不修改 Agent 框架的 Trace 或 ExecutionLogStore。
 */
@Service
public class StockUsageAuditService {

    private static final int TASK_IDLE_MINUTES = 30;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final String userHashSecret;
    private final String promptVersion;

    public StockUsageAuditService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            @Value("${stockmind.usage-log.user-hash-secret:stockmind-development-only}") String userHashSecret,
            @Value("${stockmind.usage-log.prompt-version:stock-v1}") String promptVersion) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.userHashSecret = userHashSecret;
        this.promptVersion = promptVersion;
    }

    @PostConstruct
    void initialize() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS stock_task_attempt (
                    id VARCHAR(64) PRIMARY KEY,
                    conversation_id VARCHAR(64) NOT NULL,
                    user_subject_hash CHAR(64) NOT NULL,
                    business_code VARCHAR(32) NOT NULL,
                    goal_type VARCHAR(32) NOT NULL,
                    outcome_status VARCHAR(24) NOT NULL DEFAULT 'unknown',
                    outcome_source VARCHAR(24) NOT NULL DEFAULT 'none',
                    outcome_recorded_at DATETIME(3) NULL,
                    started_at DATETIME(3) NOT NULL,
                    last_active_at DATETIME(3) NOT NULL,
                    KEY idx_stock_task_user_active (user_subject_hash, last_active_at),
                    KEY idx_stock_task_conversation_active (conversation_id, last_active_at),
                    KEY idx_stock_task_outcome (outcome_status, started_at)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS stock_execution_audit (
                    execution_id VARCHAR(64) PRIMARY KEY,
                    task_attempt_id VARCHAR(64) NOT NULL,
                    conversation_id VARCHAR(64) NOT NULL,
                    user_subject_hash CHAR(64) NOT NULL,
                    request_kind VARCHAR(16) NOT NULL,
                    execution_mode VARCHAR(16) NOT NULL,
                    model_provider VARCHAR(64) NOT NULL,
                    model_name VARCHAR(64) NOT NULL,
                    prompt_version VARCHAR(64) NOT NULL,
                    final_status VARCHAR(24) NOT NULL,
                    failure_code VARCHAR(64) NULL,
                    started_at DATETIME(3) NOT NULL,
                    finished_at DATETIME(3) NOT NULL,
                    total_latency_ms BIGINT NOT NULL,
                    round_count INT NOT NULL,
                    tool_call_count INT NOT NULL,
                    tool_failure_count INT NOT NULL,
                    answer_evidence_level VARCHAR(16) NOT NULL,
                    KEY idx_stock_execution_status (final_status, started_at),
                    KEY idx_stock_execution_task (task_attempt_id, started_at),
                    KEY idx_stock_execution_version (model_name, prompt_version, started_at)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS stock_execution_log (
                    execution_id VARCHAR(64) NOT NULL,
                    sequence_no BIGINT NOT NULL,
                    occurred_at DATETIME(3) NOT NULL,
                    event_type VARCHAR(48) NOT NULL,
                    status VARCHAR(16) NULL,
                    step_index INT NULL,
                    round_index INT NULL,
                    tool_name VARCHAR(96) NULL,
                    error_category VARCHAR(48) NULL,
                    error_code VARCHAR(64) NULL,
                    latency_ms BIGINT NULL,
                    retry_count INT NOT NULL DEFAULT 0,
                    source_name VARCHAR(64) NULL,
                    summary_json LONGTEXT NOT NULL,
                    PRIMARY KEY (execution_id, sequence_no),
                    KEY idx_stock_execution_log_tool (tool_name, occurred_at),
                    KEY idx_stock_execution_log_error (error_code, occurred_at)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS stock_execution_feedback (
                    id VARCHAR(64) PRIMARY KEY,
                    execution_id VARCHAR(64) NOT NULL,
                    task_attempt_id VARCHAR(64) NOT NULL,
                    feedback_type VARCHAR(32) NOT NULL,
                    feedback_value VARCHAR(32) NOT NULL,
                    created_at DATETIME(3) NOT NULL,
                    updated_at DATETIME(3) NOT NULL,
                    UNIQUE KEY uk_stock_feedback_type (execution_id, feedback_type),
                    KEY idx_stock_feedback_task (task_attempt_id, created_at)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS stock_behavior_event (
                    id VARCHAR(64) PRIMARY KEY,
                    occurred_at DATETIME(3) NOT NULL,
                    user_subject_hash CHAR(64) NOT NULL,
                    task_attempt_id VARCHAR(64) NULL,
                    execution_id VARCHAR(64) NULL,
                    event_name VARCHAR(48) NOT NULL,
                    properties_json LONGTEXT NOT NULL,
                    KEY idx_stock_behavior_event_name (event_name, occurred_at),
                    KEY idx_stock_behavior_event_task (task_attempt_id, occurred_at)
                )
                """);
    }

    /** Records a completed stock Agent execution using only safe fields from its returned runtime view. */
    @Transactional
    public void recordExecution(
            String userId,
            String conversationId,
            String executionId,
            Map<String, Object> payload,
            long startedNanos) {
        if (executionId == null || executionId.isBlank()) return;
        String userHash = subjectHash(userId);
        LocalDateTime now = LocalDateTime.now();
        String taskAttemptId = findOrCreateTaskAttempt(conversationId, userHash, now);
        List<Map<?, ?>> logs = executionLogs(payload);
        int toolCalls = (int) logs.stream().filter(this::isToolLog).count();
        int toolFailures = (int) logs.stream().filter(this::isFailedToolLog).count();
        String responseStatus = string(payload == null ? null : payload.get("status"));
        String finalStatus = "failed".equals(responseStatus) || "blocked".equals(responseStatus)
                ? responseStatus : toolFailures == 0 ? "completed" : "partial";
        String failureCode = logs.stream().map(this::errorCode).filter(value -> !value.isBlank()).findFirst().orElse(null);
        String requestKind = valueOrDefault(string(payload == null ? null : payload.get("route")), "unknown");
        String executionMode = "unknown";
        String modelProvider = stringAt(payload, "metadata", "provider", "unknown");
        String modelName = stringAt(payload, "metadata", "model", "unknown");
        int roundCount = (int) logs.stream().map(log -> numberFromStep(string(log.get("step")))).filter(value -> value > 0).distinct().count();
        String evidenceLevel = toolCalls == 0 ? "not_applicable" : toolFailures == 0 ? "sufficient" : "partial";
        long latencyMs = Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000L);

        jdbcTemplate.update("""
                INSERT IGNORE INTO stock_execution_audit(
                    execution_id, task_attempt_id, conversation_id, user_subject_hash, request_kind, execution_mode,
                    model_provider, model_name, prompt_version, final_status, failure_code, started_at, finished_at,
                    total_latency_ms, round_count, tool_call_count, tool_failure_count, answer_evidence_level)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, executionId, taskAttemptId, conversationId, userHash, requestKind, executionMode,
                modelProvider, modelName, promptVersion, finalStatus, failureCode, now, now, latencyMs, roundCount, toolCalls, toolFailures,
                evidenceLevel);

        long sequence = 0;
        sequence = writeLifecycleLog(executionId, ++sequence, now, "REQUEST_STARTED",
                Map.of("request_kind", requestKind));
        sequence = writeLifecycleLog(executionId, ++sequence, now, "ROUTE_DECIDED",
                Map.of("request_kind", requestKind, "execution_mode", executionMode));
        int planStepCount = planStepCount(payload);
        if (planStepCount > 0) {
            sequence = writeLifecycleLog(executionId, ++sequence, now, "PLAN_CREATED",
                    Map.of("plan_step_count", planStepCount));
        }
        for (Map<?, ?> log : logs) {
            sequence++;
            writeLog(executionId, sequence, now, log);
        }
        writeLifecycleLog(executionId, ++sequence, now, "EXECUTION_FINISHED",
                Map.of("final_status", finalStatus, "tool_call_count", toolCalls,
                        "tool_failure_count", toolFailures, "answer_evidence_level", evidenceLevel));
        jdbcTemplate.update("UPDATE stock_task_attempt SET last_active_at = ? WHERE id = ?", now, taskAttemptId);
        recordBehaviorInternal(UUID.randomUUID().toString(), userHash, taskAttemptId, executionId,
                "answer_rendered", Map.of("final_status", finalStatus), now);
    }

    @Transactional
    public void submitFeedback(String userId, String executionId, String type, String value) {
        if (!isFeedback(type, value)) throw new IllegalArgumentException("invalid feedback");
        String userHash = subjectHash(userId);
        List<Map<String, Object>> executions = jdbcTemplate.queryForList("""
                SELECT task_attempt_id FROM stock_execution_audit
                WHERE execution_id = ? AND user_subject_hash = ?
                """, executionId, userHash);
        if (executions.isEmpty()) throw new IllegalArgumentException("execution not found");
        String taskAttemptId = String.valueOf(executions.get(0).get("task_attempt_id"));
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO stock_execution_feedback(id, execution_id, task_attempt_id, feedback_type, feedback_value, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE feedback_value = VALUES(feedback_value), updated_at = VALUES(updated_at)
                """, UUID.randomUUID().toString(), executionId, taskAttemptId, type, value, now, now);
        if ("goal_achievement".equals(type)) {
            jdbcTemplate.update("""
                    UPDATE stock_task_attempt
                    SET outcome_status = ?, outcome_source = 'explicit', outcome_recorded_at = ?, last_active_at = ?
                    WHERE id = ?
                    """, value, now, now, taskAttemptId);
        }
        recordBehaviorInternal(UUID.randomUUID().toString(), userHash, taskAttemptId, executionId,
                "feedback_submitted", Map.of("feedback_type", type, "feedback_value", value), now);
    }

    @Transactional
    public void recordBehavior(String userId, String executionId, String eventName) {
        if (!List.of("evidence_viewed", "answer_copied", "answer_saved", "follow_up_submitted", "conversation_deleted").contains(eventName)) {
            throw new IllegalArgumentException("invalid behavior event");
        }
        String userHash = subjectHash(userId);
        List<Map<String, Object>> executions = jdbcTemplate.queryForList("""
                SELECT task_attempt_id FROM stock_execution_audit WHERE execution_id = ? AND user_subject_hash = ?
                """, executionId, userHash);
        if (executions.isEmpty()) throw new IllegalArgumentException("execution not found");
        recordBehaviorInternal(UUID.randomUUID().toString(), userHash,
                String.valueOf(executions.get(0).get("task_attempt_id")), executionId, eventName, Map.of(), LocalDateTime.now());
    }

    private String findOrCreateTaskAttempt(String conversationId, String userHash, LocalDateTime now) {
        List<Map<String, Object>> active = jdbcTemplate.queryForList("""
                SELECT id FROM stock_task_attempt
                WHERE conversation_id = ? AND user_subject_hash = ? AND last_active_at >= ?
                ORDER BY last_active_at DESC LIMIT 1
                """, conversationId, userHash, now.minusMinutes(TASK_IDLE_MINUTES));
        if (!active.isEmpty()) return String.valueOf(active.get(0).get("id"));
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO stock_task_attempt(id, conversation_id, user_subject_hash, business_code, goal_type, started_at, last_active_at)
                VALUES (?, ?, ?, 'stock', 'unknown', ?, ?)
                """, id, conversationId, userHash, now, now);
        return id;
    }

    private void writeLog(String executionId, long sequence, LocalDateTime now, Map<?, ?> log) {
        String step = string(log.get("step"));
        String toolName = string(log.get("tool_name"));
        Map<?, ?> output = map(log.get("output"));
        String status = string(output.get("status"));
        String errorCode = errorCode(log);
        String source = string(output.get("source"));
        if (source.isBlank()) source = string(map(output.get("metadata")).get("source"));
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("step", step);
        summary.put("confidence", log.get("confidence"));
        addIfPresent(summary, "result_quality", output.get("data_quality"));
        addIfPresent(summary, "retryable", output.get("retryable"));
        jdbcTemplate.update("""
                INSERT IGNORE INTO stock_execution_log(
                    execution_id, sequence_no, occurred_at, event_type, status, step_index, round_index, tool_name,
                    error_category, error_code, latency_ms, retry_count, source_name, summary_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 0, ?, ?)
                """, executionId, sequence, now, isToolLog(log) ? "TOOL_COMPLETED" : "FRAMEWORK_STEP",
                nullable(status), null, numberFromStep(step), nullable(toolName),
                errorCode.isBlank() ? null : "business", nullable(errorCode), nullable(source), toJson(summary));
    }

    private long writeLifecycleLog(String executionId, long sequence, LocalDateTime now, String eventType,
            Map<String, Object> summary) {
        jdbcTemplate.update("""
                INSERT IGNORE INTO stock_execution_log(
                    execution_id, sequence_no, occurred_at, event_type, status, step_index, round_index, tool_name,
                    error_category, error_code, latency_ms, retry_count, source_name, summary_json)
                VALUES (?, ?, ?, ?, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 0, NULL, ?)
                """, executionId, sequence, now, eventType, toJson(summary));
        return sequence;
    }

    private void recordBehaviorInternal(String id, String userHash, String taskAttemptId, String executionId,
            String eventName, Map<String, Object> properties, LocalDateTime now) {
        jdbcTemplate.update("""
                INSERT IGNORE INTO stock_behavior_event(
                    id, occurred_at, user_subject_hash, task_attempt_id, execution_id, event_name, properties_json)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, now, userHash, taskAttemptId, executionId, eventName, toJson(properties));
    }

    private List<Map<?, ?>> executionLogs(Map<String, Object> payload) {
        Object runtime = payload == null ? null : payload.get("runtime");
        if (!(runtime instanceof Map<?, ?> runtimeMap) || !(runtimeMap.get("execution_log") instanceof List<?> values)) return List.of();
        List<Map<?, ?>> result = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof Map<?, ?> map) result.add(map);
        }
        return List.copyOf(result);
    }

    private int planStepCount(Map<String, Object> payload) {
        Object runtime = payload == null ? null : payload.get("runtime");
        if (!(runtime instanceof Map<?, ?> runtimeMap) || !(runtimeMap.get("plan") instanceof List<?> plan)) return 0;
        return plan.size();
    }

    private boolean isToolLog(Map<?, ?> log) {
        String tool = string(log.get("tool_name"));
        return !tool.isBlank() && !"reasoning".equals(tool);
    }

    private boolean isFailedToolLog(Map<?, ?> log) {
        return isToolLog(log) && "failed".equals(string(map(log.get("output")).get("status")));
    }

    private String errorCode(Map<?, ?> log) { return string(map(log.get("output")).get("error_code")); }

    private String stringAt(Map<String, Object> payload, String parent, String key, String fallback) {
        Object value = map(payload == null ? null : payload.get(parent)).get(key);
        String result = string(value);
        return result.isBlank() ? fallback : result;
    }

    private String valueOrDefault(String value, String fallback) { return value.isBlank() ? fallback : value; }

    private int numberFromStep(String value) {
        int lastUnderscore = value.lastIndexOf('_');
        if (lastUnderscore < 0) return 0;
        try { return Integer.parseInt(value.substring(lastUnderscore + 1)); } catch (NumberFormatException ignored) { return 0; }
    }

    private boolean isFeedback(String type, String value) {
        return ("answer_helpfulness".equals(type) && List.of("helpful", "not_helpful").contains(value))
                || ("goal_achievement".equals(type) && List.of("achieved", "partially_achieved", "not_achieved").contains(value))
                || ("failure_reason".equals(type) && List.of("misunderstood_request", "insufficient_conclusion",
                        "insufficient_analysis", "insufficient_personalization", "data_incomplete", "data_quality_issue",
                        "insufficient_evidence", "missing_next_step", "missing_capability", "slow_or_failed",
                        "different_goal").contains(value));
    }

    private String subjectHash(String userId) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest((userHashSecret + "\\n" + userId).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (Exception error) {
            throw new IllegalStateException("cannot hash usage-log user subject", error);
        }
    }

    private Map<?, ?> map(Object value) { return value instanceof Map<?, ?> map ? map : Map.of(); }
    private String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private String nullable(String value) { return value == null || value.isBlank() ? null : value; }
    private void addIfPresent(Map<String, Object> target, String key, Object value) { if (value != null) target.put(key, value); }

    private String toJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("usage-log summary serialization failed", error); }
    }
}
