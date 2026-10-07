package com.jarvis.jarvisapk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class MultiLanguageTestOrchestrator {
    private static final Logger LOGGER = Logger.getLogger(MultiLanguageTestOrchestrator.class.getName());
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_TIMEOUT_SECONDS = 3600;
    private static final String CONFIG_RESOURCE = "/automation.yaml";

    private final Path projectRoot;
    private final Path reportsDirectory;
    private final Duration timeout;
    private final int maxOutputBytes;
    private final Map<String, Suite> suites;

    public MultiLanguageTestOrchestrator(Path projectRoot, Path reportsRoot) throws IOException {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(this.projectRoot)) {
            throw new IOException("Automation project root does not exist: " + this.projectRoot);
        }
        JsonNode config;
        try (InputStream input = MultiLanguageTestOrchestrator.class.getResourceAsStream(CONFIG_RESOURCE)) {
            if (input == null) {
                throw new IOException("Missing automation configuration: " + CONFIG_RESOURCE);
            }
            config = YAML.readTree(input);
        }

        long timeoutSeconds = config.path("process").path("timeout-seconds").asLong(0);
        long outputBytes = config.path("process").path("max-captured-output-bytes").asLong(0);
        if (timeoutSeconds <= 0 || timeoutSeconds > MAX_TIMEOUT_SECONDS || outputBytes <= 0
                || outputBytes > Integer.MAX_VALUE) {
            throw new IOException("Automation process timeout/output limits are invalid.");
        }
        timeout = Duration.ofSeconds(timeoutSeconds);
        maxOutputBytes = (int) outputBytes;

        Path relativeReportPath = Path.of(config.path("process").path("report-directory").asText(""));
        if (relativeReportPath.toString().isBlank() || relativeReportPath.isAbsolute()
                || relativeReportPath.normalize().startsWith("..")) {
            throw new IOException("Automation report-directory must be a safe relative path.");
        }
        Path normalizedReportsRoot = reportsRoot.toAbsolutePath().normalize();
        reportsDirectory = normalizedReportsRoot.resolve(relativeReportPath).normalize();
        if (!reportsDirectory.startsWith(normalizedReportsRoot)) {
            throw new IOException("Automation report path escapes its configured root.");
        }

        JsonNode suiteConfig = config.path("suites");
        if (!suiteConfig.isObject() || suiteConfig.isEmpty()) {
            throw new IOException("Automation configuration must define at least one suite.");
        }
        Map<String, Suite> parsedSuites = new LinkedHashMap<>();
        var suiteFields = suiteConfig.fields();
        while (suiteFields.hasNext()) {
            var entry = suiteFields.next();
            parsedSuites.put(entry.getKey(), parseSuite(entry.getKey(), entry.getValue()));
        }
        suites = Map.copyOf(parsedSuites);
    }

    public List<String> suiteNames() {
        return suites.keySet().stream().sorted().toList();
    }

    public RunReport run(String suiteName, Map<String, String> runtimeEnvironment) throws IOException {
        Objects.requireNonNull(runtimeEnvironment, "runtimeEnvironment");
        Suite suite = suites.get(suiteName);
        if (suite == null) {
            throw new IllegalArgumentException("Unknown automation suite: " + suiteName
                    + ". Configured suites: " + String.join(", ", suiteNames()));
        }
        Path workingDirectory = projectRoot.resolve(suite.workingDirectory()).normalize();
        if (!workingDirectory.startsWith(projectRoot) || !Files.isDirectory(workingDirectory)) {
            throw new IOException("Suite working directory must exist inside the project root.");
        }

        Path outputFile = Files.createTempFile("jarvis-test-run-", ".log");
        Instant started = Instant.now();
        boolean timedOut = false;
        int exitCode;
        String output;
        try {
            ProcessBuilder builder = new ProcessBuilder(suite.command())
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(outputFile.toFile());
            sanitizeEnvironment(builder.environment());
            for (Map.Entry<String, String> variable : runtimeEnvironment.entrySet()) {
                if (!variable.getKey().matches("[A-Z][A-Z0-9_]{0,63}")
                        || isSensitive(variable.getKey())
                        || variable.getValue() == null
                        || variable.getValue().length() > 2048) {
                    throw new IllegalArgumentException(
                            "Invalid or sensitive automation environment variable: " + variable.getKey());
                }
                builder.environment().put(variable.getKey(), variable.getValue());
            }

            Process process;
            try {
                process = builder.start();
            } catch (IOException exception) {
                throw new IOException("Could not start suite '" + suiteName + "' using executable '"
                        + suite.command().getFirst() + "'. Verify the runtime is installed and on PATH.", exception);
            }
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                timedOut = true;
                process.descendants().forEach(ProcessHandle::destroy);
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
                        throw new IOException("Timed-out suite process could not be stopped safely.");
                    }
                }
            }
            exitCode = timedOut ? -1 : process.exitValue();
            output = readTail(outputFile, maxOutputBytes);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running automation suite '" + suiteName + "'.", exception);
        } finally {
            Files.deleteIfExists(outputFile);
        }

        Instant finished = Instant.now();
        RunReport report = new RunReport(
                UUID.randomUUID().toString(),
                suiteName,
                suite.command(),
                started.toString(),
                finished.toString(),
                Duration.between(started, finished).toMillis(),
                exitCode,
                timedOut,
                output);
        persistReport(report);
        LOGGER.log(Level.INFO, "automation_run id={0} suite={1} exit={2} timeout={3} duration_ms={4}",
                new Object[]{report.id(), report.suite(), report.exitCode(), report.timedOut(), report.durationMillis()});
        return report;
    }

    private Suite parseSuite(String name, JsonNode node) throws IOException {
        if (!name.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IOException("Invalid automation suite identifier: " + name);
        }
        JsonNode commandNode = node.path("command");
        if (!commandNode.isArray() || commandNode.isEmpty()) {
            throw new IOException("Suite '" + name + "' must define a non-empty command argument list.");
        }
        List<String> command = new ArrayList<>();
        for (JsonNode argument : commandNode) {
            if (!argument.isTextual() || argument.asText().isBlank() || argument.asText().length() > 2048) {
                throw new IOException("Suite '" + name + "' has an invalid command argument.");
            }
            command.add(argument.asText());
        }
        String directory = node.path("working-directory").asText(".");
        Path relativeDirectory = Path.of(directory);
        if (directory.isBlank() || relativeDirectory.isAbsolute()
                || relativeDirectory.normalize().startsWith("..")) {
            throw new IOException("Suite '" + name + "' must use a safe project-relative working directory.");
        }
        return new Suite(List.copyOf(command), directory);
    }

    private void persistReport(RunReport report) throws IOException {
        Files.createDirectories(reportsDirectory);
        Path destination = reportsDirectory.resolve(report.id() + ".json");
        Path temporary = Files.createTempFile(reportsDirectory, ".report-", ".tmp");
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), report);
            try {
                Files.move(temporary, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private String readTail(Path outputFile, int limit) throws IOException {
        long size = Files.size(outputFile);
        long offset = Math.max(0, size - limit);
        try (var input = Files.newInputStream(outputFile)) {
            input.skipNBytes(offset);
            String text = new String(input.readNBytes(limit), StandardCharsets.UTF_8);
            return offset > 0 ? "[earlier output truncated]\n" + text : text;
        }
    }

    private static void sanitizeEnvironment(Map<String, String> environment) {
        environment.keySet().removeIf(MultiLanguageTestOrchestrator::isSensitive);
    }

    private static boolean isSensitive(String key) {
        return key.matches("(?i).*(API_KEY|TOKEN|PASSWORD|SECRET|CREDENTIAL).*");
    }

    private record Suite(List<String> command, String workingDirectory) {
    }

    public record RunReport(
            String id,
            String suite,
            List<String> command,
            String startedAt,
            String finishedAt,
            long durationMillis,
            int exitCode,
            boolean timedOut,
            String output) {
        public boolean succeeded() {
            return !timedOut && exitCode == 0;
        }
    }
}
