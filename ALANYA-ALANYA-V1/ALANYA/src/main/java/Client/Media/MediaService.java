package Client.Media;

import Client.Media.audio.AudioReceiver;
import Client.Media.audio.AudioSender;
import Client.Media.audio.AudioSetup;
import Client.Media.audio.CallSignaler;
import Client.Media.video.VideoCallService;
import Client.Media.video.VideoSignaler;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;
import javafx.scene.layout.Priority;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.BlurType;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;

import java.io.FileInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import Client.service.ConversationService;

public class MediaService {
    private static final Logger logger = LoggerFactory.getLogger(MediaService.class);
    private final ConversationService conversationService;
    private final String username;
    private final Map<String, String> contacts = new ConcurrentHashMap<>(); // username -> ip
    private final AtomicBoolean isCallActive = new AtomicBoolean(false);
    private static final int AUDIO_PORT = 5000;
    private static final int VIDEO_SIGNALING_PORT = 6005;
    private static final int CALL_SIGNALING_PORT = 6002;

    private AudioSetup audioSetup;
    private AudioSender audioSender;
    private VideoCallService videoCallService;
    private AudioReceiver audioReceiver;
    private CallSignaler callSignaler;
    private VideoSignaler videoSignaler;
    private ScheduledExecutorService executorService;
    private Timeline callTimer;
    private int secondsElapsed = 0;
    private Label callDurationLabel;
    private Stage callStage;
    private boolean isMicMuted = false;
    private boolean isSpeakerMuted = false;
    private boolean isVideoEnabled = true;
    private boolean isFrontCamera = true;

    private static final String ICON_PATH = "/resources/icons/";
    private static final String MIC_ON_ICON = ICON_PATH + "mic_on.png";
    private static final String MIC_OFF_ICON = ICON_PATH + "mic_off.png";
    private static final String SPEAKER_ON_ICON = ICON_PATH + "speaker_on.png";
    private static final String SPEAKER_OFF_ICON = ICON_PATH + "speaker_off.png";
    private static final String VIDEO_ON_ICON = ICON_PATH + "video_on.png";
    private static final String VIDEO_OFF_ICON = ICON_PATH + "video_off.png";
    private static final String SWITCH_CAMERA_ICON = ICON_PATH + "switch_camera.png";
    private static final String END_CALL_ICON = ICON_PATH + "end_call.png";
    private static final String ACCEPT_CALL_ICON = ICON_PATH + "accept_call.png";
    private static final String REJECT_CALL_ICON = ICON_PATH + "reject_call.png";

    private BorderPane mainVideoPane;
    private StackPane localVideoPane;
    private StackPane remoteVideoPane;

    public MediaService(String username, ConversationService conversationService) {
        this.username = username;
        this.conversationService = conversationService;
        this.audioSetup = new AudioSetup();
        this.videoCallService = new VideoCallService(username, conversationService);
        this.executorService = Executors.newScheduledThreadPool(2);
        initializeSignaling();
        refreshContacts();
    }

