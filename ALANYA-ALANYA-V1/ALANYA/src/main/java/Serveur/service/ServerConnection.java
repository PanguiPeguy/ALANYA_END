package Serveur.service;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import Client.service.DatabaseService;
import Client.service.UserStatus;
import Serveur.Config.Message;

public class ServerConnection {
    private ServerSocket serverSocket;
    private final int port;
    private volatile boolean running;
    private Map<String, ClientHandler> clients;
    private Map<String, ArrayList<Message>> offlineMessages;
    private ExecutorService executorService;
    private DatabaseService databaseService;

    private class ClientHandler {
        private final Socket socket;
        private final ObjectInputStream input;
        private final ObjectOutputStream output;
        private final String username;
        private long lastActiveTimestamp;

        public ClientHandler(Socket socket, ObjectInputStream input, ObjectOutputStream output, String username) {
            this.socket = socket;
            this.input = input;
            this.output = output;
            this.username = username;
            this.lastActiveTimestamp = System.currentTimeMillis();
        }

        public void sendMessage(Object message) throws IOException {
            synchronized (output) {
                try {
                    output.reset();
                    output.writeObject(message);
                    output.flush();

                    if (message instanceof Message) {
                        Message msg = (Message) message;
                        String logMessage = "Message envoyé à " + username + ", type=" + msg.getType();
                        if (msg.getType() == Message.MessageType.VOICE) {
                            logMessage += ", taille des données: " + (msg.getFileData() != null ? msg.getFileData().length : 0) + " octets";
                        } else if (msg.getType() == Message.MessageType.EMOJI) {
                            logMessage += ", ID émoji: " + msg.getEmojiId();
                        }
                        System.out.println(logMessage);
                    }
                } catch (IOException e) {
                    System.err.println("Erreur lors de l'envoi du message à " + username + ": " + e.getMessage());
                    throw e;
                }
            }
        }

        public void close() {
            try {
                if (input != null) input.close();
                if (output != null) output.close();
                if (socket != null) socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public ServerConnection(int port) {
        this.port = port;
        this.clients = new ConcurrentHashMap<>();
        this.offlineMessages = new ConcurrentHashMap<>();
        this.executorService = Executors.newCachedThreadPool();
        this.databaseService = new DatabaseService();
        System.out.println("Initialisation de DatabaseService pour le port " + port);
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        System.out.println("Serveur démarré sur le port " + port);

        if (databaseService == null) {
            System.err.println("Échec de l'initialisation de DatabaseService");
            return;
        }

        new Thread(() -> {
            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    executorService.execute(() -> handleClient(clientSocket));
                } catch (IOException e) {
                    if (running) e.printStackTrace();
                }
            }
        }).start();
    }

