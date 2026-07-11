package com.agent.javascope.model;

/** 将可恢复的模型失败显式传递给运行时，而不是伪装成空 JSON。 */
public class ModelCallException extends RuntimeException {

    private final ModelError error;

    public ModelCallException(ModelError error) {
        super(error == null ? "model call failed" : error.code() + ": " + error.message());
        this.error = error;
    }

    public ModelError getError() {
        return error;
    }
}
