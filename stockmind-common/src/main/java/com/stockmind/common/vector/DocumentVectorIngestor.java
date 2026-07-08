package com.stockmind.common.vector;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class DocumentVectorIngestor {

    private final OceanBaseVectorStore vectorStore;
    private final int dimensions;
    private final int chunkSize;
    private final int overlapSize;

    public DocumentVectorIngestor(OceanBaseVectorStore vectorStore, int dimensions) {
        this(vectorStore, dimensions, 1000, 100);
    }

    public DocumentVectorIngestor(
            OceanBaseVectorStore vectorStore,
            int dimensions,
            int chunkSize,
            int overlapSize) {
        if (vectorStore == null) {
            throw new IllegalArgumentException("vectorStore 不能为空");
        }
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions 必须大于 0");
        }
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize 必须大于 0");
        }
        if (overlapSize < 0 || overlapSize >= chunkSize) {
            throw new IllegalArgumentException("overlapSize 必须满足 0 <= overlapSize < chunkSize");
        }
        this.vectorStore = vectorStore;
        this.dimensions = dimensions;
        this.chunkSize = chunkSize;
        this.overlapSize = overlapSize;
    }

    /**
     * 将 PDF 文件向量化并分块入库。
     */
    public DocumentVectorIngestResult ingestPdf(Path pdfPath, String documentId) {
        requireSuffix(pdfPath, ".pdf");
        return ingest(pdfPath, documentId, "pdf");
    }

    /**
     * 将 Word 文件向量化并分块入库（支持 doc/docx）。
     */
    public DocumentVectorIngestResult ingestWord(Path wordPath, String documentId) {
        String name = filename(wordPath).toLowerCase();
        if (!name.endsWith(".doc") && !name.endsWith(".docx")) {
            throw new IllegalArgumentException("word 文件后缀必须是 .doc 或 .docx");
        }
        return ingest(wordPath, documentId, "word");
    }

    private DocumentVectorIngestResult ingest(Path filePath, String documentId, String fileType) {
        String normalizedDocumentId = normalizeDocumentId(documentId);
        String sourceFile = filename(filePath);
        String fullText = DocumentTextExtractor.extract(filePath);
        List<String> chunks = split(fullText);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("文档内容为空，无法向量化: " + sourceFile);
        }

        vectorStore.createTableIfNotExists();
        long ts = Instant.now().toEpochMilli();
        for (int i = 0; i < chunks.size(); i++) {
            String chunkText = chunks.get(i);
            float[] vector = TextVectorBuilder.build(chunkText, dimensions);
            String rowId = normalizedDocumentId + "_chunk_" + (i + 1);
            String metadataJson = buildMetadata(fileType, normalizedDocumentId, sourceFile, i, chunks.size(), ts);
            vectorStore.upsert(rowId, chunkText, vector, metadataJson);
        }
        return new DocumentVectorIngestResult(normalizedDocumentId, sourceFile, chunks.size(), dimensions);
    }

    private List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        String normalized = text.trim();
        int step = chunkSize - overlapSize;
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(normalized.length(), start + chunkSize);
            String chunk = normalized.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            if (end >= normalized.length()) {
                break;
            }
            start += step;
        }
        return chunks;
    }

    private String buildMetadata(
            String fileType,
            String documentId,
            String sourceFile,
            int chunkIndex,
            int chunkCount,
            long ts) {
        return "{"
                + "\"file_type\":\"" + escape(fileType) + "\","
                + "\"document_id\":\"" + escape(documentId) + "\","
                + "\"source_file\":\"" + escape(sourceFile) + "\","
                + "\"chunk_index\":" + (chunkIndex + 1) + ","
                + "\"chunk_count\":" + chunkCount + ","
                + "\"ingested_at\":" + ts
                + "}";
    }

    private void requireSuffix(Path path, String suffix) {
        if (!filename(path).toLowerCase().endsWith(suffix)) {
            throw new IllegalArgumentException("文件后缀必须是 " + suffix);
        }
    }

    private String normalizeDocumentId(String documentId) {
        if (documentId == null || documentId.isBlank()) {
            return UUID.randomUUID().toString().replace("-", "");
        }
        return documentId.trim();
    }

    private String filename(Path path) {
        if (path == null || path.getFileName() == null) {
            throw new IllegalArgumentException("path 非法");
        }
        return path.getFileName().toString();
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
