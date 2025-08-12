package com.jarvis.jarvisapk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

public class ApiClient {
    private static final String API_URL = "http://localhost:5000/chat";
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String sendQuestion(String question) {
        try {
            URL url = new URL(API_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            String payload = objectMapper.writeValueAsString(new Payload(question));
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes());
            }

            InputStream responseStream = conn.getResponseCode() < 400 ?
                    conn.getInputStream() : conn.getErrorStream();

            try (BufferedReader in = new BufferedReader(new InputStreamReader(responseStream))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null) {
                    response.append(line);
                }
                JsonNode json = objectMapper.readTree(response.toString());
                return json.has("reply") ? json.get("reply").asText() : "No reply in response";
            }
        } catch (Exception e) {
            e.printStackTrace();
            return "Error: Couldn't contact backend";
        }
    }

    static class Payload {
        public String prompt;
        public Payload(String prompt) { this.prompt = prompt; }
    }
}
