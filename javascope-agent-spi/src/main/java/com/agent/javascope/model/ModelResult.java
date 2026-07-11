package com.agent.javascope.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/** 模型调用的强类型结果，成功内容和失败原因互斥。 */
public sealed interface ModelResult permits ModelResult.Success, ModelResult.Failure {

    record Success(JsonNode content) implements ModelResult {
        public Success {
            Objects.requireNonNull(content, "content must not be null");
        }
    }

    record Failure(ModelError error) implements ModelResult {
        public Failure {
            Objects.requireNonNull(error, "error must not be null");
        }
    }
}
