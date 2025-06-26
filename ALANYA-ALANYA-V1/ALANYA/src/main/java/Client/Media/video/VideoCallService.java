package Client.Media.video;

import Client.Media.video.VideoCall;
import javafx.application.Platform;
import javafx.embed.swing.SwingNode;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;

import Client.service.ConversationService;

public class VideoCallService {
    private static final Logger logger = LoggerFactory.getLogger(VideoCallService.class);
    private static final int VIDEO_PORT = 9876;
    private static final int AUDIO_PORT = 9866;

    private final String username;
    private final ConversationService conversationService;
    private final AtomicBoolean isCallActive = new AtomicBoolean(false);

    private JFrame swingFrame;
    private JLabel myVideoLabel;
    private JLabel remoteVideoLabel;
    private VideoCall videoCall;
    private Thread videoCallThread;
    private DatagramSocket audioSocket;
    private DatagramSocket videoSocket;
    private Stage videoCallStage;
    private boolean isCameraEnabled = true;
    private boolean isMicMuted = false;

    public VideoCallService(String username, ConversationService conversationService) {
        this.username = username;
        this.conversationService = conversationService;
    }

    public void startVideoCall(String contactName, String contactIp) {
        try {
            if (contactIp == null || contactIp.isEmpty()) {
                throw new RuntimeException("Adresse IP du contact non valide");
            }

            logger.info("Démarrage d'un appel vidéo avec {} ({})", contactName, contactIp);
            isCallActive.set(true);

            // Créer les sockets pour la communication
            audioSocket = new DatagramSocket(AUDIO_PORT);
            videoSocket = new DatagramSocket(VIDEO_PORT);
            InetAddress serverAddress = InetAddress.getByName(contactIp);

            // Initialiser l'interface Swing pour la capture vidéo
            initSwingComponents();

            // Créer et démarrer l'appel vidéo
            videoCall = new VideoCall(myVideoLabel, remoteVideoLabel, swingFrame,
                    AUDIO_PORT, VIDEO_PORT, audioSocket, videoSocket, serverAddress);
            videoCallThread = new Thread(videoCall);
            videoCallThread.start();

            // Afficher l'interface JavaFX moderne
            Platform.runLater(this::showVideoCallUI);

        } catch (Exception e) {
            logger.error("Erreur lors du démarrage de l'appel vidéo : {}", e.getMessage(), e);
            isCallActive.set(false);
            Platform.runLater(() -> {
                showNotification("Erreur d'appel", "Impossible de démarrer l'appel vidéo : " + e.getMessage());
            });
            throw new RuntimeException("Erreur lors du démarrage de l'appel vidéo", e);
        }
    }

    private void initSwingComponents() {
        myVideoLabel = new JLabel();
        myVideoLabel.setPreferredSize(new Dimension(200, 150));
        myVideoLabel.setMinimumSize(new Dimension(200, 150));
        myVideoLabel.setOpaque(true);

        remoteVideoLabel = new JLabel();
        remoteVideoLabel.setPreferredSize(new Dimension(760, 500));
        remoteVideoLabel.setMinimumSize(new Dimension(640, 480));
        remoteVideoLabel.setOpaque(true);

        swingFrame = new JFrame("Appel vidéo");
        swingFrame.setLayout(new BorderLayout());
        JPanel remotePanel = new JPanel(new BorderLayout());
        remotePanel.add(remoteVideoLabel, BorderLayout.CENTER);

        JPanel myPanel = new JPanel(new BorderLayout());
        myPanel.add(myVideoLabel, BorderLayout.CENTER);

        swingFrame.add(remotePanel, BorderLayout.CENTER);
        swingFrame.add(myPanel, BorderLayout.SOUTH);

        swingFrame.setSize(800, 600);
        swingFrame.setVisible(false);
    }

