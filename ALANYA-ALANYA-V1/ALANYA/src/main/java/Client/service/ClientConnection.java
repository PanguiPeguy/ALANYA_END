package Client.service;

import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import Serveur.Config.Message;

public class ClientConnection {
    private Socket socket;
    private ObjectOutputStream output;
    private ObjectInputStream input;
    private String username;
    private MessageHandler messageHandler;
    private UserListHandler userListHandler;
    private final AtomicBoolean connected = new AtomicBoolean(false);

    public interface MessageHandler {
        void handleMessage(Message message);
    }

    public interface UserListHandler {
        void handleUserList(ArrayList<UserStatus> users);
    }

    public ClientConnection(String username, MessageHandler messageHandler, UserListHandler userListHandler) {
        this.username = username;
        this.messageHandler = messageHandler;
        this.userListHandler = userListHandler;
    }

    public boolean connect(String host, int port) {
        try {
            socket = new Socket(host, port);
            output = new ObjectOutputStream(socket.getOutputStream());
            output.flush();
            input = new ObjectInputStream(socket.getInputStream());

            output.writeObject(username);
            output.flush();

            Object response = input.readObject();
            if (response instanceof String && ((String) response).startsWith("ERROR")) {
                System.err.println("Erreur de connexion : " + response);
                close();
                return false;
            }

            connected.set(true);

            new Thread(() -> startListening()).start();
            System.out.println("Connecté au serveur avec succès en tant que " + username);
            return true;
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("Erreur lors de la connexion : " + e.getMessage());
            close();
            return false;
        }
    }

    private void startListening() {
        try {
            while (connected.get()) {
                Object object = null;
                try {
                    object = input.readObject();
                } catch (ClassNotFoundException e) {
                    System.err.println("Erreur de désérialisation : " + e.getMessage());
                    continue;
                }
                if (object instanceof Message) {
                    Message message = (Message) object;
                    System.out.println("Message reçu: type=" + message.getType() + ", de=" + message.getSender() +
                            ", taille données=" + (message.getFileData() != null ? message.getFileData().length : 0) + " octets" +
                            ", emojiId=" + message.getEmojiId());
                    if (messageHandler != null) {
                        messageHandler.handleMessage(message);
                    }
                } else if (object instanceof ArrayList) {
                    @SuppressWarnings("unchecked")
                    ArrayList<UserStatus> users = (ArrayList<UserStatus>) object;
                    System.out.println("Liste des utilisateurs reçue : " + users);
                    if (userListHandler != null) {
                        userListHandler.handleUserList(users);
                    }
                } else {
                    System.err.println("Objet reçu inconnu : " + (object != null ? object.getClass().getName() : "null"));
                }
            }
        } catch (IOException e) {
            if (connected.get()) {
                System.err.println("Erreur lors de la réception des messages : " + e.getMessage());
                close();
            }
        }
    }

    public void sendMessage(String receiver, String content) {
        if (!connected.get() || receiver == null || content == null) return;

        Message message = new Message(username, receiver, content);
        message.setType(Message.MessageType.TEXT);
        sendMessage(message);
    }

    public void sendFile(String receiver, File file) {
        if (!connected.get() || receiver == null || file == null) return;

        try {
            byte[] fileData = Files.readAllBytes(file.toPath());
            Message message = new Message(username, receiver, "Fichier : " + file.getName());
            message.setFileData(fileData);
            message.setFileName(file.getName());
            message.setType(Message.MessageType.FILE);
            sendMessage(message);
            System.out.println("Fichier envoyé à " + receiver + ", taille : " + fileData.length + " octets");
        } catch (IOException e) {
            System.err.println("Erreur lors de l'envoi du fichier : " + e.getMessage());
        }
    }

    public void sendVoiceMessage(String receiver, byte[] audioData) {
        if (!connected.get() || receiver == null || audioData == null) return;

        Message message = new Message(username, receiver, "Message vocal");
        message.setFileData(audioData);
        message.setFileName("voice_message.wav");
        message.setType(Message.MessageType.VOICE);
        sendMessage(message);
        System.out.println("Message vocal envoyé à " + receiver + ", taille : " + (audioData != null ? audioData.length : 0) + " octets");
    }

    public void sendEmoji(String receiver, int emojiId) {
        if (!connected.get() || receiver == null) return;

        Message message = new Message(username, receiver, emojiId);
        message.setType(Message.MessageType.EMOJI);
        sendMessage(message);
        System.out.println("Émoji envoyé à " + receiver + ", ID : " + emojiId);
    }

    private void sendMessage(Message message) {
        try {
            if (connected.get() && output != null) {
                output.reset();
                output.writeObject(message);
                output.flush();
                System.out.println("Message envoyé: type=" + message.getType() + ", à=" + message.getReceiver() +
                        ", taille données=" + (message.getFileData() != null ? message.getFileData().length : 0) + " octets" +
                        ", emojiId=" + message.getEmojiId());
            }
        } catch (IOException e) {
            System.err.println("Erreur lors de l'envoi du message : " + e.getMessage());
            close();
        }
    }

    public void disconnect() {
        if (connected.getAndSet(false)) {
            Message disconnectMsg = new Message(username, null, "");
            disconnectMsg.setType(Message.MessageType.DISCONNECTION);
            sendMessage(disconnectMsg);

            close();
            System.out.println("Déconnexion de " + username);
        }
    }

    private void close() {
        try {
            if (input != null) input.close();
            if (output != null) output.close();
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException e) {
            System.err.println("Erreur lors de la fermeture de la connexion : " + e.getMessage());
        }
    }
}