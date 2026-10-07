package com.jarvis.jarvisapk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public final class PythonVisionExecutor {
    private static final Logger LOGGER = Logger.getLogger(PythonVisionExecutor.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration PROCESS_TIMEOUT = Duration.ofMinutes(3);
    private static final int MAX_DIAGNOSTIC_CHARS = 4000;
    private static final String SCRIPT_RESOURCE = "/python/vision_ocr.py";

    private final JarvisSettings settings;

    public PythonVisionExecutor(JarvisSettings settings) {
        this.settings = settings;
    }

    public OcrResult extract(Path input) throws IOException {
        Path source = input.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new IOException("Select a readable PDF or image file for Python OCR.");
        }
        if (Files.size(source) > settings.maxDocumentBytes()) {
            throw new IOException("File exceeds the configured limit of "
                    + settings.maxDocumentBytes() + " bytes.");
        }

        Path script = Files.createTempFile("jarvis-vision-", ".py");
        Path output = Files.createTempFile("jarvis-vision-", ".json");
        Path diagnostics = Files.createTempFile("jarvis-vision-", ".log");
        try {
            try (InputStream resource = PythonVisionExecutor.class.getResourceAsStream(SCRIPT_RESOURCE)) {
                if (resource == null) {
                    throw new IOException("Python OCR bridge resource is missing: " + SCRIPT_RESOURCE);
                }
                Files.copy(resource, script, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            List<String> command = List.of(
                    settings.pythonExecutable(),
                    script.toString(),
                    source.toString(),
                    "--language", settings.ocrLanguage(),
                    "--max-bytes", Long.toString(settings.maxDocumentBytes()),
                    "--max-characters", Integer.toString(settings.maxExtractedCharacters()),
                    "--max-pages", "50");
            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectOutput(output.toFile())
                    .redirectError(diagnostics.toFile());
            removeCredentialEnvironment(builder.environment());

            Process process;
            try {
                process = builder.start();
            } catch (IOException exception) {
                throw new IOException("Could not launch the configured Python OCR runtime '"
                        + settings.pythonExecutable() + "'. Install Python and tools/python/requirements.txt.", exception);
            }
            boolean completed;
            try {
                completed = process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("Python OCR was interrupted.", exception);
            }
            if (!completed) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw new IOException("Python OCR exceeded its " + PROCESS_TIMEOUT.toSeconds() + "-second timeout.");
            }
            if (process.exitValue() != 0) {
                throw new IOException("Python OCR failed (exit " + process.exitValue() + "): "
                        + readBounded(diagnostics, MAX_DIAGNOSTIC_CHARS));
            }
            if (Files.size(output) > maxOutputBytes()) {
                throw new IOException("Python OCR output exceeded the configured extraction limit.");
            }

            JsonNode result;
            try (InputStream response = Files.newInputStream(output)) {
                result = JSON.readTree(response);
            }
            String text = result.path("text").asText().strip();
            if (text.isBlank() || text.length() > settings.maxExtractedCharacters()) {
                throw new IOException("Python OCR returned empty text or exceeded the configured character limit.");
            }
            int pages = result.path("pagesProcessed").asInt(0);
            if (pages < 1) {
                throw new IOException("Python OCR returned an invalid pagesProcessed value.");
            }
            LOGGER.info(() -> "python_ocr_success file=" + source.getFileName()
                    + " pages=" + pages + " characters=" + text.length());
            return new OcrResult(text, pages, text.length());
        } finally {
            Files.deleteIfExists(script);
            Files.deleteIfExists(output);
            Files.deleteIfExists(diagnostics);
        }
    }

    private long maxOutputBytes() {
        return Math.min(Integer.MAX_VALUE, (long) settings.maxExtractedCharacters() * 6 + 65_536);
    }

    private String readBounded(Path file, int maximumCharacters) throws IOException {
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(maximumCharacters);
            return new String(bytes, StandardCharsets.UTF_8).strip();
        }
    }

    private void removeCredentialEnvironment(Map<String, String> environment) {
        environment.keySet().removeIf(key ->
                key.matches("(?i).*(API_KEY|TOKEN|PASSWORD|SECRET|CREDENTIAL).*"));
    }

    public record OcrResult(String text, int pagesProcessed, int characters) {
    }
}
