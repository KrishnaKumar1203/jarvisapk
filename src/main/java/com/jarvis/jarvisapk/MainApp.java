package com.jarvis.jarvisapk;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class MainApp extends Application {
   @Override
public void start(Stage primaryStage) throws Exception {
    FXMLLoader loader = new FXMLLoader();
    loader.setLocation(MainApp.class.getResource("/com/jarvis/jarvisapk/ui.fxml"));
    Parent root = loader.load();

    Scene scene = new Scene(root);
    primaryStage.setTitle("Jarvis Desktop Assistant");
    primaryStage.setScene(scene);
    primaryStage.show();
}

    public static void main(String[] args) {
        launch(args);
    }
}
