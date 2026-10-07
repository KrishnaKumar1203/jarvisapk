package com.jarvis.jarvisapk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

public final class ApiClient {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JarvisSettings settings;
    private final HttpClient httpClient;

    public ApiClient(JarvisSettings settings) {
        this.settings = settings;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(settings.apiTimeout())
                .build();
    }

    public CompletableFuture<String> sendQuestionAsync(String question) {
        if (question == null || question.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Enter a message first."));
        }
        if (settings.apiKey() != null
                && !"https".equalsIgnoreCase(settings.apiEndpoint().getScheme())
                && !isLoopback(settings.apiEndpoint().getHost())) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("API keys may only be sent to HTTPS or a loopback service."));
        }

        try {
            ObjectNode payload = createPayload(question.strip());
            HttpRequest.Builder request = HttpRequest.newBuilder(settings.apiEndpoint())
                    .timeout(settings.apiTimeout())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload)));
            if (settings.apiProtocol() == JarvisSettings.ApiProtocol.ANTHROPIC) {
                request.header("anthropic-version", "2023-06-01");
                if (settings.apiKey() != null) {
                    request.header("x-api-key", settings.apiKey());
                }
            } else if (settings.apiKey() != null) {
                request.header("Authorization", "Bearer " + settings.apiKey());
            }

            return httpClient.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString())
                    .thenApply(this::readResponse);
        } catch (IOException | IllegalArgumentException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private ObjectNode createPayload(String question) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("model", settings.model());
        switch (settings.apiProtocol()) {
            case CLAW -> payload.put("prompt", question);
            case OPENAI_COMPATIBLE -> addChatMessage(payload, question);
            case ANTHROPIC -> {
                payload.put("max_tokens", 2048);
                addChatMessage(payload, question);
            }
        }
        return payload;
    }

    private void addChatMessage(ObjectNode payload, String question) {
        ArrayNode messages = payload.putArray("messages");
        messages.addObject().put("role", "user").put("content", question);
    }

    public CompletableFuture<Boolean> checkConnectionAsync() {
        HttpRequest request = HttpRequest.newBuilder(settings.healthEndpoint())
                .timeout(settings.apiTimeout())
                .header("Accept", "application/json")
                .GET()
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .thenApply(response -> response.statusCode() >= 200 && response.statusCode() < 300);
    }

    private String readResponse(HttpResponse<String> response) {
        JsonNode body;
        try {
            body = JSON.readTree(response.body());
            if (body == null) {
                body = JSON.createObjectNode();
            }
        } catch (IOException exception) {
            throw new IllegalStateException("AI service returned invalid JSON (HTTP "
                    + response.statusCode() + ").", exception);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String detail = body.path("error").asText("HTTP " + response.statusCode());
            throw new IllegalStateException("AI service request failed: " + detail);
        }

        String answer = switch (settings.apiProtocol()) {
            case CLAW -> firstNonBlank(body.path("output").asText(), body.path("reply").asText());
            case OPENAI_COMPATIBLE -> textContent(
                    body.path("choices").path(0).path("message").path("content"));
            case ANTHROPIC -> textContent(body.path("content"));
        };
        if (answer.isBlank()) {
            throw new IllegalStateException("AI service response did not contain text in the configured "
                    + settings.apiProtocol() + " response format.");
        }
        return answer;
    }

    private String textContent(JsonNode content) {
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode item : content) {
                String itemText = item.path("text").asText();
                if (!itemText.isBlank()) {
                    if (!text.isEmpty()) {
                        text.append(System.lineSeparator());
                    }
                    text.append(itemText);
                }
            }
            return text.toString();
        }
        return "";
    }

    private String firstNonBlank(String first, String second) {
        return first.isBlank() ? second : first;
    }

    private boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "[::1]".equals(host);
    }
}