    private void handleClient(Socket clientSocket) {
        ObjectOutputStream output = null;
        ObjectInputStream input = null;
        String username = null;
        ClientHandler clientHandler = null;

        try {
            output = new ObjectOutputStream(clientSocket.getOutputStream());
            output.flush();

            input = new ObjectInputStream(clientSocket.getInputStream());

            Object obj = input.readObject();
            if (!(obj instanceof String)) {
                output.writeObject("ERROR: Format du nom d'utilisateur incorrect");
                output.flush();
                return;
            }

            username = (String) obj;

            if (clients.containsKey(username)) {
                output.writeObject("ERROR: Ce nom d'utilisateur est déjà utilisé");
                output.flush();
                return;
            }

            clientHandler = new ClientHandler(clientSocket, input, output, username);
            clients.put(username, clientHandler);

            System.out.println("Nouvel utilisateur connecté: " + username);

            ArrayList<UserStatus> currentUsers = new ArrayList<>();
            for (String user : clients.keySet()) {
                currentUsers.add(new UserStatus(user, true));
            }
            clientHandler.sendMessage(currentUsers);
            System.out.println("Liste des utilisateurs envoyée à " + username + ": " + currentUsers);

            if (offlineMessages.containsKey(username)) {
                ArrayList<Message> messages = offlineMessages.get(username);
                for (Message msg : messages) {
                    clientHandler.sendMessage(msg);
                    System.out.println("Message hors ligne envoyé à " + username + ": type=" + msg.getType() +
                            ", taille données=" + (msg.getFileData() != null ? msg.getFileData().length : 0) + " octets");
                }
                offlineMessages.remove(username);
            }

            broadcastConnectionMessage(username, true);

            broadcastUserList();

            while (running) {
                Object messageObj = input.readObject();

                if (!(messageObj instanceof Message)) {
                    System.err.println("Objet reçu n'est pas un message: " + messageObj);
                    continue;
                }

                Message message = (Message) messageObj;

                String logMessage = "Message reçu de " + username + " pour " + message.getReceiver() + ", type: " + message.getType();
                if (message.getType() == Message.MessageType.VOICE) {
                    logMessage += ", taille: " + (message.getFileData() != null ? message.getFileData().length : 0) + " octets";
                } else if (message.getType() == Message.MessageType.EMOJI) {
                    logMessage += ", ID émoji: " + message.getEmojiId();
                }
                System.out.println(logMessage);

                if (message.getType() == Message.MessageType.DISCONNECTION) {
                    System.out.println(username + " s'est déconnecté.");
                    broadcastConnectionMessage(username, false);
                    break;
                } else if (message.getType() == Message.MessageType.HEARTBEAT) {
                    clientHandler.lastActiveTimestamp = System.currentTimeMillis();
                    continue;
                }

                // Sauvegarder le message dans la base de données
                System.out.println("Tentative de sauvegarde du message: " + message.getSender() + " -> " + message.getReceiver());
                if (databaseService != null) {
                    try {
                        databaseService.saveMessage(message);
                        System.out.println("Message sauvegardé avec succès dans la base de données");
                    } catch (Exception e) {
                        System.err.println("Erreur lors de la sauvegarde du message: " + e.getMessage());
                        e.printStackTrace();
                    }
                } else {
                    System.err.println("DatabaseService est null, impossible de sauvegarder le message");
                }

                ClientHandler receiverHandler = clients.get(message.getReceiver());
                if (receiverHandler != null) {
                    try {
                        if (message.getType() == Message.MessageType.VOICE &&
                                (message.getFileData() == null || message.getFileData().length == 0)) {
                            System.err.println("ATTENTION: Message vocal sans données audio");
                        }

                        receiverHandler.sendMessage(message);

                        logMessage = "Message relayé à " + message.getReceiver() + ", type=" + message.getType();
                        if (message.getType() == Message.MessageType.VOICE) {
                            logMessage += ", taille des données: " + (message.getFileData() != null ? message.getFileData().length : 0) + " octets";
                        } else if (message.getType() == Message.MessageType.EMOJI) {
                            logMessage += ", ID émoji: " + message.getEmojiId();
                        }
                        System.out.println(logMessage);

                        Message ack = new Message("SERVER", username,
                                "Message livré à " + message.getReceiver());
                        ack.setType(Message.MessageType.ACK);
                        clientHandler.sendMessage(ack);
                    } catch (IOException e) {
                        System.err.println("Erreur lors de l'envoi du message à " + message.getReceiver() + ": " + e.getMessage());
                        e.printStackTrace();

                        clients.remove(message.getReceiver());
                        receiverHandler.close();
                        broadcastUserList();
                    }
                } else {
                    System.out.println("Destinataire " + message.getReceiver() + " non connecté. Stockage du message. Taille données: " +
                            (message.getFileData() != null ? message.getFileData().length : 0) + " octets");
                    offlineMessages.computeIfAbsent(message.getReceiver(), k -> new ArrayList<>()).add(message);
                }
            }
        } catch (IOException e) {
            System.err.println("Erreur I/O avec le client " + username + ": " + e.getMessage());
            e.printStackTrace();
        } catch (ClassNotFoundException e) {
            System.err.println("Erreur de classe avec le client " + username + ": " + e.getMessage());
        } finally {
            if (username != null && clients.containsKey(username)) {
                clients.remove(username);
                broadcastConnectionMessage(username, false);
                broadcastUserList();
            }

            if (clientHandler != null) {
                clientHandler.close();
            } else {
                try {
                    if (input != null) input.close();
                    if (output != null) output.close();
                    if (clientSocket != null) clientSocket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void broadcastConnectionMessage(String username, boolean connected) {
        Message connectionMessage = new Message(
                "SERVER",
                null,
                username + (connected ? " s'est connecté." : " s'est déconnecté.")
        );
        connectionMessage.setType(Message.MessageType.TEXT);

        for (ClientHandler handler : clients.values()) {
            try {
                if (!handler.username.equals(username)) {
                    handler.sendMessage(connectionMessage);
                }
            } catch (IOException e) {
                System.err.println("Erreur lors de l'envoi du message de connexion à " + handler.username + ": " + e.getMessage());
            }
        }
        System.out.println("Message de " + (connected ? "connexion" : "déconnexion") + " diffusé pour " + username);
    }

    private void broadcastUserList() {
        ArrayList<UserStatus> userList = new ArrayList<>();
        for (String user : clients.keySet()) {
            userList.add(new UserStatus(user, true));
        }
        System.out.println("Diffusion de la liste des utilisateurs mise à jour: " + userList);

        for (ClientHandler handler : clients.values()) {
            try {
                handler.sendMessage(userList);
            } catch (IOException e) {
                System.err.println("Erreur lors de la diffusion de la liste des utilisateurs: " + e.getMessage());
            }
        }
    }

    public void close() throws IOException {
        running = false;
        executorService.shutdown();

        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
        }

        for (ClientHandler handler : clients.values()) {
            handler.close();
        }

        clients.clear();
        if (databaseService != null) {
            databaseService.closeConnection();
        }
        System.out.println("Serveur fermé");
    }

    public void removeClient(String username) {
        ClientHandler handler = clients.remove(username);
        if (handler != null) {
            handler.close();
            broadcastUserList();
            System.out.println("Client déconnecté: " + username);
        }
    }

    public void sendMessage(Message message) {
        String receiver = message.getReceiver();
        ClientHandler handler = clients.get(receiver);

        if (handler != null) {
            try {
                String logMessage = "Envoi direct d'un message à " + receiver + ", type=" + message.getType();
                if (message.getType() == Message.MessageType.VOICE) {
                    logMessage += ", taille: " + (message.getFileData() != null ? message.getFileData().length : 0) + " octets";
                } else if (message.getType() == Message.MessageType.EMOJI) {
                    logMessage += ", ID émoji: " + message.getEmojiId();
                }
                System.out.println(logMessage);

                handler.sendMessage(message);
                System.out.println("Message envoyé à " + receiver);
            } catch (IOException e) {
                System.err.println("Erreur lors de l'envoi du message à " + receiver + ": " + e.getMessage());
                e.printStackTrace();
                clients.remove(receiver);
                handler.close();
                broadcastUserList();
            }
        } else {
            System.out.println("Destinataire " + receiver + " non connecté. Stockage du message. Taille données: " +
                    (message.getFileData() != null ? message.getFileData().length : 0) + " octets");
            offlineMessages.computeIfAbsent(receiver, k -> new ArrayList<>()).add(message);
        }
    }
}