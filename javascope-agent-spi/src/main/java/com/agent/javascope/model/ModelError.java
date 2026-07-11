package com.agent.javascope.model;

/** 模型调用失败的结构化信息。 */
public record ModelError(String code, String message, boolean retryable, int statusCode) {

    public ModelError {
        code = code == null ? "model_error" : code;
        message = message == null ? "" : message;
    }
}