    private void showVideoCallUI() {
        videoCallStage = new Stage();
        videoCallStage.initStyle(StageStyle.DECORATED);

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #121212;");

        // Zone principale pour la vidéo distante
        SwingNode remoteVideoNode = new SwingNode();
        SwingUtilities.invokeLater(() -> {
            JPanel remotePanel = new JPanel(new BorderLayout());
            remotePanel.add(remoteVideoLabel, BorderLayout.CENTER);
            remotePanel.setPreferredSize(new Dimension(760, 500));
            remoteVideoNode.setContent(remotePanel);
        });

        // Conteneur pour la vidéo distante
        StackPane remoteContainer = new StackPane();
        remoteContainer.getChildren().add(remoteVideoNode);
        remoteContainer.setStyle("-fx-background-color: #121212;");
        root.setCenter(remoteContainer);

        // Zone pour ma vidéo
        SwingNode myVideoNode = new SwingNode();
        SwingUtilities.invokeLater(() -> {
            JPanel myPanel = new JPanel(new BorderLayout());
            myPanel.add(myVideoLabel, BorderLayout.CENTER);
            myPanel.setPreferredSize(new Dimension(200, 150));
            myVideoNode.setContent(myPanel);
        });

        // Conteneur pour ma vidéo
        StackPane myVideoContainer = new StackPane();
        myVideoContainer.getChildren().add(myVideoNode);
        myVideoContainer.setStyle("-fx-background-color: #232323; -fx-border-color: white; -fx-border-width: 1px; -fx-border-radius: 10px;");
        myVideoContainer.setMaxSize(200, 150);
        myVideoContainer.setMinSize(200, 150);
        myVideoContainer.setPrefSize(200, 150);

        // Positionnement de ma vidéo (en haut à droite)
        StackPane.setAlignment(myVideoContainer, Pos.TOP_RIGHT);
        StackPane.setMargin(myVideoContainer, new Insets(20));
        remoteContainer.getChildren().add(myVideoContainer);

        // Barre de contrôle en bas
        HBox controlsBox = createControlsBar();
        root.setBottom(controlsBox);

        Scene scene = new Scene(root, 800, 600);
        videoCallStage.setScene(scene);
        videoCallStage.setTitle("Appel vidéo");
        videoCallStage.setOnCloseRequest(event -> endCall());
        videoCallStage.show();

        // Forcer la mise à jour des labels après le rendu JavaFX
        SwingUtilities.invokeLater(() -> {
            remoteVideoLabel.setPreferredSize(new Dimension(760, 500));
            remoteVideoLabel.setSize(new Dimension(760, 500));
            remoteVideoLabel.revalidate();
            remoteVideoLabel.repaint();

            myVideoLabel.setPreferredSize(new Dimension(200, 150));
            myVideoLabel.setSize(new Dimension(200, 150));
            myVideoLabel.revalidate();
            myVideoLabel.repaint();
        });
    }

    private HBox createControlsBar() {
        HBox controlsBox = new HBox(20);
        controlsBox.setAlignment(Pos.CENTER);
        controlsBox.setPadding(new Insets(15));
        controlsBox.setStyle("-fx-background-color: #232323;");
        controlsBox.setPrefHeight(80);

        Button hangupButton = createRoundButton("📞", "-fx-background-color: #FF0000;");
        hangupButton.setOnAction(e -> endCall());

        controlsBox.getChildren().addAll( hangupButton);
        return controlsBox;
    }

    private Button createRoundButton(String text, String style) {
        Button button = new Button(text);
        button.setStyle(style + "-fx-text-fill: white; -fx-font-size: 18px; -fx-padding: 15px; -fx-background-radius: 50%;");
        button.setMinSize(60, 60);
        button.setMaxSize(60, 60);
        return button;
    }

    public void endCall() {
        if (isCallActive.compareAndSet(true, false)) {
            logger.info("Fin de l'appel vidéo");

            if (videoCall != null) {
                videoCall.stop();
                videoCall = null;
                logger.info("Instance de VideoCall arrêtée");
            }

            if (videoCallThread != null && videoCallThread.isAlive()) {
                videoCallThread.interrupt();
                videoCallThread = null;
                logger.info("Thread principal de VideoCall interrompu");
            }

            if (audioSocket != null && !audioSocket.isClosed()) {
                audioSocket.close();
                audioSocket = null;
                logger.info("Socket audio fermé");
            }
            if (videoSocket != null && !videoSocket.isClosed()) {
                videoSocket.close();
                videoSocket = null;
                logger.info("Socket vidéo fermé");
            }

            if (swingFrame != null) {
                swingFrame.dispose();
                swingFrame = null;
                logger.info("Fenêtre Swing fermée");
            }

            Platform.runLater(() -> {
                if (videoCallStage != null) {
                    videoCallStage.close();
                    videoCallStage = null;
                    logger.info("Fenêtre JavaFX fermée");
                }
            });
        }
    }

    public boolean isCallActive() {
        return isCallActive.get();
    }

    public void shutdown() {
        endCall();
    }

    private String resolveContactIp(String contactName) {
        try {
            // TODO: Récupérer l'IP via VideoSignaler ou une autre méthode dynamique
            logger.info("Résolution de l'IP pour {}", contactName);
            // Remplacer par l'IP réelle ou récupérer dynamiquement
            String contactIp = "10.2.61.28"; // À remplacer par l'IP de l'interlocuteur
            logger.info("IP résolue pour {} : {}", contactName, contactIp);
            return contactIp;
        } catch (Exception e) {
            logger.error("Impossible de résoudre l'adresse IP pour {} : {}", contactName, e.getMessage());
            return null;
        }
    }

    private void showNotification(String title, String message) {
        logger.info("Affichage de la notification : {} - {}", title, message);
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}