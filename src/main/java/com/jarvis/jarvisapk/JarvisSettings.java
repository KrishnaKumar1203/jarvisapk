package com.jarvis.jarvisapk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public record JarvisSettings(
        URI apiEndpoint,
        URI healthEndpoint,
        ApiProtocol apiProtocol,
        String model,
        String apiKey,
        Duration apiTimeout,
        Path projectsRoot,
        Path dataDirectory,
        long maxDocumentBytes,
        int maxExtractedCharacters,
        boolean ocrEnabled,
        String ocrLanguage,
        boolean pythonOcrFallback,
        String pythonExecutable) {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final String CONFIG_RESOURCE = "/application.yaml";

    public static JarvisSettings load() throws IOException {
        JsonNode config;
        try (InputStream input = JarvisSettings.class.getResourceAsStream(CONFIG_RESOURCE)) {
            if (input == null) {
                throw new IOException("Missing application configuration: " + CONFIG_RESOURCE);
            }
            config = YAML_MAPPER.readTree(input);
        }

        JsonNode api = config.path("api");
        JsonNode workspace = config.path("workspace");
        JsonNode documents = config.path("documents");
        JsonNode ocr = documents.path("ocr");
        JsonNode app = config.path("app");

        String endpoint = environmentOrDefault(
                "JARVIS_API_ENDPOINT",
                api.path("endpoint").asText("http://127.0.0.1:5000/api/prompt"));
        String healthEndpoint = environmentOrDefault(
                "JARVIS_API_HEALTH_ENDPOINT",
                api.path("health-endpoint").asText("http://127.0.0.1:5000/api/status"));
        String model = environmentOrDefault(
                "JARVIS_API_MODEL",
                api.path("model").asText("local-gemini"));
        String configuredProtocol = environmentOrDefault(
                "JARVIS_API_PROTOCOL",
                api.path("protocol").asText("claw"));
        String configuredRoot = environmentOrDefault(
                "JARVIS_PROJECTS_ROOT",
                workspace.path("projects-root").asText("${user.home}/IdeaProjects"));
        String root = configuredRoot.replace("${user.home}", System.getProperty("user.home"));
        String configuredDataDirectory = app.path("data-directory")
                .asText("${user.home}/.jarvis")
                .replace("${user.home}", System.getProperty("user.home"));
        String pythonExecutable = environmentOrDefault(
                "JARVIS_PYTHON_EXECUTABLE",
                ocr.path("python-executable").asText("python"));

        try {
            URI apiUri = requireHttpUri(endpoint, "api.endpoint");
            URI healthUri = requireHttpUri(healthEndpoint, "api.health-endpoint");
            ApiProtocol protocol = ApiProtocol.parse(configuredProtocol);
            Duration timeout = Duration.ofSeconds(api.path("timeout-seconds").asLong(180));
            long maxBytes = documents.path("max-bytes").asLong(33_554_432);
            int maxCharacters = documents.path("max-extracted-characters").asInt(200_000);
            if (timeout.isZero() || timeout.isNegative() || maxBytes <= 0 || maxCharacters <= 0) {
                throw new IllegalArgumentException("Timeout and document limits must be positive.");
            }
            return new JarvisSettings(
                    apiUri,
                    healthUri,
                    protocol,
                    model,
                    configuredApiKey(protocol),
                    timeout,
                    Path.of(root).toAbsolutePath().normalize(),
                    Path.of(configuredDataDirectory).toAbsolutePath().normalize(),
                    maxBytes,
                    maxCharacters,
                    ocr.path("enabled").asBoolean(true),
                    ocr.path("language").asText("eng"),
                    Boolean.parseBoolean(environmentOrDefault(
                            "JARVIS_PYTHON_OCR_ENABLED",
                            ocr.path("python-fallback").asText("true"))),
                    pythonExecutable);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid Jarvis application configuration", exception);
        }
    }

    public JarvisSettings(
            URI apiEndpoint,
            URI healthEndpoint,
            ApiProtocol apiProtocol,
            String model,
            String apiKey,
            Duration apiTimeout,
            Path projectsRoot,
            Path dataDirectory,
            long maxDocumentBytes,
            int maxExtractedCharacters,
            boolean ocrEnabled,
            String ocrLanguage) {
        this(apiEndpoint, healthEndpoint, apiProtocol, model, apiKey, apiTimeout, projectsRoot, dataDirectory,
                maxDocumentBytes, maxExtractedCharacters, ocrEnabled, ocrLanguage, false, "python");
    }

    private static URI requireHttpUri(String value, String setting) {
        URI uri = URI.create(value);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null) {
            throw new IllegalArgumentException(setting + " must be an absolute HTTP(S) URL.");
        }
        return uri;
    }

    private static String environmentOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String configuredApiKey(ApiProtocol protocol) {
        String key = emptyToNull(System.getenv("JARVIS_API_KEY"));
        if (key != null) {
            return key;
        }
        return switch (protocol) {
            case ANTHROPIC -> emptyToNull(System.getenv("ANTHROPIC_API_KEY"));
            case OPENAI_COMPATIBLE -> emptyToNull(System.getenv("OPENAI_API_KEY"));
            case CLAW -> null;
        };
    }

    public enum ApiProtocol {
        CLAW,
        OPENAI_COMPATIBLE,
        ANTHROPIC;

        private static ApiProtocol parse(String value) {
            return switch (value.toLowerCase()) {
                case "claw" -> CLAW;
                case "openai-compatible" -> OPENAI_COMPATIBLE;
                case "anthropic" -> ANTHROPIC;
                default -> throw new IllegalArgumentException(
                        "api.protocol must be claw, openai-compatible, or anthropic.");
            };
        }
    }
}
