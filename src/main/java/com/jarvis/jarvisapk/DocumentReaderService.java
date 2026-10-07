package com.jarvis.jarvisapk;

import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.pdf.OcrConfig;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DocumentReaderService {
    private final JarvisSettings settings;

    public DocumentReaderService(JarvisSettings settings) {
        this.settings = settings;
    }

    public ExtractedDocument read(Path file) throws IOException, TikaException, SAXException {
        Path path = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IOException("Select a readable file.");
        }

        long size = Files.size(path);
        if (size > settings.maxDocumentBytes()) {
            throw new IOException("File exceeds the configured limit of "
                    + settings.maxDocumentBytes() + " bytes.");
        }

        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, path.getFileName().toString());
        BodyContentHandler handler = new BodyContentHandler(settings.maxExtractedCharacters());
        ParseContext context = new ParseContext();
        if (settings.ocrEnabled()) {
            TesseractOCRConfig ocrConfig = new TesseractOCRConfig();
            ocrConfig.setLanguage(settings.ocrLanguage());
            ocrConfig.setTimeoutMillis(30_000);
            context.set(TesseractOCRConfig.class, ocrConfig);

            PDFParserConfig pdfConfig = new PDFParserConfig();
            OcrConfig pdfOcrConfig = new OcrConfig();
            pdfOcrConfig.setStrategy(OcrConfig.Strategy.AUTO);
            pdfOcrConfig.setStrategyAuto(OcrConfig.StrategyAuto.BETTER);
            pdfOcrConfig.setMaxPagesToOcr(50);
            pdfConfig.setOcr(pdfOcrConfig);
            context.set(PDFParserConfig.class, pdfConfig);
        }

        AutoDetectParser parser = new AutoDetectParser();
        try (TikaInputStream input = TikaInputStream.get(path)) {
            parser.parse(input, handler, metadata, context);
        }

        String content = handler.toString().strip();
        if (content.isBlank()) {
            String extension = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            boolean pythonSupportedFormat = extension.endsWith(".pdf")
                    || extension.matches(".*\\.(png|jpe?g|tiff?|bmp|webp)$");
            if (settings.ocrEnabled() && settings.pythonOcrFallback() && pythonSupportedFormat) {
                PythonVisionExecutor.OcrResult pythonResult = new PythonVisionExecutor(settings).extract(path);
                content = pythonResult.text();
            }
        }
        if (content.isBlank()) {
            throw new IOException("No readable text was found. For scanned PDFs and images, "
                    + "install Tesseract OCR and its language data, then try again.");
        }
        return new ExtractedDocument(
                path.getFileName().toString(),
                metadata.get(HttpHeaders.CONTENT_TYPE),
                content,
                content.length());
    }

    public record ExtractedDocument(String fileName, String contentType, String text, int characters) {
    }
}
