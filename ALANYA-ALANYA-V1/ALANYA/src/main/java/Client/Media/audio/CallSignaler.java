package Client.Media.audio;

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class handles signaling for audio calls between clients.
 * It listens for incoming call requests, responds to them, and notifies
 * the application through a listener interface.
 */
public class CallSignaler {
    private static final Logger logger = LoggerFactory.getLogger(CallSignaler.class);
    private final int port;
    private ServerSocket serverSocket;
    private ExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private CallListener callListener;

    /**
     * Constructor for CallSignaler
     *
     * @param port The port to listen on for call signaling
     */
    public CallSignaler(int port) {
        this.port = port;
        this.executorService = Executors.newCachedThreadPool();
        startServer();
    }

    /**
     * Starts the signaling server to listen for incoming calls
     */
    private void startServer() {
        running.set(true);
        executorService.submit(() -> {
            try {
                serverSocket = new ServerSocket(port);
                logger.info("Call signaling server started on port {}", port);

                while (running.get() && !serverSocket.isClosed()) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        handleIncomingConnection(clientSocket);
                    } catch (IOException e) {
                        if (running.get()) {
                            logger.error("Error accepting connection: {}", e.getMessage());
                        }
                    }
                }
            } catch (IOException e) {
                if (running.get()) {
                    logger.error("Error starting call signaling server: {}", e.getMessage());
                }
            }
        });
    }

    /**
     * Handles an incoming connection from another client
     *
     * @param clientSocket The socket of the connected client
     */
    private void handleIncomingConnection(Socket clientSocket) {
        executorService.submit(() -> {
            try (
                    BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                    PrintWriter writer = new PrintWriter(clientSocket.getOutputStream(), true)
            ) {
                String message = reader.readLine();
                if (message != null) {
                    processMessage(message, clientSocket.getInetAddress().getHostAddress(), writer);
                }
            } catch (IOException e) {
                logger.error("Error handling client connection: {}", e.getMessage());
            } finally {
                try {
                    clientSocket.close();
                } catch (IOException e) {
                    logger.error("Error closing client socket: {}", e.getMessage());
                }
            }
        });
    }

    /**
     * Processes incoming signaling messages
     *
     * @param message The received message
     * @param sourceIp The IP address of the sender
     * @param writer Writer to send responses
     */
    private void processMessage(String message, String sourceIp, PrintWriter writer) {
        logger.debug("Received message: {} from {}", message, sourceIp);

        if (message.startsWith("CALL_REQUEST:")) {
            String username = message.substring("CALL_REQUEST:".length());
            if (callListener != null) {
                callListener.onCallReceived(username, sourceIp);
            }
        } else if (message.equals("CALL_ACCEPTED")) {
            if (callListener != null) {
                callListener.onCallAccepted(sourceIp);
            }
        } else if (message.equals("CALL_DECLINED")) {
            if (callListener != null) {
                callListener.onCallDeclined(sourceIp);
            }
        } else if (message.equals("CALL_ENDED")) {
            if (callListener != null) {
                callListener.onCallEnded(sourceIp);
            }
        }
    }

    /**
     * Sends a call request to a specified IP address
     *
     * @param targetIp The IP address to send the call request to
     * @param username The username of the caller
     */
    public void sendCallRequest(String targetIp, String username) {
        sendMessage(targetIp, "CALL_REQUEST:" + username);
    }

    /**
     * Sends a response to a call request
     *
     * @param targetIp The IP address to send the response to
     * @param accepted Whether the call was accepted or declined
     */
    public void sendCallResponse(String targetIp, boolean accepted) {
        String message = accepted ? "CALL_ACCEPTED" : "CALL_DECLINED";
        sendMessage(targetIp, message);
    }

    /**
     * Sends a call ended notification
     *
     * @param targetIp The IP address to send the notification to
     */
    public void sendCallEnded(String targetIp) {
        sendMessage(targetIp, "CALL_ENDED");
    }


    private void sendMessage(String targetIp, String message) {
        executorService.submit(() -> {
            Socket socket = null;
            try {
                socket = new Socket();
                socket.setSoTimeout(5000); // Timeout pour les opérations de lecture/écriture
                socket.connect(new InetSocketAddress(targetIp, port), 5000); // Timeout pour la connexion
                try (PrintWriter writer = new PrintWriter(socket.getOutputStream(), true)) {
                    writer.println(message);
                    writer.flush(); // Assurer que le message est envoyé
                    logger.debug("Sent message: {} to {}", message, targetIp);
                }
            } catch (java.net.SocketTimeoutException e) {
                logger.error("Timeout lors de l'envoi du message à {} sur le port {}: {}", targetIp, port, e.getMessage());
            } catch (IOException e) {
                logger.error("Échec de l'envoi du message à {} sur le port {}: {}", targetIp, port, e.getMessage());
            } finally {
                if (socket != null && !socket.isClosed()) {
                    try {
                        socket.close();
                    } catch (IOException e) {
                        logger.error("Erreur lors de la fermeture du socket: {}", e.getMessage());
                    }
                }
            }
        });
    }


    public void listenForCallRequests(CallListener listener) {
        this.callListener = listener;
    }


    public void stop() {
        running.set(false);

        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                logger.error("Error closing server socket: {}", e.getMessage());
            }
        }

        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }

        logger.info("Call signaling server stopped");
    }


    public interface CallListener {

        void onCallReceived(String fromUser, String ip);


        void onCallAccepted(String ip);


        void onCallDeclined(String ip);


        void onCallEnded(String ip);
    }
}