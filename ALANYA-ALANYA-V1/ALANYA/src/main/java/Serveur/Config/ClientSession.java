package Serveur.Config;


import java.io.*;
import java.net.*;
import java.util.ArrayList;

import Serveur.service.ServerConnection;

public class ClientSession {
    private final String username;
    private final ServerConnection server;
    private ObjectInputStream inputStream;
    private ObjectOutputStream outputStream;
    private boolean running = true;

    public ClientSession(String username, Socket socket, ServerConnection server) {
        this.username = username;
        this.server = server;

        try {
            this.outputStream = new ObjectOutputStream(socket.getOutputStream());
            this.inputStream = new ObjectInputStream(socket.getInputStream());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void start() {
        new Thread(() -> {
            while (running) {
                try {
                    Object obj = inputStream.readObject();
                    if (obj instanceof Message) {
                        Message message = (Message) obj;
                        handleMessage(message);
                    }
                } catch (Exception e) {
                    running = false;
                    server.removeClient(username);
                    try {
                        close();
                    } catch (IOException ioException) {
                        ioException.printStackTrace();
                    }
                }
            }
        }).start();
    }

    private void handleMessage(Message message) {
        switch (message.getType()) {
            case TEXT:
                server.sendMessage(message);
                break;
            case FILE:
                server.sendMessage(message);
                break;
            case DISCONNECTION:
                running = false;
                server.removeClient(username);
                try {
                    close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                break;
            default:
                break;
        }
    }

    public void sendMessage(Message message) {
        try {
            synchronized (outputStream) {
                outputStream.writeObject(message);
                outputStream.flush();
                System.out.println("Message envoyé à " + username + " : " + message.getContent());
            }
        } catch (IOException e) {
            System.err.println("Erreur lors de l'envoi à " + username + " : " + e.getMessage());
            e.printStackTrace();
        }
    }

    public String getUsername() {
        return username;
    }

    public ObjectInputStream getInputStream() {
        return inputStream;
    }

    public void close() throws IOException {
        inputStream.close();
        outputStream.close();
    }

    public void sendUserList(ArrayList<String> users) {
        try {
            outputStream.writeObject(users);
            outputStream.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}

