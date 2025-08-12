package com.jarvis.jarvisapk;
import java.sql.*;
import java.time.LocalDateTime;

public class ChatHistoryDAO {
    private static final String DB_PATH = "C:/Users/kksuc/IdeaProjects/Practice/src/test/java/stepDefinations/jarvis-desktop/server/DataBase/memory.accdb";
    private static final String DB_URL = "jdbc:ucanaccess://" + DB_PATH;

    public void saveChat(String userText, String aiResponse) {
        String sql = "INSERT INTO chat_history (user_text, ai_response, timestamp) VALUES (?, ?, ?)";
        try {Class.forName("net.ucanaccess.jdbc.UcanaccessDriver");
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userText);
            ps.setString(2, aiResponse);
            ps.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now()));
            ps.executeUpdate();
        }
    } catch (ClassNotFoundException e) {
        e.printStackTrace();
        System.err.println("UCanAccess driver not found!");
    } catch (SQLException e) {
        e.printStackTrace();
    
    }
}

public static void main(String[] args) {
    // Example usage
    ChatHistoryDAO dao = new ChatHistoryDAO();
    dao.saveChat("Hello, Jarvis!", "Hello! How can I assist you today?");
    System.out.println("Chat saved successfully.");
}

   /*  public String loadChatHistory() {
        StringBuilder history = new StringBuilder();
        String sql = "SELECT * FROM chat_history ORDER BY timestamp";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                history.append("User: ").append(rs.getString("user_text")).append("\n");
                history.append("Jarvis: ").append(rs.getString("ai_response")).append("\n");
                history.append("Time: ").append(rs.getTimestamp("timestamp")).append("\n---\n");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return history.toString();
    }*/
}
