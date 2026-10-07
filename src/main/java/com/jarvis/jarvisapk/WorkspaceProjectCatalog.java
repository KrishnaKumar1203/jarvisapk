package com.jarvis.jarvisapk;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class WorkspaceProjectCatalog {
    private static final int MAX_DEPTH = 8;
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            ".git", ".idea", ".gradle", ".mvn", ".venv", ".venv-1", "node_modules",
            "target", "build", "dist", "out", "coverage", "__pycache__", "vendor", "lib");
    private static final Map<String, String> LANGUAGES = Map.ofEntries(
            Map.entry(".java", "Java"),
            Map.entry(".kt", "Kotlin"),
            Map.entry(".js", "JavaScript"),
            Map.entry(".jsx", "JavaScript"),
            Map.entry(".ts", "TypeScript"),
            Map.entry(".tsx", "TypeScript"),
            Map.entry(".py", "Python"),
            Map.entry(".rs", "Rust"),
            Map.entry(".go", "Go"));

    private final Path projectsRoot;
    private final Path currentProjectRoot;

    public WorkspaceProjectCatalog(Path projectsRoot) {
        this(projectsRoot, null);
    }

    public WorkspaceProjectCatalog(Path projectsRoot, Path currentProjectRoot) {
        this.projectsRoot = projectsRoot.toAbsolutePath().normalize();
        this.currentProjectRoot = currentProjectRoot == null
                ? null
                : currentProjectRoot.toAbsolutePath().normalize();
    }

    public List<ProjectSummary> scan() throws IOException {
        if (!Files.isDirectory(projectsRoot)) {
            throw new IOException("Projects folder does not exist: " + projectsRoot);
        }

        List<ProjectSummary> summaries = new ArrayList<>();
        try (var entries = Files.list(projectsRoot)) {
            for (Path entry : entries.filter(Files::isDirectory).sorted().toList()) {
                if (entry.toAbsolutePath().normalize().equals(projectsRoot)
                        || entry.toAbsolutePath().normalize().equals(currentProjectRoot)
                        || entry.getFileName().toString().startsWith(".")
                        || entry.getFileName().toString().endsWith(".worktrees")) {
                    continue;
                }
                summaries.add(summarize(entry));
            }
        }
        return List.copyOf(summaries);
    }

    private ProjectSummary summarize(Path projectRoot) throws IOException {
        Map<String, Integer> languageCounts = new LinkedHashMap<>();
        int[] sourceFiles = {0};
        Files.walkFileTree(projectRoot, Set.of(), MAX_DEPTH, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (!directory.equals(projectRoot)
                        && IGNORED_DIRECTORIES.contains(directory.getFileName().toString().toLowerCase())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (!attributes.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                String name = file.getFileName().toString().toLowerCase();
                int extensionStart = name.lastIndexOf('.');
                if (extensionStart >= 0) {
                    String language = LANGUAGES.get(name.substring(extensionStart));
                    if (language != null) {
                        sourceFiles[0]++;
                        languageCounts.merge(language, 1, Integer::sum);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return new ProjectSummary(
                projectRoot.getFileName().toString(),
                projectRoot.toAbsolutePath().normalize(),
                sourceFiles[0],
                Map.copyOf(languageCounts));
    }

    public record ProjectSummary(String name, Path path, int sourceFiles, Map<String, Integer> languages) {
        public String render() {
            String languageSummary = languages.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> entry.getKey() + " " + entry.getValue())
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("No supported source files found");
            return name + " — " + sourceFiles + " source files (" + languageSummary + ")";
        }
    }
}
