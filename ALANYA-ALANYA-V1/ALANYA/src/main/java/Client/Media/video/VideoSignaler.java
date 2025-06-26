package Client.Media.video;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class VideoSignaler {
    private static final Logger logger = LoggerFactory.getLogger(VideoSignaler.class);
    private static final int PORT = 6005;
    private ServerSocket serverSocket;
    private ExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private CallListener callListener;

    public VideoSignaler() {
        this.executorService = Executors.newCachedThreadPool();
        startServer();
    }

    private void startServer() {
        running.set(true);
        executorService.submit(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                logger.info("Serveur de signalisation vidéo démarré sur le port {}", PORT);

                while (running.get() && !serverSocket.isClosed()) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        handleIncomingConnection(clientSocket);
                    } catch (IOException e) {
                        if (running.get()) {
                            logger.error("Erreur lors de l'acceptation de la connexion : {}", e.getMessage());
                        }
                    }
                }
            } catch (IOException e) {
                if (running.get()) {
                    logger.error("Erreur lors du démarrage du serveur de signalisation vidéo : {}", e.getMessage());
                }
            }
        });
    }

    private void handleIncomingConnection(Socket clientSocket) {
        executorService.submit(() -> {
            try (
                    BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                    PrintWriter writer = new PrintWriter(clientSocket.getOutputStream(), true)
            ) {
                String message = reader.readLine();
                if (message != null) {
                    processMessage(message, clientSocket.getInetAddress().getHostAddress());
                }
            } catch (IOException e) {
                logger.error("Erreur lors de la gestion de la connexion client : {}", e.getMessage());
            } finally {
                try {
                    clientSocket.close();
                } catch (IOException e) {
                    logger.error("Erreur lors de la fermeture du socket client : {}", e.getMessage());
                }
            }
        });
    }

    private void processMessage(String message, String senderIp) {
        logger.debug("Message reçu : {} de {}", message, senderIp);

        String[] parts = message.split(":");
        if (parts.length < 2 && !message.equals("CALL_RESPONSE:accepted") && !message.equals("CALL_RESPONSE:declined")) {
            logger.warn("Format de message invalide : {}", message);
            return;
        }

        String command = parts[0];
        String content = parts.length > 1 ? parts[1] : "";

        switch (command) {
            case "CALL_REQUEST":
                logger.info("Demande d'appel vidéo reçue de {} ({})", content, senderIp);
                if (callListener != null) {
                    callListener.onCallReceived(content, senderIp);
                }
                break;
            case "CALL_RESPONSE":
                boolean accepted = content.equals("accepted");
                logger.info("Réponse d'appel vidéo reçue de {} : {}", senderIp, accepted ? "accepté" : "refusé");
                if (callListener != null) {
                    if (accepted) {
                        callListener.onCallAccepted(senderIp);
                    } else {
                        callListener.onCallDeclined(senderIp);
                    }
                }
                break;
            case "CALL_END":
                logger.info("Fin d'appel vidéo reçue de {}", senderIp);
                if (callListener != null) {
                    callListener.onCallEnded(senderIp);
                }
                break;
            default:
                logger.warn("Commande inconnue : {}", command);
        }
    }

    public void sendCallRequest(String targetIp, String username) {
        sendMessage(targetIp, "CALL_REQUEST:" + username);
    }

    public void sendCallResponse(String targetIp, boolean accepted) {
        sendMessage(targetIp, "CALL_RESPONSE:" + (accepted ? "accepted" : "declined"));
    }

    public void sendCallEnded(String targetIp) {
        sendMessage(targetIp, "CALL_END:");
    }

    private void sendMessage(String targetIp, String message) {
        executorService.submit(() -> {
            Socket socket = null;
            int retries = 3;
            int delayMs = 1000;

            for (int i = 0; i < retries; i++) {
                try {
                    socket = new Socket();
                    socket.setSoTimeout(5000);
                    socket.connect(new InetSocketAddress(targetIp, PORT), 5000);
                    try (PrintWriter writer = new PrintWriter(socket.getOutputStream(), true)) {
                        writer.println(message);
                        writer.flush();
                        logger.debug("Message envoyé : {} à {}", message, targetIp);
                        return;
                    }
                } catch (IOException e) {
                    logger.warn("Tentative {} échouée pour envoyer le message à {}: {}", i + 1, targetIp, e.getMessage());
                    if (i < retries - 1) {
                        try {
                            Thread.sleep(delayMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            logger.error("Interruption lors de la réessai: {}", ie.getMessage());
                            break;
                        }
                    }
                } finally {
                    if (socket != null && !socket.isClosed()) {
                        try {
                            socket.close();
                        } catch (IOException e) {
                            logger.error("Erreur lors de la fermeture du socket: {}", e.getMessage());
                        }
                    }
                }
            }
            logger.error("Échec de l'envoi du message à {} après {} tentatives", targetIp, retries);
        });
    }

    public void listenForCallRequests(CallListener listener) {
        this.callListener = listener;
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            logger.info("Arrêt du VideoSignaler");

            if (serverSocket != null && !serverSocket.isClosed()) {
                try {
                    serverSocket.close();
                } catch (IOException e) {
                    logger.error("Erreur lors de la fermeture du socket serveur : {}", e.getMessage());
                }
            }

            if (executorService != null && !executorService.isShutdown()) {
                executorService.shutdown();
                try {
                    if (!executorService.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        executorService.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executorService.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    public interface CallListener {
        void onCallReceived(String fromUser, String ip);
        void onCallAccepted(String ip);
        void onCallDeclined(String ip);
        void onCallEnded(String ip);
    }
}