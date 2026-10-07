package com.jarvis.jarvisapk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiLanguageTestOrchestratorTest {
    @TempDir
    Path projectRoot;

    @Test
    void loadsConfiguredPolyglotSuites() throws IOException {
        MultiLanguageTestOrchestrator orchestrator =
                new MultiLanguageTestOrchestrator(projectRoot, projectRoot.resolve("reports"));

        assertNotNull(PythonVisionExecutor.class.getResource("/python/vision_ocr.py"));
        assertEquals(
                java.util.List.of("java", "javascript-unit", "playwright", "python"),
                orchestrator.suiteNames());
    }

    @Test
    void refusesSensitiveRuntimeEnvironmentVariables() throws IOException {
        MultiLanguageTestOrchestrator orchestrator =
                new MultiLanguageTestOrchestrator(projectRoot, projectRoot.resolve("reports"));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> orchestrator.run("javascript-unit", Map.of("OPENAI_API_KEY", "must-not-leak")));

        assertTrue(exception.getMessage().contains("sensitive"));
        assertFalse(java.nio.file.Files.exists(projectRoot.resolve("reports")));
    }

    @Test
    void reportsUnknownSuitesWithoutStartingAProcess() throws IOException {
        MultiLanguageTestOrchestrator orchestrator =
                new MultiLanguageTestOrchestrator(projectRoot, projectRoot.resolve("reports"));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> orchestrator.run("not-configured", Map.of()));

        assertTrue(exception.getMessage().contains("not-configured"));
        assertFalse(java.nio.file.Files.exists(projectRoot.resolve("reports")));
    }
}
