package com.jarvis.jarvisapk;

import com.sun.net.httpserver.HttpServer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JarvisServicesTest {
    @TempDir
    Path tempDirectory;

    @Test
    void loadsSafeApiAndWorkspaceDefaultsFromYaml() throws IOException {
        JarvisSettings settings = JarvisSettings.load();

        assertEquals("http", settings.apiEndpoint().getScheme());
        assertEquals("/api/prompt", settings.apiEndpoint().getPath());
        assertTrue(settings.projectsRoot().isAbsolute());
        assertFalse(settings.apiEndpoint().toString().contains("key="));
    }

    @Test
    void extractsJavaScriptAndPythonAsReadableSource() throws Exception {
        Path source = tempDirectory.resolve("example.js");
        Files.writeString(source, "export const answer = () => 42;\n");
        Path pythonSource = tempDirectory.resolve("example.py");
        Files.writeString(pythonSource, "def answer():\n    return 42\n");

        DocumentReaderService reader = new DocumentReaderService(settings(1_000_000));
        var document = reader.read(source);
        var pythonDocument = reader.read(pythonSource);

        assertEquals("example.js", document.fileName());
        assertTrue(document.text().contains("answer"));
        assertTrue(document.contentType().contains("text"));
        assertTrue(pythonDocument.text().contains("return 42"));
    }

    @Test
    void extractsTextFromWordDocumentsAndPdfFiles() throws Exception {
        Path wordFile = tempDirectory.resolve("notes.docx");
        writeDocx(wordFile);

        Path pdfFile = tempDirectory.resolve("notes.pdf");
        try (PDDocument pdf = new PDDocument()) {
            pdf.addPage(new PDPage());
            try (PDPageContentStream content = new PDPageContentStream(pdf, pdf.getPage(0))) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText("Jarvis PDF extraction works.");
                content.endText();
            }
            pdf.save(pdfFile.toFile());
        }

        DocumentReaderService reader = new DocumentReaderService(settings(1_000_000));

        assertTrue(reader.read(wordFile).text().contains("Word extraction"));
        assertTrue(reader.read(pdfFile).text().contains("PDF extraction"));
    }

    private void writeDocx(Path file) throws IOException {
        try (ZipOutputStream docx = new ZipOutputStream(Files.newOutputStream(file))) {
            addZipEntry(docx, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                    </Types>
                    """);
            addZipEntry(docx, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                    </Relationships>
                    """);
            addZipEntry(docx, "word/document.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                      <w:body><w:p><w:r><w:t>Jarvis Word extraction works.</w:t></w:r></w:p></w:body>
                    </w:document>
                    """);
        }
    }

    private void addZipEntry(ZipOutputStream archive, String name, String content) throws IOException {
        archive.putNextEntry(new ZipEntry(name));
        archive.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        archive.closeEntry();
    }

    @Test
    void rejectsFilesAboveConfiguredSize() throws IOException {
        Path source = tempDirectory.resolve("large.txt");
        Files.writeString(source, "This is too large.");

        IOException exception = assertThrows(IOException.class,
                () -> new DocumentReaderService(settings(2)).read(source));

        assertTrue(exception.getMessage().contains("configured limit"));
    }

    @Test
    void workspaceCatalogCountsCodeAndSkipsBuildArtifacts() throws IOException {
        Path project = Files.createDirectories(tempDirectory.resolve("sample-project"));
        Files.createDirectories(project.resolve("target"));
        Files.writeString(project.resolve("App.java"), "class App {}");
        Files.writeString(project.resolve("tool.py"), "print('ok')");
        Files.writeString(project.resolve("target/Generated.java"), "class Generated {}");

        var summaries = new WorkspaceProjectCatalog(tempDirectory).scan();

        assertEquals(1, summaries.size());
        assertEquals(2, summaries.getFirst().sourceFiles());
        assertEquals(1, summaries.getFirst().languages().get("Java"));
        assertEquals(1, summaries.getFirst().languages().get("Python"));
    }

    @Test
    void workspaceCatalogExcludesTheCurrentProjectFromItsSiblingInventory() throws IOException {
        Path current = Files.createDirectories(tempDirectory.resolve("jarvisapk"));
        Files.writeString(current.resolve("Current.java"), "class Current {}");
        Path sibling = Files.createDirectories(tempDirectory.resolve("sibling"));
        Files.writeString(sibling.resolve("Sibling.py"), "pass");

        var summaries = new WorkspaceProjectCatalog(tempDirectory, current).scan();

        assertEquals(1, summaries.size());
        assertEquals("sibling", summaries.getFirst().name());
    }

    @Test
    void apiClientUsesConfiguredPromptContractAndReadsOutputField() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/prompt", exchange -> {
            try (exchange) {
                String request = new String(exchange.getRequestBody().readAllBytes());
                assertTrue(request.contains("\"prompt\":\"hello\""));
                assertTrue(request.contains("\"model\":\"test-model\""));
                byte[] response = "{\"output\":\"Hello from Jarvis\"}".getBytes();
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        try {
            JarvisSettings settings = new JarvisSettings(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/prompt"),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/health"),
                    JarvisSettings.ApiProtocol.CLAW, "test-model", null, Duration.ofSeconds(5),
                    tempDirectory, tempDirectory, 1_000_000, 10_000, false, "eng");

            String answer = new ApiClient(settings)
                    .sendQuestionAsync("hello")
                    .get(6, TimeUnit.SECONDS);

            assertEquals("Hello from Jarvis", answer);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void apiClientSupportsOpenAiCompatibleChatCompletions() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = server("/v1/chat/completions",
                "{\"choices\":[{\"message\":{\"content\":\"OpenAI answer\"}}]}",
                authorization, requestBody);
        try {
            JarvisSettings settings = settingsFor(
                    server, JarvisSettings.ApiProtocol.OPENAI_COMPATIBLE, "test-model", "test-token");

            String answer = new ApiClient(settings)
                    .sendQuestionAsync("hello")
                    .get(6, TimeUnit.SECONDS);

            assertEquals("OpenAI answer", answer);
            assertEquals("Bearer test-token", authorization.get());
            assertTrue(requestBody.get().contains("\"messages\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void apiClientSupportsAnthropicMessagesResponse() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = server("/v1/messages",
                "{\"content\":[{\"type\":\"text\",\"text\":\"Anthropic answer\"}]}",
                authorization, requestBody);
        try {
            JarvisSettings settings = settingsFor(
                    server, JarvisSettings.ApiProtocol.ANTHROPIC, "claude-test", "test-token");

            String answer = new ApiClient(settings)
                    .sendQuestionAsync("hello")
                    .get(6, TimeUnit.SECONDS);

            assertEquals("Anthropic answer", answer);
            assertEquals("test-token", authorization.get());
            assertTrue(requestBody.get().contains("\"max_tokens\""));
        } finally {
            server.stop(0);
        }
    }

    private HttpServer server(
            String path,
            String responseText,
            AtomicReference<String> authorization,
            AtomicReference<String> requestBody) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            try (exchange) {
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                if (authorization.get() == null) {
                    authorization.set(exchange.getRequestHeaders().getFirst("x-api-key"));
                }
                requestBody.set(new String(exchange.getRequestBody().readAllBytes()));
                byte[] response = responseText.getBytes();
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        return server;
    }

    private JarvisSettings settingsFor(
            HttpServer server,
            JarvisSettings.ApiProtocol protocol,
            String model,
            String apiKey) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        String path = protocol == JarvisSettings.ApiProtocol.ANTHROPIC
                ? "/v1/messages"
                : "/v1/chat/completions";
        return new JarvisSettings(
                URI.create(baseUrl + path), URI.create(baseUrl + "/health"),
                protocol, model, apiKey, Duration.ofSeconds(5),
                tempDirectory, tempDirectory, 1_000_000, 10_000, false, "eng");
    }

    private JarvisSettings settings(long maxBytes) {
        return new JarvisSettings(
                URI.create("http://127.0.0.1:5000/api/prompt"),
                URI.create("http://127.0.0.1:5000/api/status"),
                JarvisSettings.ApiProtocol.CLAW, "local-gemini", null, Duration.ofSeconds(10),
                tempDirectory, tempDirectory, maxBytes, 20_000, false, "eng");
    }
}
