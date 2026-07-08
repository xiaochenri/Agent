package com.stockmind.common.vector;

import java.util.ArrayList;
import java.util.List;

public final class TextVectorBuilder {

    private TextVectorBuilder() {}

    /**
     * 将文本构造成固定维度向量（基础实现：分词哈希 + L2 归一化）。
     */
    public static float[] build(String text, int dimensions) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions 必须大于 0");
        }
        String normalized = text == null ? "" : text.trim();
        float[] vector = new float[dimensions];
        if (normalized.isEmpty()) {
            return vector;
        }
        String[] tokens = normalized.split("\\s+");
        for (String token : tokens) {
            int index = Math.floorMod(fnv1a32(token), dimensions);
            vector[index] += 1.0f;
        }
        normalizeL2(vector);
        return vector;
    }

    /**
     * 向量转为 OceanBase 可识别的文本字面量，例如 [0.1,0.2,0.3]。
     */
    public static String toLiteral(float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("vector 不能为空");
        }
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(vector[i]);
        }
        builder.append(']');
        return builder.toString();
    }

    /**
     * 从向量字面量反解析为 float[]。
     */
    public static float[] fromLiteral(String literal) {
        if (literal == null || literal.isBlank()) {
            return new float[0];
        }
        String trimmed = literal.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            throw new IllegalArgumentException("非法向量字面量: " + literal);
        }
        String body = trimmed.substring(1, trimmed.length() - 1).trim();
        if (body.isEmpty()) {
            return new float[0];
        }
        String[] parts = body.split(",");
        List<Float> values = new ArrayList<>(parts.length);
        for (String part : parts) {
            values.add(Float.parseFloat(part.trim()));
        }
        float[] out = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    private static int fnv1a32(String input) {
        int hash = 0x811C9DC5;
        for (int i = 0; i < input.length(); i++) {
            hash ^= input.charAt(i);
            hash *= 0x01000193;
        }
        return hash;
    }

    private static void normalizeL2(float[] vector) {
        double sum = 0.0;
        for (float value : vector) {
            sum += value * value;
        }
        if (sum <= 0.0) {
            return;
        }
        double norm = Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / norm);
        }
    }
}
