package com.jarvis.jarvisapk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;

public final class ChatHistoryDAO {
    private final Path databasePath;

    public ChatHistoryDAO(Path dataDirectory) {
        this.databasePath = dataDirectory.resolve("chat-history.db").toAbsolutePath().normalize();
    }

    public void saveChat(String userText, String aiResponse) throws SQLException {
        try {
            Files.createDirectories(databasePath.getParent());
        } catch (java.io.IOException exception) {
            throw new SQLException("Could not create Jarvis data directory.", exception);
        }

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS chat_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        user_text TEXT NOT NULL,
                        ai_response TEXT NOT NULL,
                        created_at TEXT NOT NULL
                    )
                    """);
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO chat_history (user_text, ai_response, created_at)
                    VALUES (?, ?, ?)
                    """)) {
                insert.setString(1, userText);
                insert.setString(2, aiResponse);
                insert.setString(3, Instant.now().toString());
                insert.executeUpdate();
            }
        }
    }
}