    private void initializeSignaling() {
        try {
            callSignaler = new CallSignaler(CALL_SIGNALING_PORT);
            callSignaler.listenForCallRequests(new CallSignaler.CallListener() {
                @Override
                public void onCallReceived(String fromUser, String ip) {
                    logger.info("Réception d'un appel vocal de {} ({})", fromUser, ip);
                    if (isCallActive.get()) {
                        try {
                            callSignaler.sendCallResponse(ip, false);
                        } catch (Exception e) {
                            logger.error("Erreur lors de l'envoi de la réponse d'appel : {}", e.getMessage());
                        }
                        return;
                    }

                    Platform.runLater(() -> showIncomingCallUI(fromUser, ip, false));
                }

                @Override
                public void onCallAccepted(String ip) {
                    logger.info("Appel vocal accepté par {}", ip);
                    Platform.runLater(() -> {
                        String contactUsername = findContactByIp(ip);
                        if (contactUsername != null) {
                            startCall(contactUsername, ip, false);
                        } else {
                            startCall("Contact inconnu", ip, false);
                        }
                    });
                }

                @Override
                public void onCallDeclined(String ip) {
                    logger.info("Appel vocal refusé par {}", ip);
                    Platform.runLater(() -> {
                        showNotification("Appel refusé", "Votre appel vocal a été refusé");
                        if (callStage != null) {
                            callStage.close();
                        }
                    });
                }

                @Override
                public void onCallEnded(String ip) {
                    logger.info("Appel vocal terminé par {}", ip);
                    Platform.runLater(() -> {
                        if (isCallActive.get()) {
                            endCall();
                            showNotification("Appel terminé", "L'appel vocal a été terminé par votre correspondant");
                        }
                    });
                }
            });

            videoSignaler = new VideoSignaler();
            videoSignaler.listenForCallRequests(new VideoSignaler.CallListener() {
                @Override
                public void onCallReceived(String fromUser, String ip) {
                    logger.info("Réception d'un appel vidéo de {} ({})", fromUser, ip);
                    if (isCallActive.get()) {
                        try {
                            videoSignaler.sendCallResponse(ip, false);
                        } catch (Exception e) {
                            logger.error("Erreur lors de l'envoi de la réponse d'appel vidéo : {}", e.getMessage());
                        }
                        return;
                    }

                    Platform.runLater(() -> showIncomingCallUI(fromUser, ip, true));
                }

                @Override
                public void onCallAccepted(String ip) {
                    logger.info("Appel vidéo accepté par {}", ip);
                    Platform.runLater(() -> {
                        String contactUsername = findContactByIp(ip);
                        if (contactUsername != null) {
                            startCall(contactUsername, ip, true);
                        } else {
                            startCall("Contact inconnu", ip, true);
                        }
                    });
                }

                @Override
                public void onCallDeclined(String ip) {
                    logger.info("Appel vidéo refusé par {}", ip);
                    Platform.runLater(() -> {
                        showNotification("Appel refusé", "Votre appel vidéo a été refusé");
                        if (callStage != null) {
                            callStage.close();
                        }
                    });
                }

                @Override
                public void onCallEnded(String ip) {
                    logger.info("Appel vidéo terminé par {}", ip);
                    Platform.runLater(() -> {
                        if (isCallActive.get()) {
                            endCall();
                            showNotification("Appel terminé", "L'appel vidéo a été terminé par votre correspondant");
                        }
                    });
                }
            });
        } catch (Exception e) {
            logger.error("Erreur lors de l'initialisation des signaleurs : {}", e.getMessage());
            showNotification("Erreur d'initialisation", "Impossible d'initialiser les composants d'appel");
        }
    }

