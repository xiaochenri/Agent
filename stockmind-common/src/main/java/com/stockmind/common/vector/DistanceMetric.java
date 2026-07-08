package com.stockmind.common.vector;

public enum DistanceMetric {
    COSINE("cosine_distance"),
    L2("l2_distance"),
    INNER_PRODUCT("inner_product");

    private final String functionName;

    DistanceMetric(String functionName) {
        this.functionName = functionName;
    }

    public String functionName() {
        return functionName;
    }
}
