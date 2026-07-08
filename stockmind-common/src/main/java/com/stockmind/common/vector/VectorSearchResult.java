package com.stockmind.common.vector;

public record VectorSearchResult(
        String id,
        String content,
        String metadataJson,
        double distance,
        double score) {}
