package com.jarvis.jarvisapk;

import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.Path;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;

public class Controller {
    @FXML private Button startListeningButton;
    @FXML private Button stopListeningButton;
    @FXML private Label listeningStatusLabel;

    @FXML private TextArea outputArea;
    @FXML private TextField inputField;
    @FXML private CheckBox speechOutputCheckbox;

    private SpeechRecognizer speechRecognizer;
    private TextToSpeech tts;
    private Stage primaryStage;

    private volatile boolean isListening = false;
    private Thread recognitionThread;

    // Update this path to wherever you want your text file
    private final Path chatHistoryPath = Path.of(
        "C:/Users/kksuc/IdeaProjects/Practice/src/test/java/stepDefinations/jarvis-desktop/memory.txt"
    );
    private final ChatHistoryDAO chatHistoryDAO = new ChatHistoryDAO();

    @FXML
    public void initialize() {
        speechRecognizer = new SpeechRecognizer();
        tts = new TextToSpeech();
        inputField.setOnAction(event -> handleSend());
        startListeningButton.setDisable(false);
        stopListeningButton.setDisable(true);
        listeningStatusLabel.setText("");
    }

    private void saveChatHistoryToDB(String userText, String aiResponse) {
        chatHistoryDAO.saveChat(userText, aiResponse);
    }

    // Save chat (User/Jarvis) to text file in append mode
    private void saveChatHistoryToFile(String userText, String aiResponse) {
        try {
            Files.createDirectories(chatHistoryPath.getParent());
            String entry = 
                "User: " + userText + System.lineSeparator() +
                "Jarvis: " + aiResponse + System.lineSeparator() +
                "---------------------------" + System.lineSeparator();
            Files.writeString(
                chatHistoryPath,
                entry,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            showAlert(Alert.AlertType.ERROR, "Error", "Failed to save to file: " + e.getMessage());
        }
    }

    // Called for every chat exchange (Send message or recognized speech)
    private void displayResponse(String question) {
        outputArea.appendText("User: " + question + "\n");
        String response = new ApiClient().sendQuestion(question);
        outputArea.appendText("Jarvis: " + response + "\n\n");

        if (speechOutputCheckbox.isSelected()) {
            tts.speak(response);
        }

        // // Save to both DB and file
        // saveChatHistoryToDB(question, response);
        // saveChatHistoryToFile(question, response);
    }

    // Save button handler: scan ALL chat history and write missing pairs to both destinations
    @FXML
    private void handleChat() {
        String chatText = outputArea.getText().trim();
        if (chatText.isBlank()) {
            showAlert(Alert.AlertType.ERROR, "Error", "No chat to save!");
            return;
        }

        try {
            Files.createDirectories(chatHistoryPath.getParent());
            String[] lines = chatText.split("\\r?\\n");
            String currentUserMsg = null;
            String currentJarvisMsg = null;
            int savedCount = 0;

            for (String line : lines) {
                if (line.startsWith("User:")) {
                    currentUserMsg = line.substring(5).trim();
                } else if (line.startsWith("Jarvis:")) {
                    currentJarvisMsg = line.substring(7).trim();
                    if (currentUserMsg != null && currentJarvisMsg != null) {
                        // Save to file
                        String entry = 
                            "User: " + currentUserMsg + System.lineSeparator() +
                            "Jarvis: " + currentJarvisMsg + System.lineSeparator() +
                            "---------------------------" + System.lineSeparator();
                        Files.writeString(
                            chatHistoryPath, entry,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND
                        );
                        // Save to DB
                        saveChatHistoryToDB(currentUserMsg, currentJarvisMsg);
                        savedCount++;
                        currentUserMsg = null;
                        currentJarvisMsg = null;
                    }
                }
            }
            showAlert(Alert.AlertType.INFORMATION, "Success", 
                savedCount > 0 
                    ? ("All chat history saved to file and database.") 
                    : ("No new chat pairs found to save!")
            );
        } catch (IOException e) {
            showAlert(Alert.AlertType.ERROR, "Error", "Failed to save chat: " + e.getMessage());
        }
    }

    @FXML
    private void handleSend() {
        String question = inputField.getText().trim();
        if (question.isEmpty()) return;

        if (question.equalsIgnoreCase("save my information")) {
            // Manual save of ALL chat to file only (could extend to DB if you want)
            try {
                Files.createDirectories(chatHistoryPath.getParent());
                Files.writeString(
                    chatHistoryPath,
                    outputArea.getText() + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND
                );
                showAlert(Alert.AlertType.INFORMATION, "Success", "All chat saved to text file.");
            } catch (IOException e) {
                showAlert(Alert.AlertType.ERROR, "Error", "Failed to save chat: " + e.getMessage());
            }
            inputField.clear();
            return;
        }

        displayResponse(question);
        inputField.clear();
    }

    @FXML
    private void handleStartListening() {
        if (isListening) return;
        isListening = true;

        Platform.runLater(() -> {
            startListeningButton.setDisable(true);
            stopListeningButton.setDisable(false);
            listeningStatusLabel.setText("Listening...");
        });

        recognitionThread = new Thread(() -> {
            String recognizedText = speechRecognizer.recognizeSpeech();
            isListening = false;

            Platform.runLater(() -> {
                startListeningButton.setDisable(false);
                stopListeningButton.setDisable(true);
                listeningStatusLabel.setText("");
                if (recognizedText != null && !recognizedText.isBlank()) {
                    inputField.setText(recognizedText);
                    displayResponse(recognizedText);
                }
            });
        });
        recognitionThread.setDaemon(true);
        recognitionThread.start();
    }

    @FXML
    private void handleStopListening() {
        if (!isListening) return;
        isListening = false;

        Platform.runLater(() -> {
            startListeningButton.setDisable(false);
            stopListeningButton.setDisable(true);
            listeningStatusLabel.setText("");
        });

        if (recognitionThread != null && recognitionThread.isAlive()) {
            recognitionThread.interrupt();
            try {
                recognitionThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @FXML
    private void handleLoadFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Open Text File");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text Files", "*.txt"));
        File file = fileChooser.showOpenDialog(getStage());
        if (file != null) {
            try {
                String content = Files.readString(file.toPath());
                inputField.setText(content);
            } catch (IOException e) {
                showAlert(Alert.AlertType.ERROR, "Error", "Failed to read file: " + e.getMessage());
            }
        }
    }

    @FXML
    private void handleSaveFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save Text File");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text Files", "*.txt"));
        File file = fileChooser.showSaveDialog(getStage());
        if (file != null) {
            try {
                Files.writeString(file.toPath(), inputField.getText());
            } catch (IOException e) {
                showAlert(Alert.AlertType.ERROR, "Error", "Failed to save file: " + e.getMessage());
            }
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
        alert.setContentText(message);
        alert.showAndWait();
    }
}
