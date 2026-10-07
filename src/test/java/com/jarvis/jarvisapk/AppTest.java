package com.jarvis.jarvisapk;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppTest {
    @Test
    void includesTheJavaFxViewAndStylesheetInTheApplicationResources() {
        assertNotNull(MainApp.class.getResource("/com/jarvis/jarvisapk/ui.fxml"));
        assertNotNull(MainApp.class.getResource("/com/jarvis/jarvisapk/jarvis.css"));
    }

    @Test
    void exposesTheConfiguredAutomationActionInTheDesktopView() throws IOException {
        String view = readResource("/com/jarvis/jarvisapk/ui.fxml");

        assertTrue(view.contains("fx:id=\"automationButton\""),
                "The desktop view must retain its automation button.");
        assertTrue(view.contains("onAction=\"#handleRunAutomation\""),
                "The automation button must be connected to its controller action.");
        assertTrue(view.contains("fx:id=\"inputField\""),
                "The desktop view must retain its conversation input.");
    }

    private String readResource(String resourcePath) throws IOException {
        try (InputStream resource = MainApp.class.getResourceAsStream(resourcePath)) {
            assertNotNull(resource, "Missing application resource: " + resourcePath);
            return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
