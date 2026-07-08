package com.stockmind.common.vector;

public record DocumentVectorIngestResult(
        String documentId,
        String sourceFile,
        int chunkCount,
        int dimensions) {}
