package com.jarvis.jarvisapk;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class MainApp extends Application {
    @Override
    public void start(Stage primaryStage) throws Exception {
        FXMLLoader loader = new FXMLLoader(
                MainApp.class.getResource("/com/jarvis/jarvisapk/ui.fxml"));
        Parent root = loader.load();

        Scene scene = new Scene(root, 1180, 760);
        var stylesheet = MainApp.class.getResource("/com/jarvis/jarvisapk/jarvis.css");
        if (stylesheet == null) {
            throw new IllegalStateException("Missing Jarvis stylesheet.");
        }
        scene.getStylesheets().add(stylesheet.toExternalForm());
        primaryStage.setTitle("Jarvis Desktop Assistant");
        primaryStage.setMinWidth(900);
        primaryStage.setMinHeight(620);
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
