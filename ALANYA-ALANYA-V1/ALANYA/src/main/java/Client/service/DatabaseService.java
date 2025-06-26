package Client.service;

import Serveur.Config.Message;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DatabaseService {
    private Connection connection;

    public DatabaseService() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            String url = "jdbc:mysql://0.0.0.0:3306/ALANYA_BD?useSSL=false&serverTimezone=UTC";
            String dbUser = "root";
            String dbPassword = "22p3?9";
            connection = DriverManager.getConnection(url, dbUser, dbPassword);
            System.out.println("Connexion à la base de données établie avec succès.");
        } catch (ClassNotFoundException | SQLException e) {
            System.err.println("Erreur lors de la connexion à la base de données : " + e.getMessage());
            connection = null;
        }
    }
    public List<Message> getNewMessagesForConversation(String user1, String user2) {
        List<Message> messages = new ArrayList<>();
        try {
            // Requête SQL pour récupérer les nouveaux messages (vous devrez peut-être adapter cette requête)
            String query = "SELECT * FROM message WHERE " +
                    "((sender = ? AND receiver = ?) OR (sender = ? AND receiver = ?)) " +
                    "ORDER BY timestamp ASC";

            PreparedStatement stmt = connection.prepareStatement(query);
            stmt.setString(1, user1);
            stmt.setString(2, user2);
            stmt.setString(3, user2);
            stmt.setString(4, user1);

            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                Message message = new Message();
                message.setSender(rs.getString("sender"));
                message.setReceiver(rs.getString("receiver"));
                message.setContent(rs.getString("content"));
                message.setType(Message.MessageType.valueOf(rs.getString("type")));

                if (message.getType() == Message.MessageType.FILE ||
                        message.getType() == Message.MessageType.VOICE) {
                    message.setFileData(rs.getBytes("file_data"));
                    message.setFileName(rs.getString("file_name"));
                }

                if (message.getType() == Message.MessageType.EMOJI) {
                    message.setEmojiId(rs.getInt("emoji_id"));
                }

                messages.add(message);
            }

            rs.close();
            stmt.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return messages;
    }
    public Set<String> getAllContacts(String username) {
        Set<String> contacts = new HashSet<>();
        if (connection == null) return contacts;

        String query = "SELECT DISTINCT sender FROM message WHERE receiver = ? " +
                "UNION SELECT DISTINCT receiver FROM message WHERE sender = ?";
        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, username);
            stmt.setString(2, username);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                String contact = rs.getString("sender");
                if (!contact.equals(username)) {
                    contacts.add(contact);
                } else {
                    contact = rs.getString("receiver");
                    if (!contact.equals(username)) {
                        contacts.add(contact);
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("Erreur lors de la récupération des contacts : " + e.getMessage());
        }
        return contacts;
    }
    public Set<String> getAllUsers() {
        Set<String> users = new HashSet<>();
        try {
            String query = "SELECT userName FROM Users";
            PreparedStatement stmt = connection.prepareStatement(query);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                users.add(rs.getString("username"));
            }

            rs.close();
            stmt.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return users;
    }
    public byte[] getProfilePicture(String username) {
        if (connection == null) return null;

        String query = "SELECT profilPicture FROM Users WHERE userName = ?";
        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, username);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getBytes("profilPicture");
            }
        } catch (SQLException e) {
            System.err.println("Erreur lors de la récupération de la photo de profil pour " + username + " : " + e.getMessage());
        }
        return null;
    }
    public void saveMessage(Message message) {
        if (connection == null) return;

        String query = "INSERT INTO message (sender, receiver, content, type, file_data, file_name, emoji_id, timestamp) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, message.getSender());
            stmt.setString(2, message.getReceiver());
            stmt.setString(3, message.getContent());
            stmt.setString(4, message.getType().toString());
            if (message.getFileData() != null) {
                stmt.setBytes(5, message.getFileData());
            } else {
                stmt.setNull(5, Types.BLOB);
            }
            stmt.setString(6, message.getFileName());
            if (message.getType() == Message.MessageType.EMOJI) {
                stmt.setInt(7, message.getEmojiId());
            } else {
                stmt.setNull(7, Types.INTEGER);
            }
            stmt.setTimestamp(8, new Timestamp(System.currentTimeMillis()));
            stmt.executeUpdate();
            System.out.println("Message sauvegardé dans la base de données : " + message.getSender() + " -> " + message.getReceiver());
        } catch (SQLException e) {
            System.err.println("Erreur lors de la sauvegarde du message : " + e.getMessage());
        }
    }
    public List<Message> getMessagesForConversation(String user1, String user2) {
        List<Message> messages = new ArrayList<>();
        if (connection == null) return messages;

        String query = "SELECT * FROM message WHERE (sender = ? AND receiver = ?) OR (sender = ? AND receiver = ?) ORDER BY timestamp ASC";
        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, user1);
            stmt.setString(2, user2);
            stmt.setString(3, user2);
            stmt.setString(4, user1);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                Message message = new Message();
                message.setSender(rs.getString("sender"));
                message.setReceiver(rs.getString("receiver"));
                message.setContent(rs.getString("content"));
                message.setType(Message.MessageType.valueOf(rs.getString("type")));
                message.setFileData(rs.getBytes("file_data"));
                message.setFileName(rs.getString("file_name"));
                message.setEmojiId(rs.getInt("emoji_id"));
                messages.add(message);
            }
        } catch (SQLException e) {
            System.err.println("Erreur lors de la récupération des messages : " + e.getMessage());
        }
        return messages;
    }
    public void closeConnection() {
        if (connection != null) {
            try {
                connection.close();
                System.out.println("Connexion à la base de données fermée.");
            } catch (SQLException e) {
                System.err.println("Erreur lors de la fermeture de la connexion : " + e.getMessage());
            }
        }
    }
}