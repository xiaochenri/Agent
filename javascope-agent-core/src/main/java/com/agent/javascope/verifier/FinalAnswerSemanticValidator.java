package com.agent.javascope.verifier;

import com.agent.javascope.entity.execution.AgentExecutionLogEntry;
import java.util.List;
import java.util.Map;

/**
 * Adds deterministic domain checks before the model-based independent verifier runs.
 *
 * <p>Implementations should only reject claims that can be proven invalid from structured tool
 * output. Writing preferences and investigation-path preferences do not belong here.</p>
 */
public interface FinalAnswerSemanticValidator {

    /** Returns user-correctable blocking issues; an empty list means the domain checks passed. */
    default List<String> validate(
            String userInput,
            List<AgentExecutionLogEntry> executionLog,
            Map<String, Object> finalAnswer) {
        return List.of();
    }
}
