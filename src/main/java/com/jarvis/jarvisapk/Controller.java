package com.jarvis.jarvisapk;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.util.concurrent.CompletionException;
import java.util.HashMap;
import java.util.Map;

public class Controller {
    @FXML private Button startListeningButton;
    @FXML private Button stopListeningButton;
    @FXML private Button sendButton;
    @FXML private Button automationButton;
    @FXML private Label listeningStatusLabel;
    @FXML private Label apiStatusLabel;
    @FXML private TextArea outputArea;
    @FXML private TextArea inputField;
    @FXML private CheckBox speechOutputCheckbox;

    private SpeechRecognizer speechRecognizer;
    private TextToSpeech tts;
    private Stage primaryStage;
    private JarvisSettings settings;
    private ApiClient apiClient;
    private DocumentReaderService documentReader;
    private WorkspaceProjectCatalog projectCatalog;
    private MultiLanguageTestOrchestrator testOrchestrator;
    private ChatHistoryDAO chatHistoryDAO;
    private Path chatHistoryPath;
    private volatile boolean isListening;
    private Thread recognitionThread;

    @FXML
    public void initialize() {
        try {
            settings = JarvisSettings.load();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load application.yaml", exception);
        }
        apiClient = new ApiClient(settings);
        documentReader = new DocumentReaderService(settings);
        Path projectRoot = Path.of(System.getProperty("jarvis.project.root", System.getProperty("user.dir")))
                .toAbsolutePath().normalize();
        projectCatalog = new WorkspaceProjectCatalog(settings.projectsRoot(), projectRoot);
        try {
            testOrchestrator = new MultiLanguageTestOrchestrator(projectRoot, settings.dataDirectory());
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load automation.yaml", exception);
        }
        chatHistoryDAO = new ChatHistoryDAO(settings.dataDirectory());
        chatHistoryPath = settings.dataDirectory().resolve("chat-history.txt");
        speechRecognizer = new SpeechRecognizer();
        tts = new TextToSpeech();

        outputArea.setEditable(false);
        inputField.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER && event.isControlDown()) {
                handleSend();
                event.consume();
            }
        });
        stopListeningButton.setDisable(true);
        listeningStatusLabel.setText("");
        checkApiConnection();
    }

    private void checkApiConnection() {
        apiClient.checkConnectionAsync().whenComplete((connected, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                apiStatusLabel.setText("Offline · " + settings.apiEndpoint().getHost());
                apiStatusLabel.getStyleClass().setAll("status-offline");
            } else if (connected) {
                apiStatusLabel.setText("Connected · " + settings.model());
                apiStatusLabel.getStyleClass().setAll("status-online");
            } else {
                apiStatusLabel.setText("Service unavailable");
                apiStatusLabel.getStyleClass().setAll("status-offline");
            }
        }));
    }

    private void displayResponse(String question) {
        outputArea.appendText("You: " + question + System.lineSeparator());
        inputField.setDisable(true);
        sendButton.setDisable(true);

        apiClient.sendQuestionAsync(question).whenComplete((response, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                        ? failure.getCause()
                        : failure;
                outputArea.appendText("Jarvis: Request failed — " + cause.getMessage()
                        + System.lineSeparator() + System.lineSeparator());
                apiStatusLabel.setText("Request failed");
                apiStatusLabel.getStyleClass().setAll("status-offline");
            } else {
                outputArea.appendText("Jarvis: " + response
                        + System.lineSeparator() + System.lineSeparator());
                apiStatusLabel.setText("Connected · " + settings.model());
                apiStatusLabel.getStyleClass().setAll("status-online");
                try {
                    chatHistoryDAO.saveChat(question, response);
                    saveChatHistoryToFile(question, response);
                } catch (SQLException | IOException exception) {
                    outputArea.appendText("History warning: " + exception.getMessage()
                            + System.lineSeparator());
                }
                if (speechOutputCheckbox.isSelected()) {
                    Thread speechThread = new Thread(() -> tts.speak(response), "jarvis-speech-output");
                    speechThread.setDaemon(true);
                    speechThread.start();
                }
            }
            inputField.setDisable(false);
            sendButton.setDisable(false);
            inputField.requestFocus();
        }));
    }

    private void saveChatHistoryToFile(String userText, String aiResponse) throws IOException {
        Files.createDirectories(chatHistoryPath.getParent());
        String entry = "You: " + userText + System.lineSeparator()
                + "Jarvis: " + aiResponse + System.lineSeparator()
                + "---------------------------" + System.lineSeparator();
        Files.writeString(chatHistoryPath, entry, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @FXML
    private void handleSend() {
        String question = inputField.getText().trim();
        if (question.isEmpty() || sendButton.isDisabled()) {
            return;
        }
        inputField.clear();
        displayResponse(question);
    }

    @FXML
    private void handleStartListening() {
        if (isListening) {
            return;
        }
        isListening = true;
        startListeningButton.setDisable(true);
        stopListeningButton.setDisable(false);
        listeningStatusLabel.setText("Listening…");
        recognitionThread = new Thread(() -> {
            String recognizedText = speechRecognizer.recognizeSpeech();
            Platform.runLater(() -> {
                isListening = false;
                startListeningButton.setDisable(false);
                stopListeningButton.setDisable(true);
                listeningStatusLabel.setText("");
                if (recognizedText != null && !recognizedText.isBlank()) {
                    displayResponse(recognizedText);
                }
            });
        }, "jarvis-speech-recognition");
        recognitionThread.setDaemon(true);
        recognitionThread.start();
    }

    @FXML
    private void handleStopListening() {
        if (!isListening) {
            return;
        }
        isListening = false;
        if (recognitionThread != null) {
            recognitionThread.interrupt();
        }
        startListeningButton.setDisable(false);
        stopListeningButton.setDisable(true);
        listeningStatusLabel.setText("Stopping…");
    }

    @FXML
    private void handleLoadFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Open a document or source file");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Supported documents and code", "*.*"));
        File file = fileChooser.showOpenDialog(getStage());
        if (file == null) {
            return;
        }
        try {
            DocumentReaderService.ExtractedDocument document = documentReader.read(file.toPath());
            inputField.setText("Analyze this file: " + document.fileName()
                    + " (" + document.contentType() + ")" + System.lineSeparator()
                    + document.text());
            inputField.requestFocus();
            inputField.positionCaret(inputField.getLength());
        } catch (IOException | org.apache.tika.exception.TikaException | org.xml.sax.SAXException exception) {
            showAlert(Alert.AlertType.ERROR, "Could not read file", exception.getMessage());
        }
    }

    @FXML
    private void handleScanProjects() {
        try {
            var projects = projectCatalog.scan();
            if (projects.isEmpty()) {
                outputArea.appendText("No project folders found in "
                        + settings.projectsRoot() + System.lineSeparator());
                return;
            }
            outputArea.appendText("IdeaProjects catalog (read-only):"
                    + System.lineSeparator());
            projects.forEach(project ->
                    outputArea.appendText("  • " + project.render() + System.lineSeparator()));
            outputArea.appendText(System.lineSeparator());
        } catch (IOException exception) {
            showAlert(Alert.AlertType.ERROR, "Could not scan projects", exception.getMessage());
        }
    }

    @FXML
    private void handleRunAutomation() {
        var suites = testOrchestrator.suiteNames();
        if (suites.isEmpty()) {
            showAlert(Alert.AlertType.ERROR, "No automation suites", "No suites are configured in automation.yaml.");
            return;
        }
        ChoiceDialog<String> dialog = new ChoiceDialog<>(suites.getFirst(), suites);
        dialog.setTitle("Run automation");
        dialog.setHeaderText("Select a configured Java, JavaScript/Playwright, or Python suite.");
        dialog.setContentText("Test suite:");
        var selected = dialog.showAndWait();
        if (selected.isEmpty()) {
            return;
        }

        automationButton.setDisable(true);
        Thread worker = new Thread(() -> {
            try {
                Map<String, String> environment = new HashMap<>();
                String toolkitUrl = System.getenv("JARVIS_TOOLKIT_URL");
                if (toolkitUrl != null && !toolkitUrl.isBlank()) {
                    environment.put("JARVIS_TOOLKIT_URL", toolkitUrl);
                }
                MultiLanguageTestOrchestrator.RunReport report =
                        testOrchestrator.run(selected.get(), environment);
                Platform.runLater(() -> {
                    outputArea.appendText("Automation " + report.suite() + ": "
                            + (report.succeeded() ? "PASSED" : report.timedOut() ? "TIMED OUT" : "FAILED")
                            + " (" + report.durationMillis() + " ms)" + System.lineSeparator()
                            + report.output() + System.lineSeparator()
                            + "Report ID: " + report.id() + System.lineSeparator() + System.lineSeparator());
                    automationButton.setDisable(false);
                });
            } catch (IOException | IllegalArgumentException exception) {
                Platform.runLater(() -> {
                    automationButton.setDisable(false);
                    showAlert(Alert.AlertType.ERROR, "Automation could not run", exception.getMessage());
                });
            }
        }, "jarvis-automation-" + selected.get());
        worker.setDaemon(true);
        worker.start();
    }

    @FXML
    private void handleChat() {
        String conversation = outputArea.getText().trim();
        if (conversation.isEmpty()) {
            showAlert(Alert.AlertType.INFORMATION, "Nothing to save", "Start a conversation first.");
            return;
        }
        try {
            Files.createDirectories(chatHistoryPath.getParent());
            Files.writeString(chatHistoryPath, conversation + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            showAlert(Alert.AlertType.INFORMATION, "Conversation saved",
                    "Conversation exported to " + chatHistoryPath);
        } catch (IOException exception) {
            showAlert(Alert.AlertType.ERROR, "Could not save conversation", exception.getMessage());
        }
    }

    @FXML
    private void handleSaveFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Export conversation");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Text files", "*.txt"));
        File file = fileChooser.showSaveDialog(getStage());
        if (file == null) {
            return;
        }
        try {
            Files.writeString(file.toPath(), outputArea.getText());
        } catch (IOException exception) {
            showAlert(Alert.AlertType.ERROR, "Could not export conversation", exception.getMessage());
        }
    }

    private Stage getStage() {
        if (primaryStage == null) {
            primaryStage = (Stage) inputField.getScene().getWindow();
        }
        return primaryStage;
    }

    private void showAlert(Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message == null || message.isBlank() ? "An unexpected error occurred." : message);
        alert.showAndWait();
    }
}