    private CompletableFuture<Boolean> isPortReachableAsync(String ip, int port) {
        return CompletableFuture.supplyAsync(() -> {
            logger.info("Vérification de l'accessibilité du port {} sur {}", port, ip);
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(ip, port), 2000);
                logger.info("Port {} accessible sur {}", port, ip);
                return true;
            } catch (Exception e) {
                logger.error("Port {} non accessible sur {} : {}", port, ip, e.getMessage());
                return false;
            }
        }, executorService);
    }

    public void startVideoCall(String contact) {
        logger.info("Démarrage d'un appel vidéo avec {}", contact);
        if (isCallActive.get()) {
            logger.warn("Un appel est déjà en cours. Veuillez terminer l'appel actuel.");
            showNotification("Appel en cours", "Un appel est déjà en cours. Veuillez terminer l'appel actuel.");
            return;
        }

        if (!conversationService.getAllContacts().contains(contact)) {
            logger.warn("Contact {} non trouvé dans la liste des contacts", contact);
            showNotification("Contact inconnu", "Contact non trouvé dans la liste");
            return;
        }

        Boolean isOnline = conversationService.getUserStatusMap().getOrDefault(contact, false);
        if (!isOnline) {
            logger.warn("Contact {} est hors ligne", contact);
            showNotification("Contact hors ligne", "Le contact " + contact + " est actuellement hors ligne");
            return;
        }

        String contactIp = resolveContactIp(contact);
        if (contactIp == null) {
            logger.warn("Adresse IP pour le contact {} non disponible", contact);
            showNotification("Information manquante", "Impossible de déterminer l'adresse du contact");
            return;
        }

        try {
            Platform.runLater(() -> showOutgoingCallUI(contact, contactIp, true));

            isPortReachableAsync(contactIp, VIDEO_SIGNALING_PORT)
                    .thenAcceptAsync(reachable -> {
                        if (!reachable) {
                            logger.warn("Le port de signalisation vidéo {} n'est pas accessible sur {}", VIDEO_SIGNALING_PORT, contactIp);
                            Platform.runLater(() -> {
                                showNotification("Connexion impossible", "Impossible de se connecter au contact");
                                if (callStage != null) {
                                    callStage.close();
                                }
                            });
                            return;
                        }

                        try {
                            logger.info("Envoi de la requête d'appel vidéo à {} ({})", contact, contactIp);
                            videoSignaler.sendCallRequest(contactIp, username);
                        } catch (Exception e) {
                            logger.error("Erreur lors de l'envoi de la requête d'appel vidéo : {}", e.getMessage());
                            Platform.runLater(() -> {
                                showNotification("Erreur d'appel", "Impossible d'initialiser l'appel vidéo");
                                if (callStage != null) {
                                    callStage.close();
                                }
                            });
                        }
                    }, executorService);
        } catch (Exception e) {
            logger.error("Erreur lors du démarrage de l'appel vidéo : {}", e.getMessage());
            showNotification("Erreur d'appel", "Impossible d'initialiser l'appel vidéo : " + e.getMessage());
        }
    }

    public void startVoiceCall(String contact) {
        logger.info("Tentative de démarrage d'un appel vocal avec {}", contact);
        if (isCallActive.get()) {
            logger.warn("Un appel est déjà en cours");
            showNotification("Appel en cours", "Vous êtes déjà en communication");
            return;
        }

        if (!conversationService.getAllContacts().contains(contact)) {
            logger.warn("Contact {} non trouvé dans la liste des contacts", contact);
            showNotification("Contact inconnu", "Contact non trouvé dans la liste");
            return;
        }

        Boolean isOnline = conversationService.getUserStatusMap().getOrDefault(contact, false);
        if (!isOnline) {
            logger.warn("Contact {} est hors ligne", contact);
            showNotification("Contact hors ligne", "Le contact " + contact + " est actuellement hors ligne");
            return;
        }

        String contactIp = resolveContactIp(contact);
        if (contactIp == null) {
            logger.warn("Adresse IP pour le contact {} non disponible", contact);
            showNotification("Information manquante", "Impossible de déterminer l'adresse du contact");
            return;
        }

        try {
            Platform.runLater(() -> showOutgoingCallUI(contact, contactIp, false));

            isPortReachableAsync(contactIp, CALL_SIGNALING_PORT)
                    .thenAcceptAsync(reachable -> {
                        if (!reachable) {
                            logger.warn("Le port de signalisation vocal {} n'est pas accessible sur {}", CALL_SIGNALING_PORT, contactIp);
                            Platform.runLater(() -> {
                                showNotification("Connexion impossible", "Impossible de se connecter au contact");
                                if (callStage != null) {
                                    callStage.close();
                                }
                            });
                            return;
                        }

                        try {
                            logger.info("Envoi de la requête d'appel vocal à {} ({})", contact, contactIp);
                            callSignaler.sendCallRequest(contactIp, username);
                        } catch (Exception e) {
                            logger.error("Erreur lors de l'envoi de la requête d'appel vocal : {}", e.getMessage());
                            Platform.runLater(() -> {
                                showNotification("Erreur d'appel", "Impossible d'initialiser l'appel vocal");
                                if (callStage != null) {
                                    callStage.close();
                                }
                            });
                        }
                    }, executorService);
        } catch (Exception e) {
            logger.error("Erreur lors du démarrage de l'appel vocal : {}", e.getMessage());
            showNotification("Erreur d'appel", "Impossible d'initialiser l'appel vocal : " + e.getMessage());
        }
    }

    private void showIncomingCallUI(String fromUser, String ip, boolean isVideo) {
        logger.info("Affichage de l'UI pour appel entrant de {} (vidéo: {})", fromUser, isVideo);
        Platform.runLater(() -> {
            try {
                if (callStage != null) {
                    callStage.close();
                }

                callStage = new Stage();
                callStage.initStyle(StageStyle.UNDECORATED);

                BorderPane root = new BorderPane();
                root.setStyle("-fx-background-color: linear-gradient(to bottom, #075E54, #128C7E); -fx-background-radius: 10;");
                root.setPadding(new Insets(20));

                DropShadow dropShadow = new DropShadow();
                dropShadow.setBlurType(BlurType.GAUSSIAN);
                dropShadow.setColor(Color.rgb(0, 0, 0, 0.4));
                dropShadow.setHeight(10);
                dropShadow.setWidth(10);
                dropShadow.setRadius(5);
                dropShadow.setOffsetX(2);
                dropShadow.setOffsetY(2);
                root.setEffect(dropShadow);

                Circle avatarCircle = new Circle(50);
                avatarCircle.setFill(Color.WHITE);
                avatarCircle.setStroke(Color.WHITE);
                avatarCircle.setStrokeWidth(2);

                Label avatarLabel = new Label(fromUser.substring(0, 1).toUpperCase());
                avatarLabel.setFont(Font.font("System", FontWeight.BOLD, 30));
                avatarLabel.setTextFill(Color.web("#075E54"));

                StackPane avatarPane = new StackPane(avatarCircle, avatarLabel);

                VBox infoBox = new VBox(10);
                infoBox.setAlignment(Pos.CENTER);

                Label nameLabel = new Label(fromUser);
                nameLabel.setFont(Font.font("System", FontWeight.BOLD, 22));
                nameLabel.setTextFill(Color.WHITE);

                Label callTypeLabel = new Label(isVideo ? "Appel vidéo entrant" : "Appel vocal entrant");
                callTypeLabel.setFont(Font.font("System", 16));
                callTypeLabel.setTextFill(Color.rgb(255, 255, 255, 0.8));

                infoBox.getChildren().addAll(avatarPane, nameLabel, callTypeLabel);

                HBox actionButtons = new HBox(30);
                actionButtons.setAlignment(Pos.CENTER);

                VBox declineBox = new VBox(5);
                declineBox.setAlignment(Pos.CENTER);

                Circle declineCircle = new Circle(30);
                declineCircle.setFill(Color.RED);

                ImageView declineIcon;
                try {
                    Image declineImage = new Image(new FileInputStream(getClass().getResource(REJECT_CALL_ICON).getPath()));
                    declineIcon = new ImageView(declineImage);
                } catch (Exception e) {
                    logger.error("Impossible de charger l'icône de refus : {}", e.getMessage());
                    declineIcon = new ImageView();
                }
                declineIcon.setFitWidth(20);
                declineIcon.setFitHeight(20);

                StackPane declineButton = new StackPane(declineCircle, declineIcon);

                Label declineLabel = new Label("Refuser");
                declineLabel.setTextFill(Color.WHITE);

                declineBox.getChildren().addAll(declineButton, declineLabel);
                declineBox.setCursor(javafx.scene.Cursor.HAND);

                declineBox.setOnMouseClicked(e -> {
                    logger.info("Refus de l'appel de {} (vidéo: {})", fromUser, isVideo);
                    try {
                        if (isVideo) {
                            executorService.submit(() -> videoSignaler.sendCallResponse(ip, false));
                        } else {
                            executorService.submit(() -> callSignaler.sendCallResponse(ip, false));
                        }
                    } catch (Exception ex) {
                        logger.error("Erreur lors du refus de l'appel : {}", ex.getMessage());
                    }
                    callStage.close();
                });

                VBox acceptBox = new VBox(5);
                acceptBox.setAlignment(Pos.CENTER);

                Circle acceptCircle = new Circle(30);
                acceptCircle.setFill(Color.GREEN);

                ImageView acceptIcon;
                try {
                    Image acceptImage = new Image(new FileInputStream(getClass().getResource(ACCEPT_CALL_ICON).getPath()));
                    acceptIcon = new ImageView(acceptImage);
                } catch (Exception e) {
                    logger.error("Impossible de charger l'icône d'acceptation : {}", e.getMessage());
                    acceptIcon = new ImageView();
                }
                acceptIcon.setFitWidth(20);
                acceptIcon.setFitHeight(20);

                StackPane acceptButton = new StackPane(acceptCircle, acceptIcon);

                Label acceptLabel = new Label("Accepter");
                acceptLabel.setTextFill(Color.WHITE);

                acceptBox.getChildren().addAll(acceptButton, acceptLabel);
                acceptBox.setCursor(javafx.scene.Cursor.HAND);

                acceptBox.setOnMouseClicked(e -> {
                    logger.info("Acceptation de l'appel de {} (vidéo: {})", fromUser, isVideo);
                    try {
                        if (isVideo) {
                            executorService.submit(() -> videoSignaler.sendCallResponse(ip, true));
                            startCall(fromUser, ip, true);
                        } else {
                            executorService.submit(() -> callSignaler.sendCallResponse(ip, true));
                            startCall(fromUser, ip, false);
                        }
                    } catch (Exception ex) {
                        logger.error("Erreur lors de l'acceptation de l'appel : {}", ex.getMessage());
                        showNotification("Erreur d'appel", "Impossible d'accepter l'appel : " + ex.getMessage());
                    }
                    callStage.close();
                });

                actionButtons.getChildren().addAll(declineBox, acceptBox);

                VBox centerContent = new VBox(30);
                centerContent.setAlignment(Pos.CENTER);
                centerContent.getChildren().addAll(infoBox, actionButtons);

                root.setCenter(centerContent);

                Scene scene = new Scene(root, 400, 500);
                scene.setFill(Color.TRANSPARENT);
                callStage.setScene(scene);
                callStage.setTitle(isVideo ? "Appel vidéo entrant" : "Appel vocal entrant");
                callStage.show();
            } catch (Exception e) {
                logger.error("Erreur lors de l'affichage de l'UI d'appel entrant : {}", e.getMessage());
                showNotification("Erreur d'interface", "Impossible d'afficher l'interface d'appel entrant");
            }
        });
    }

    private void showOutgoingCallUI(String contact, String contactIp, boolean isVideo) {
        logger.info("Affichage de l'UI pour appel sortant vers {} (vidéo: {})", contact, isVideo);
        Platform.runLater(() -> {
            try {
                if (callStage != null) {
                    callStage.close();
                }

                callStage = new Stage();
                callStage.initStyle(StageStyle.UNDECORATED);

                BorderPane root = new BorderPane();
                root.setStyle("-fx-background-color: linear-gradient(to bottom, #075E54, #128C7E); -fx-background-radius: 10;");
                root.setPadding(new Insets(20));

                DropShadow dropShadow = new DropShadow();
                dropShadow.setBlurType(BlurType.GAUSSIAN);
                dropShadow.setColor(Color.rgb(0, 0, 0, 0.4));
                dropShadow.setHeight(10);
                dropShadow.setWidth(10);
                dropShadow.setRadius(5);
                dropShadow.setOffsetX(2);
                dropShadow.setOffsetY(2);
                root.setEffect(dropShadow);

                Circle avatarCircle = new Circle(50);
                avatarCircle.setFill(Color.WHITE);
                avatarCircle.setStroke(Color.WHITE);
                avatarCircle.setStrokeWidth(2);

                Label avatarLabel = new Label(contact.substring(0, 1).toUpperCase());
                avatarLabel.setFont(Font.font("System", FontWeight.BOLD, 30));
                avatarLabel.setTextFill(Color.web("#075E54"));

                StackPane avatarPane = new StackPane(avatarCircle, avatarLabel);

                VBox infoBox = new VBox(10);
                infoBox.setAlignment(Pos.CENTER);

                Label nameLabel = new Label(contact);
                nameLabel.setFont(Font.font("System", FontWeight.BOLD, 22));
                nameLabel.setTextFill(Color.WHITE);

                Label callTypeLabel = new Label(isVideo ? "Appel vidéo sortant" : "Appel vocal sortant");
                callTypeLabel.setFont(Font.font("System", 16));
                callTypeLabel.setTextFill(Color.rgb(255, 255, 255, 0.8));

                Label statusLabel = new Label("En attente de réponse...");
                statusLabel.setFont(Font.font("System", 14));
                statusLabel.setTextFill(Color.rgb(255, 255, 255, 0.6));

                infoBox.getChildren().addAll(avatarPane, nameLabel, callTypeLabel, statusLabel);

                VBox hangupBox = new VBox(5);
                hangupBox.setAlignment(Pos.CENTER);

                Circle hangupCircle = new Circle(30);
                hangupCircle.setFill(Color.RED);

                ImageView hangupIcon;
                try {
                    Image hangupImage = new Image(new FileInputStream(getClass().getResource(END_CALL_ICON).getPath()));
                    hangupIcon = new ImageView(hangupImage);
                } catch (Exception e) {
                    logger.error("Impossible de charger l'icône de fin d'appel : {}", e.getMessage());
                    hangupIcon = new ImageView();
                }
                hangupIcon.setFitWidth(20);
                hangupIcon.setFitHeight(20);

                StackPane hangupButton = new StackPane(hangupCircle, hangupIcon);

                Label hangupLabel = new Label("Annuler");
                hangupLabel.setTextFill(Color.WHITE);

                hangupBox.getChildren().addAll(hangupButton, hangupLabel);
                hangupBox.setCursor(javafx.scene.Cursor.HAND);

                hangupBox.setOnMouseClicked(e -> {
                    logger.info("Annulation de l'appel vers {} (vidéo: {})", contact, isVideo);
                    try {
                        if (isVideo) {
                            executorService.submit(() -> videoSignaler.sendCallEnded(contactIp));
                        } else {
                            executorService.submit(() -> callSignaler.sendCallEnded(contactIp));
                        }
                    } catch (Exception ex) {
                        logger.error("Erreur lors de l'annulation de l'appel : {}", ex.getMessage());
                    }
                    callStage.close();
                });

                VBox centerContent = new VBox(50);
                centerContent.setAlignment(Pos.CENTER);
                centerContent.getChildren().addAll(infoBox, hangupBox);

                root.setCenter(centerContent);

                Scene scene = new Scene(root, 400, 500);
                scene.setFill(Color.TRANSPARENT);
                callStage.setScene(scene);
                callStage.setTitle(isVideo ? "Appel vidéo sortant" : "Appel vocal sortant");
                callStage.show();
            } catch (Exception e) {
                logger.error("Erreur lors de l'affichage de l'UI d'appel sortant : {}", e.getMessage());
                showNotification("Erreur d'interface", "Impossible d'afficher l'interface d'appel sortant");
            }
        });
    }

    private void startCall(String contactName, String contactIp, boolean isVideo) {
        logger.info("Début de l'appel avec {} (vidéo: {}, IP: {})", contactName, isVideo, contactIp);
        isCallActive.set(true);

        try {
            if (isVideo) {
                logger.info("Initialisation de l'appel vidéo avec {} ({})", contactName, contactIp);
                executorService.submit(() -> {
                    try {
                        videoCallService.startVideoCall(contactName, contactIp);
                    } catch (Exception e) {
                        logger.error("Erreur lors du démarrage de l'appel vidéo : {}", e.getMessage());
                        Platform.runLater(() -> {
                            endCall();
                            showNotification("Erreur d'appel", "Impossible d'établir la communication vidéo : " + e.getMessage());
                        });
                    }
                });
            } else {
                logger.info("Initialisation du récepteur audio sur port {}", AUDIO_PORT);
                audioReceiver = new AudioReceiver(AUDIO_PORT, audioSetup);
                executorService.submit(() -> {
                    try {
                        audioReceiver.start();
                        logger.info("Récepteur audio démarré avec succès");
                    } catch (Exception e) {
                        logger.error("Erreur lors du démarrage du récepteur audio : {}", e.getMessage());
                        Platform.runLater(() -> {
                            endCall();
                            showNotification("Erreur d'appel", "Impossible d'établir la communication audio : " + e.getMessage());
                        });
                    }
                });

                logger.info("Initialisation de l'émetteur audio vers {}:{}", contactIp, AUDIO_PORT);
                audioSender = new AudioSender(contactIp, AUDIO_PORT, audioSetup);
                executorService.submit(() -> {
                    try {
                        audioSender.start();
                        logger.info("Émetteur audio démarré avec succès");
                    } catch (Exception e) {
                        logger.error("Erreur lors du démarrage de l'émetteur audio : {}", e.getMessage());
                        Platform.runLater(() -> {
                            endCall();
                            showNotification("Erreur d'appel", "Impossible d'établir la communication audio : " + e.getMessage());
                        });
                    }
                });
            }

            Platform.runLater(() -> showCallUI(contactName, contactIp, isVideo));
        } catch (Exception e) {
            logger.error("Erreur générale lors du démarrage de l'appel : {}", e.getMessage());
            endCall();
            showNotification("Erreur d'appel", "Impossible d'établir la communication : " + e.getMessage());
        }
    }

    private void showCallUI(String contactName, String contactIp, boolean isVideo) {
        logger.info("Affichage de l'UI pour appel en cours avec {} (vidéo: {})", contactName, isVideo);
        Platform.runLater(() -> {
            if (callStage != null) {
                callStage.close();
            }

            callStage = new Stage();
            callStage.initStyle(StageStyle.UNDECORATED);
            VBox root = new VBox(10);
            root.setAlignment(Pos.CENTER);
            root.setPadding(new Insets(15));
            root.setStyle("-fx-background-color: #FFFFFF; -fx-border-color: #009688; -fx-border-width: 2; -fx-background-radius: 10;");

            Circle avatar = new Circle(40, Color.web("#009688"));
            Label avatarText = new Label(contactName.substring(0, 1).toUpperCase());
            avatarText.setFont(Font.font("Arial", FontWeight.BOLD, 24));
            avatarText.setTextFill(Color.WHITE);
            StackPane avatarPane = new StackPane(avatar, avatarText);

            Label callLabel = new Label(isVideo ? "Appel vidéo en cours avec " + contactName : "Appel vocal en cours avec " + contactName);
            callLabel.setFont(Font.font("Arial", FontWeight.BOLD, 16));
            callLabel.setTextFill(Color.web("#008069"));

            HBox buttonBox = new HBox(15);
            buttonBox.setAlignment(Pos.CENTER);

            Button micButton = new Button(isMicMuted ? "Activer le micro" : "Couper le micro");
            micButton.setStyle("-fx-background-color: #009688; -fx-text-fill: white; -fx-font-size: 12; -fx-padding: 8 16; -fx-background-radius: 5; -fx-cursor: hand;");
            micButton.setOnMouseEntered(e -> micButton.setStyle("-fx-background-color: #008069; -fx-text-fill: white; -fx-font-size: 12; -fx-padding: 8 16; -fx-background-radius: 5; -fx-cursor: hand;"));
            micButton.setOnMouseExited(e -> micButton.setStyle("-fx-background-color: #009688; -fx-text-fill: white; -fx-font-size: 12; -fx-padding: 8 16; -fx-background-radius: 5; -fx-cursor: hand;"));
            micButton.setOnAction(e -> {
                logger.info("Changement de l'état du micro : {}", isMicMuted ? "activé" : "coupé");
                isMicMuted = !isMicMuted;
                if (audioSetup != null) {
                    audioSetup.setMicMuted(isMicMuted);
                }
                micButton.setText(isMicMuted ? "Activer le micro" : "Couper le micro");
            });

            Button hangupButton = new Button("Raccrocher");
            hangupButton.setStyle("-fx-background-color: #FF4D4D; -fx-text-fill: white; -fx-font-size: 12; -fx-padding: 8 16; -fx-background-radius: 5; -fx-cursor: hand;");
            hangupButton.setOnMouseEntered(e -> hangupButton.setStyle("-fx-background-color: #CC0000; -fx-text-fill: white; -fx-font-size: 12; -fx-padding: 8 16; -fx-background-radius: 5; -fx-cursor: hand;"));
            hangupButton.setOnMouseExited(e -> hangupButton.setStyle("-fx-background-color: #FF4D4D; -fx-text-fill: white; -fx-font-size: 12; -fx-padding: 8 16; -fx-background-radius: 5; -fx-cursor: hand;"));
            hangupButton.setOnAction(e -> {
                logger.info("Raccrochage de l'appel avec {} (vidéo: {})", contactName, isVideo);
                if (isVideo) {
                    executorService.submit(() -> videoSignaler.sendCallEnded(contactIp));
                    videoCallService.endCall();
                } else {
                    executorService.submit(() -> callSignaler.sendCallEnded(contactIp));
                }
                endCall();
                callStage.close();
            });

            buttonBox.getChildren().addAll(micButton, hangupButton);

            if (!isVideo) {
                callDurationLabel = new Label("Durée : 00:00");
                callDurationLabel.setFont(Font.font("Arial", 14));
                callDurationLabel.setTextFill(Color.web("#009688"));
                root.getChildren().addAll(avatarPane, callLabel, callDurationLabel, buttonBox);
                startCallTimer();
            } else {
                root.getChildren().addAll(avatarPane, callLabel, buttonBox);
            }

            Scene scene = new Scene(root, 350, isVideo ? 250 : 300);
            callStage.setScene(scene);
            callStage.setTitle(isVideo ? "Appel vidéo en cours" : "Appel vocal en cours");
            callStage.setOnCloseRequest(e -> {
                logger.info("Fermeture de la fenêtre d'appel avec {} (vidéo: {})", contactName, isVideo);
                if (isVideo) {
                    executorService.submit(() -> videoSignaler.sendCallEnded(contactIp));
                    videoCallService.endCall();
                } else {
                    executorService.submit(() -> callSignaler.sendCallEnded(contactIp));
                }
                endCall();
            });

            callStage.show();
        });
    }

    private void startCallTimer() {
        logger.info("Démarrage du minuteur d'appel");
        secondsElapsed = 0;
        callDurationLabel.setText("Durée : 00:00");

        callTimer = new Timeline(new KeyFrame(Duration.seconds(1), event -> {
            secondsElapsed++;
            int minutes = secondsElapsed / 60;
            int seconds = secondsElapsed % 60;
            callDurationLabel.setText(String.format("Durée : %02d:%02d", minutes, seconds));
        }));
        callTimer.setCycleCount(Timeline.INDEFINITE);
        callTimer.play();
    }

    private void stopCallTimer() {
        logger.info("Arrêt du minuteur d'appel");
        if (callTimer != null) {
            callTimer.stop();
            callTimer = null;
        }
        secondsElapsed = 0;
    }

    private String findContactByIp(String ip) {
        for (Map.Entry<String, String> entry : contacts.entrySet()) {
            if (entry.getValue().equals(ip)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void refreshContacts() {
        executorService.scheduleAtFixedRate(() -> {
            try {
                Set<String> contactUsernames = conversationService.getAllContacts();
                logger.debug("Mise à jour des contacts : {}", contactUsernames);

                for (String contactUsername : contactUsernames) {
                    boolean isOnline = conversationService.getUserStatusMap().getOrDefault(contactUsername, false);
                    if (isOnline && !contacts.containsKey(contactUsername)) {
                        String contactIp = resolveContactIp(contactUsername);
                        if (contactIp != null) {
                            contacts.put(contactUsername, contactIp);
                            logger.info("Contact {} résolu à l'IP {}", contactUsername, contactIp);
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("Erreur lors de la mise à jour des contacts : {}", e.getMessage());
            }
        }, 0, 30, TimeUnit.SECONDS);
    }

    private String resolveContactIp(String username) {
        try {
            logger.info("Utilisation de l'adresse IP spécifique: 172.20.10.5");
            return "10.2.61.28"; // Remplacer par l'IP réelle ou une résolution dynamique
        } catch (Exception e) {
            logger.error("Impossible de résoudre l'adresse IP pour {} : {}", username, e.getMessage());
            return null;
        }
    }

    private boolean isPortReachable(String ip, int port) {
        logger.info("Vérification de l'accessibilité du port {} sur {}", port, ip);
        try (Socket socket = new Socket(ip, port)) {
            logger.info("Port {} accessible sur {}", port, ip);
            return true;
        } catch (Exception e) {
            logger.error("Port {} non accessible sur {} : {}", port, ip, e.getMessage());
            return false;
        }
    }

    public void endCall() {
        logger.info("Tentative de fin de l'appel");
        if (isCallActive.compareAndSet(true, false)) {
            logger.info("Fin de l'appel confirmée");

            if (audioSender != null) {
                logger.info("Arrêt de l'émetteur audio");
                audioSender.stopSending();
                audioSender = null;
            }

            if (audioReceiver != null) {
                logger.info("Arrêt du récepteur audio");
                audioReceiver.stopReceiving();
                audioReceiver = null;
            }

            if (audioSetup != null) {
                logger.info("Fermeture du microphone et des haut-parleurs");
                audioSetup.closeMicrophone();
                audioSetup.closeSpeakers();
            }

            if (videoCallService != null && videoCallService.isCallActive()) {
                logger.info("Arrêt de l'appel vidéo");
                videoCallService.endCall();
            }

            stopCallTimer();

            Platform.runLater(() -> {
                if (callStage != null) {
                    logger.info("Fermeture de la fenêtre d'appel");
                    callStage.close();
                    callStage = null;
                }
            });
        } else {
            logger.info("Aucun appel actif à terminer");
        }
    }

    public boolean isCallActive() {
        return isCallActive.get();
    }

    public void shutdown() {
        logger.info("Arrêt du service de communication");

        if (isCallActive.get()) {
            endCall();
        }

        if (videoCallService != null) {
            logger.info("Arrêt du service d'appel vidéo");
            videoCallService.shutdown();
            videoCallService = null;
        }

        if (callSignaler != null) {
            logger.info("Arrêt du signaliseur d'appel vocal");
            callSignaler.stop();
            callSignaler = null;
        }

        if (videoSignaler != null) {
            logger.info("Arrêt du signaliseur d'appel vidéo");
            videoSignaler.stop();
            videoSignaler = null;
        }

        if (executorService != null && !executorService.isShutdown()) {
            logger.info("Arrêt de l'executor service");
            try {
                executorService.shutdown();
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
                logger.error("Interruption lors de l'arrêt de l'executor : {}", e.getMessage());
            }
        }

        logger.info("Service de communication arrêté");
    }

    private void showNotification(String title, String message) {
        logger.info("Affichage de la notification : {} - {}", title, message);
        Platform.runLater(() -> {
            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }
}