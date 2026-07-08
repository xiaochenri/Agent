package com.stockmind.common.vector;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;

public final class DocumentTextExtractor {

    private DocumentTextExtractor() {}

    public static String extract(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("path 不能为空");
        }
        String filename = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase();
        try {
            if (filename.endsWith(".pdf")) {
                return extractPdf(path);
            }
            if (filename.endsWith(".docx")) {
                return extractDocx(path);
            }
            if (filename.endsWith(".doc")) {
                return extractDoc(path);
            }
            throw new IllegalArgumentException("暂不支持的文件类型: " + filename);
        } catch (IOException e) {
            throw new IllegalStateException("读取文档失败: " + path, e);
        }
    }

    private static String extractPdf(Path path) throws IOException {
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return normalize(stripper.getText(document));
        }
    }

    private static String extractDocx(Path path) throws IOException {
        try (InputStream inputStream = Files.newInputStream(path);
                XWPFDocument document = new XWPFDocument(inputStream);
                XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return normalize(extractor.getText());
        }
    }

    private static String extractDoc(Path path) throws IOException {
        try (InputStream inputStream = Files.newInputStream(path);
                HWPFDocument document = new HWPFDocument(inputStream);
                WordExtractor extractor = new WordExtractor(document)) {
            return normalize(extractor.getText());
        }
    }

    private static String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.replace("\r\n", "\n").replace('\r', '\n').trim();
    }
}
