package Client.service;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

import Client.Media.MediaService;
import Client.Media.audio.AudioRecorder;
import Client.emoji.Emogi;
import Client.emoji.Model_Emoji;
import Serveur.Config.Message;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public class ConversationService {

    private VBox conversationsListVBox;
    private VBox chatMessagesVBox;
    private TextField messageField;
    private ScrollPane chatScrollPane;
    private String currentChat = null;
    private final Stage primaryStage;
    private double xOffset = 0;
    private double yOffset = 0;
    private Stage emojiStage;
    private final HashMap<String, List<ConversationMessage>> conversations = new HashMap<>();
    private final HashMap<String, Boolean> userStatusMap = new HashMap<>();
    private final String HEADER_COLOR = "#008069";
    private final String GRAY_LIGHTER = "#FAFAFA";
    private final ClientConnection connectionHandler;
    private final DatabaseService databaseService;
    private final String username;
    private final List<UserStatus> connectedUsers = new ArrayList<>();
    private final Set<String> allContacts = new HashSet<>();
    private MediaService mediaService;
    private AudioRecorder recorder;
    private boolean isFullScreen = false;
    private final Emogi emojiManager;
    static class ConversationMessage {
        public String messageId;
        String sender;
        String content;
        Date timestamp;
        boolean isFile;
        boolean isVoice;
        boolean isEmoji;
        byte[] fileData;
        String fileName;
        int emojiId;

        ConversationMessage(String sender, String content) {
            this.sender = sender;
            this.content = content;
            this.timestamp = new Date();
            this.isFile = false;
            this.isVoice = false;
            this.isEmoji = false;
        }

        ConversationMessage(String sender, String content, byte[] fileData, String fileName) {
            this.sender = sender;
            this.content = content;
            this.timestamp = new Date();
            this.isFile = content.startsWith("Fichier :");
            this.isVoice = content.equals("Message vocal");
            this.isEmoji = false;
            this.fileData = fileData;
            this.fileName = fileName;
        }

        ConversationMessage(String sender, int emojiId) {
            this.sender = sender;
            this.content = "Emoji";
            this.timestamp = new Date();
            this.isFile = false;
            this.isVoice = false;
            this.isEmoji = true;
            this.emojiId = emojiId;
        }
    }
    public ConversationService(String username, String password, String host, int port, Stage primaryStage) {
        this.username = username;
        this.primaryStage = primaryStage;
        this.emojiManager = Emogi.getInstance();

        this.databaseService = new DatabaseService();

        // Établir la connexion au serveur
        connectionHandler = new ClientConnection(
                username,
                this::handleIncomingMessage,
                this::updateUserList
        );

        if (!connectionHandler.connect(host, port)) {
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("Erreur de connexion");
                alert.setHeaderText("Impossible de se connecter au serveur");
                alert.setContentText("Vérifiez votre connexion réseau et réessayez.");
                alert.showAndWait();
                Platform.exit();
            });
            return;
        }

        this.mediaService = new MediaService(username, this);

        Platform.runLater(() -> {
            createUI();
            initializeConversations();
        });
    }
    private void startMessagePolling() {
        Timer timer = new Timer(true);
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                for (String contact : allContacts) {
                    List<Message> newMessages = databaseService.getNewMessagesForConversation(username, contact);
                    for (Message msg : newMessages) {
                        String messageId = generateMessageId(msg);
                        List<ConversationMessage> conversation = conversations.get(contact);
                        if (conversation == null || conversation.stream().noneMatch(m -> m.messageId.equals(messageId))) {
                            handleIncomingMessage(msg);
                        }
                    }
                }
            }
        }, 10000, 30000);
    }

    private String generateMessageId(Message msg) {
        return String.valueOf(msg.getEmojiId());
    }

    private void initializeConversations() {
        Set<String> dbContacts = databaseService.getAllContacts(username);
        System.out.println("Contacts chargés depuis la base de données: " + dbContacts);
        allContacts.clear();
        allContacts.addAll(dbContacts);

        Set<String> allUsers = databaseService.getAllUsers();
        System.out.println("Tous les utilisateurs du système: " + allUsers);
        allUsers.remove(username);
        allContacts.addAll(allUsers);

        for (String contact : allContacts) {
            if (!conversations.containsKey(contact)) {
                List<Message> dbMessages = databaseService.getMessagesForConversation(username, contact);
                System.out.println("Messages chargés pour " + contact + ": " + dbMessages.size());

                List<ConversationMessage> convMessages = new ArrayList<>();
                for (Message msg : dbMessages) {
                    if (msg.getType() == Message.MessageType.EMOJI) {
                        convMessages.add(new ConversationMessage(msg.getSender(), msg.getEmojiId()));
                    } else if (msg.getType() == Message.MessageType.FILE) {
                        convMessages.add(new ConversationMessage(msg.getSender(), "Fichier : " + msg.getFileName(), msg.getFileData(), msg.getFileName()));
                    } else if (msg.getType() == Message.MessageType.VOICE) {
                        convMessages.add(new ConversationMessage(msg.getSender(), "Message vocal", msg.getFileData(), msg.getFileName()));
                    } else {
                        convMessages.add(new ConversationMessage(msg.getSender(), msg.getContent()));
                    }
                }
                conversations.put(contact, convMessages);
            }
        }
        updateConversationsList();
    }
    private void handleIncomingMessage(Message message) {
        Platform.runLater(() -> {
            if (message.getSender().equals("SERVER")) {
                System.out.println("Message du serveur ignoré : " + message.getContent());
                return;
            }

            String conversationKey = message.getSender().equals(username) ?
                    message.getReceiver() : message.getSender();

            System.out.println("Handling message from " + message.getSender() +
                    " in conversation: " + conversationKey + ", emojiId=" + message.getEmojiId());

            if (!allContacts.contains(conversationKey)) {
                allContacts.add(conversationKey);
            }

            if (!conversations.containsKey(conversationKey)) {
                conversations.put(conversationKey, new ArrayList<>());
            }

            ConversationMessage internalMessage;
            if (message.getType() == Message.MessageType.EMOJI) {
                internalMessage = new ConversationMessage(
                        message.getSender(),
                        message.getEmojiId()
                );
            } else if (message.getType() == Message.MessageType.FILE) {
                internalMessage = new ConversationMessage(
                        message.getSender(),
                        "Fichier : " + message.getFileName(),
                        message.getFileData(),
                        message.getFileName()
                );
                if (message.getFileData() != null) {
                    try {
                        File downloadDir = new File("downloads");
                        if (!downloadDir.exists()) {
                            downloadDir.mkdir();
                        }

                        File receivedFile = new File(downloadDir,
                                System.currentTimeMillis() + "_" + message.getFileName());
                        Files.write(receivedFile.toPath(), message.getFileData());
                        System.out.println("Fichier reçu et sauvegardé : " + receivedFile.getAbsolutePath());
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            } else if (message.getType() == Message.MessageType.VOICE) {
                internalMessage = new ConversationMessage(
                        message.getSender(),
                        "Message vocal",
                        message.getFileData(),
                        message.getFileName()
                );
                System.out.println("Message vocal reçu de " + message.getSender() +
                        ", taille des données : " + (message.getFileData() != null ? message.getFileData().length : 0) + " octets");
                if (message.getFileData() != null) {
                    try {
                        File downloadDir = new File("voice_messages");
                        if (!downloadDir.exists()) {
                            downloadDir.mkdir();
                        }

                        File receivedFile = new File(downloadDir,
                                System.currentTimeMillis() + "_" + message.getFileName());
                        Files.write(receivedFile.toPath(), message.getFileData());
                        System.out.println("Message vocal reçu et sauvegardé : " + receivedFile.getAbsolutePath());
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            } else {
                internalMessage = new ConversationMessage(
                        message.getSender(),
                        message.getContent()
                );
            }

            conversations.get(conversationKey).add(internalMessage);

            if (!message.getSender().equals(username)) {
                databaseService.saveMessage(message);
            }

            if (message.getSender().equals(username)) {
                String receiverKey = message.getReceiver();
                if (!allContacts.contains(receiverKey)) {
                    allContacts.add(receiverKey);
                }
                if (!conversations.containsKey(receiverKey)) {
                    conversations.put(receiverKey, new ArrayList<>());
                }
            }

            if (conversationsListVBox != null) {
                updateConversationsList();
                if (conversationKey.equals(currentChat)) {
                    updateChatPanel(currentChat);
                } else {
                    showNotification(message.getSender() + " a envoyé un nouveau message");
                }
            }
        });
    }
    public Set<String> getAllContacts() {
        return allContacts;
    }
    public HashMap<String, Boolean> getUserStatusMap() {
        return userStatusMap;
    }
    private void showNotification(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Nouveau message");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.show();
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                Platform.runLater(() -> alert.close());
            }
        }, 1000);
    }
    private void updateUserList(ArrayList<UserStatus> users) {
        Platform.runLater(() -> {
            connectedUsers.clear();
            connectedUsers.addAll(users);

            connectedUsers.removeIf(user -> user.getUsername().equals(username));

            for (UserStatus user : connectedUsers) {
                userStatusMap.put(user.getUsername(), user.isOnline());

                if (!allContacts.contains(user.getUsername())) {
                    allContacts.add(user.getUsername());

                    if (!conversations.containsKey(user.getUsername())) {
                        List<Message> dbMessages = databaseService.getMessagesForConversation(username, user.getUsername());
                        List<ConversationMessage> convMessages = new ArrayList<>();
                        for (Message msg : dbMessages) {
                            if (msg.getType() == Message.MessageType.EMOJI) {
                                convMessages.add(new ConversationMessage(msg.getSender(), msg.getEmojiId()));
                            } else if (msg.getType() == Message.MessageType.FILE) {
                                convMessages.add(new ConversationMessage(msg.getSender(), "Fichier : " + msg.getFileName(), msg.getFileData(), msg.getFileName()));
                            } else if (msg.getType() == Message.MessageType.VOICE) {
                                convMessages.add(new ConversationMessage(msg.getSender(), "Message vocal", msg.getFileData(), msg.getFileName()));
                            } else {
                                convMessages.add(new ConversationMessage(msg.getSender(), msg.getContent()));
                            }
                        }
                        conversations.put(user.getUsername(), convMessages);
                    }
                }
            }

            System.out.println("Liste des utilisateurs mise à jour : " + connectedUsers);

            if (conversationsListVBox != null) {
                updateConversationsList();
            }
        });
    }
    private void createUI() {
        primaryStage.setTitle("ALANYA");

        BorderPane mainLayout = new BorderPane();

        HBox windowControls = createWindowControls();

        SplitPane splitPane = new SplitPane();

        VBox leftPane = new VBox();
        leftPane.setStyle("-fx-background-color: white;");

        HBox header = createHeader();

        HBox searchBox = createSearchBox();

        conversationsListVBox = new VBox();
        conversationsListVBox.setStyle("-fx-background-color: white;");
        ScrollPane conversationsScrollPane = new ScrollPane(conversationsListVBox);
        conversationsScrollPane.setFitToWidth(true);
        conversationsScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        Button logoutButton = new Button("Déconnexion");
        logoutButton.setMaxWidth(Double.MAX_VALUE);
        logoutButton.setPrefHeight(40);
        logoutButton.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-background-radius: 5px; -fx-cursor: hand;");
        logoutButton.setOnAction(e -> {
            connectionHandler.disconnect();
            if (mediaService != null) {
                mediaService.shutdown();
            }
            databaseService.closeConnection();
            primaryStage.close();
        });

        leftPane.getChildren().addAll(header, searchBox, conversationsScrollPane, logoutButton);
        VBox.setVgrow(conversationsScrollPane, Priority.ALWAYS);

        BorderPane chatPane = new BorderPane();
        chatPane.setStyle("-fx-background-color: #E5DDD5;");

        HBox chatHeader = createChatHeader();

        chatMessagesVBox = new VBox();
        chatMessagesVBox.setSpacing(8);
        chatMessagesVBox.setPadding(new Insets(10));
        chatMessagesVBox.setStyle("-fx-background-color: #E5DDD5;");

        chatScrollPane = new ScrollPane(chatMessagesVBox);
        chatScrollPane.setFitToWidth(true);
        chatScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        chatScrollPane.vvalueProperty().bind(chatMessagesVBox.heightProperty());

        HBox messageInputBox = createMessageInputBox();

        chatPane.setTop(chatHeader);
        chatPane.setCenter(chatScrollPane);
        chatPane.setBottom(messageInputBox);

        splitPane.getItems().addAll(leftPane, chatPane);
        splitPane.setDividerPositions(0.3);

        leftPane.setPrefWidth(350);

        VBox rootLayout = new VBox();
        rootLayout.getChildren().addAll(windowControls, splitPane);
        VBox.setVgrow(splitPane, Priority.ALWAYS);

        mainLayout.setCenter(rootLayout);

        Scene scene = new Scene(mainLayout, 1200, 800);

        windowControls.setOnMousePressed(event -> {
            xOffset = event.getSceneX();
            yOffset = event.getSceneY();
        });

        windowControls.setOnMouseDragged(event -> {
            primaryStage.setX(event.getScreenX() - xOffset);
            primaryStage.setY(event.getScreenY() - yOffset);
        });

        primaryStage.setScene(scene);
        primaryStage.setMinWidth(800);
        primaryStage.setMinHeight(600);

        updateConversationsList();

        primaryStage.setOnCloseRequest(event -> {
            connectionHandler.disconnect();
            if (mediaService != null) {
                mediaService.shutdown();
            }
            databaseService.closeConnection();
            Platform.exit();
        });
    }
    private HBox createWindowControls() {
        HBox controls = new HBox();
        controls.setAlignment(Pos.CENTER_RIGHT);
        controls.setPadding(new Insets(5, 10, 5, 10));
        controls.setSpacing(10);
        controls.setStyle("-fx-background-color: " + HEADER_COLOR + ";");

        Label titleLabel = new Label("ALANYA - " + username);
        titleLabel.setFont(Font.font("Arial", FontWeight.BOLD, 14));
        titleLabel.setTextFill(Color.WHITE);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button minimizeBtn = new Button("-");
        minimizeBtn.setStyle(
                "-fx-background-color: transparent; " +
                        "-fx-text-fill: white; " +
                        "-fx-font-weight: bold; " +
                        "-fx-cursor: hand;"
        );
        minimizeBtn.setOnAction(e -> primaryStage.setIconified(true));

        Button maximizeBtn = new Button("□");
        maximizeBtn.setStyle(
                "-fx-background-color: transparent; " +
                        "-fx-text-fill: white; " +
                        "-fx-font-weight: bold; " +
                        "-fx-cursor: hand;"
        );
        maximizeBtn.setOnAction(e -> {
            isFullScreen = !isFullScreen;
            primaryStage.setFullScreen(isFullScreen);
        });

        Button closeBtn = new Button("X");
        closeBtn.setStyle(
                "-fx-background-color: transparent; " +
                        "-fx-text-fill: white; " +
                        "-fx-font-weight: bold; " +
                        "-fx-cursor: hand;"
        );
        closeBtn.setOnAction(e -> {
            connectionHandler.disconnect();
            if (mediaService != null) {
                mediaService.shutdown();
            }
            databaseService.closeConnection();
            primaryStage.close();
        });

        controls.getChildren().addAll(titleLabel, spacer, minimizeBtn, maximizeBtn, closeBtn);

        return controls;
    }
    private HBox createHeader() {
        HBox header = new HBox();
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(10));
        header.setStyle("-fx-background-color: " + HEADER_COLOR + ";");

        Label titleLabel = new Label("ALANYA");
        titleLabel.setFont(Font.font("Arial", FontWeight.BOLD, 18));
        titleLabel.setTextFill(Color.WHITE);
        titleLabel.setPadding(new Insets(0, 0, 0, 10));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        header.getChildren().addAll(titleLabel, spacer);

        return header;
    }
    private Button createIconButton(String imagePath, String tooltip) {
        Button button = new Button();
        try {
            InputStream iconStream = getClass().getResourceAsStream(imagePath);
            if (iconStream != null) {
                Image image = new Image(iconStream);
                ImageView imageView = new ImageView(image);
                imageView.setFitHeight(20);
                imageView.setFitWidth(20);
                button.setGraphic(imageView);
                iconStream.close();
            } else {
                System.out.println("Ressource non trouvée : " + imagePath);
                button.setText(tooltip.substring(0, 1));
            }
        } catch (Exception e) {
            System.err.println("Erreur lors du chargement de l'icône : " + e.getMessage());
            button.setText(tooltip.substring(0, 1));
        }

        button.setTooltip(new Tooltip(tooltip));
        button.setStyle(
                "-fx-background-color: transparent; " +
                        "-fx-cursor: hand;"
        );
        return button;
    }
    private HBox createSearchBox() {
        HBox searchBox = new HBox();
        searchBox.setPadding(new Insets(10));
        String GRAY_LIGHT = "#F0F0F0";
        searchBox.setStyle("-fx-background-color: " + GRAY_LIGHT + ";");

        TextField searchField = new TextField();
        searchField.setPromptText("Rechercher ou démarrer une nouvelle conversation");
        searchField.getStyleClass().add("search-field");
        HBox.setHgrow(searchField, Priority.ALWAYS);

        searchField.textProperty().addListener((observable, oldValue, newValue) -> {
            filterConversations(newValue);
        });

        searchBox.getChildren().addAll(searchField);

        return searchBox;
    }
    private void filterConversations(String filter) {
        if (filter == null || filter.trim().isEmpty()) {
            updateConversationsList();
            return;
        }

        conversationsListVBox.getChildren().clear();

        for (String contact : allContacts) {
            if (contact.toLowerCase().contains(filter.toLowerCase())) {
                conversationsListVBox.getChildren().add(createConversationItem(contact));
            }
        }
    }
    private HBox createChatHeader() {
        HBox chatHeader = new HBox();
        chatHeader.setAlignment(Pos.CENTER_LEFT);
        chatHeader.setPadding(new Insets(10));
        chatHeader.setStyle("-fx-background-color: " + GRAY_LIGHTER + "; -fx-border-color: #E0E0E0; -fx-border-width: 0 0 1 0;");

        StackPane avatarPane = new StackPane();
        Circle avatar = new Circle(20, Color.LIGHTGRAY);
        Text avatarText = new Text("?");
        avatarText.setFill(Color.WHITE);
        Circle statusCircle = new Circle(8);
        statusCircle.setFill(Color.GRAY);
        StackPane statusPane = new StackPane(statusCircle);
        statusPane.setAlignment(Pos.TOP_RIGHT);
        statusPane.setTranslateX(5);
        statusPane.setTranslateY(-5);
        avatarPane.getChildren().addAll(avatar, avatarText, statusPane);

        Label contactNameLabel = new Label("Sélectionnez une conversation");
        contactNameLabel.setFont(Font.font("Arial", FontWeight.BOLD, 16));
        contactNameLabel.setPadding(new Insets(0, 0, 0, 10));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button callButton = createIconButton("/image/call.png", "Appel vocal");
        callButton.setOnAction(e -> {
            if (currentChat != null) {
                System.out.println("Démarrage de l'appel vocal avec " + currentChat);
                mediaService.startVoiceCall(currentChat);
            } else {
                showNotification("Veuillez d'abord sélectionner un contact");
            }
        });

        Button videoButton = createIconButton("/image/camera.png", "Appel vidéo");
        videoButton.setOnAction(e -> {
            if (currentChat != null) {
                System.out.println("Démarrage de l'appel vidéo avec " + currentChat);
                mediaService.startVideoCall(currentChat);
            } else {
                showNotification("Veuillez d'abord sélectionner un contact");
            }
        });

        chatHeader.getChildren().addAll(avatarPane, contactNameLabel, spacer, callButton, videoButton);

        return chatHeader;
    }
    private boolean isImageFile(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".jpeg") || name.endsWith(".gif");
    }
    private HBox createMessageInputBox() {
        HBox messageBox = new HBox();
        messageBox.setAlignment(Pos.CENTER);
        messageBox.setPadding(new Insets(10));
        messageBox.setSpacing(8);
        messageBox.setStyle("-fx-background-color: " + GRAY_LIGHTER + ";");

        Button emojiButton = createIconButton("/image/icon.png", "Emoji");
        emojiButton.setOnAction(e -> {
            if (currentChat == null) {
                showNotification("Veuillez d'abord sélectionner une conversation");
                return;
            }
            showEmojiSelector();
        });

        messageField = new TextField();
        messageField.setPromptText("Tapez un message");
        messageField.getStyleClass().add("message-field");
        HBox.setHgrow(messageField, Priority.ALWAYS);

        messageField.setOnAction(e -> sendMessage());

        Button sendButton = createIconButton("/image/sent.png", "Envoyer");
        sendButton.setOnAction(e -> sendMessage());

        Button attachButton = createIconButton("/image/file.png", "Joindre");
        attachButton.setOnAction(e -> {
            if (currentChat == null) {
                showNotification("Veuillez d'abord sélectionner une conversation");
                return;
            }

            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Choisir un fichier à envoyer");
            File file = fileChooser.showOpenDialog(primaryStage);

            if (file != null) {
                sendFile(currentChat, file);
            }
        });

        Button voiceButton = createIconButton("/image/microphone.png", "Message vocal");
        voiceButton.setOnAction(e -> {
            if (currentChat == null) {
                showNotification("Veuillez d'abord sélectionner une conversation");
                return;
            }

            if (recorder == null || !recorder.isRecording()) {
                recorder = new AudioRecorder();
                boolean started = recorder.startRecording();

                if (started) {
                    voiceButton.setStyle("-fx-background-color: #ff4d4d;");
                    messageField.setDisable(true);
                    messageField.setPromptText("Enregistrement en cours...");
                } else {
                    showNotification("Impossible de démarrer l'enregistrement");
                }
            } else {
                byte[] audioData = recorder.stopRecording();
                voiceButton.setStyle("");
                messageField.setDisable(false);
                messageField.setPromptText("Tapez un message");

                if (audioData != null && audioData.length > 0) {
                    sendVoiceMessage(currentChat, audioData);
                } else {
                    showNotification("Aucun audio enregistré ou données vides.");
                }
            }
        });

        messageBox.getChildren().addAll(emojiButton, messageField, attachButton, voiceButton, sendButton);

        return messageBox;
    }
    private void sendFile(String receiver, File file) {
        if (receiver == null || file == null) return;

        try {
            byte[] fileData = Files.readAllBytes(file.toPath());

            // Envoyer le fichier via le handler de connexion
            connectionHandler.sendFile(receiver, file);

            // Créer un message interne
            ConversationMessage message = new ConversationMessage(
                    username, "Fichier : " + file.getName(), fileData, file.getName()
            );

            // Ajouter à la conversation locale
            if (!conversations.containsKey(receiver)) {
                conversations.put(receiver, new ArrayList<>());
                allContacts.add(receiver);
            }
            conversations.get(receiver).add(message);

            // Sauvegarder dans la base de données
            Message dbMessage = new Message();
            dbMessage.setSender(username);
            dbMessage.setReceiver(receiver);
            dbMessage.setContent("Fichier : " + file.getName());
            dbMessage.setType(Message.MessageType.FILE);
            dbMessage.setFileData(fileData);
            dbMessage.setFileName(file.getName());
            databaseService.saveMessage(dbMessage);

            // Mettre à jour l'interface
            updateChatPanel(receiver);
            updateConversationsList();
        } catch (IOException e) {
            e.printStackTrace();
            showNotification("Erreur lors de la lecture du fichier : " + e.getMessage());
        }
    }
    private void sendVoiceMessage(String receiver, byte[] audioData) {
        if (receiver == null || audioData == null || audioData.length == 0) return;

        // Envoyer le message vocal via le handler de connexion
        connectionHandler.sendVoiceMessage(receiver, audioData);

        // Créer un message interne
        ConversationMessage message = new ConversationMessage(
                username, "Message vocal", audioData, "voice_message.wav"
        );

        // Ajouter à la conversation locale
        if (!conversations.containsKey(receiver)) {
            conversations.put(receiver, new ArrayList<>());
            allContacts.add(receiver);
        }
        conversations.get(receiver).add(message);

        // Sauvegarder dans la base de données
        Message dbMessage = new Message();
        dbMessage.setSender(username);
        dbMessage.setReceiver(receiver);
        dbMessage.setContent("Message vocal");
        dbMessage.setType(Message.MessageType.VOICE);
        dbMessage.setFileData(audioData);
        dbMessage.setFileName("voice_message.wav");
        databaseService.saveMessage(dbMessage);

        // Mettre à jour l'interface
        updateChatPanel(receiver);
        updateConversationsList();
    }
    private void showEmojiSelector() {
        if (emojiStage != null && emojiStage.isShowing()) {
            emojiStage.close();
            return;
        }

        emojiStage = new Stage();
        emojiStage.initStyle(StageStyle.UNDECORATED);
        emojiStage.initOwner(primaryStage);

        VBox emojiBox = new VBox();
        emojiBox.setSpacing(10);
        emojiBox.setPadding(new Insets(10));
        emojiBox.setStyle("-fx-background-color: white; -fx-border-color: #E0E0E0; -fx-border-width: 1;");

        HBox row = new HBox();
        row.setSpacing(5);
        int count = 0;

        List<Model_Emoji> style1 = emojiManager.getStyle1();
        List<Model_Emoji> style2 = emojiManager.getStyle2();

        for (Model_Emoji emoji : style1) {
            ImageView emojiView = new ImageView(new Image(emoji.getIcon().toString()));
            emojiView.setFitWidth(30);
            emojiView.setFitHeight(30);

            Button emojiBtn = new Button();
            emojiBtn.setGraphic(emojiView);
            emojiBtn.setStyle("-fx-background-color: transparent; -fx-cursor: hand;");
            emojiBtn.setOnAction(e -> {
                sendEmoji(emoji.getId());
                emojiStage.close();
            });

            row.getChildren().add(emojiBtn);
            count++;
            if (count % 5 == 0) {
                emojiBox.getChildren().add(row);
                row = new HBox();
                row.setSpacing(5);
            }
        }

        for (Model_Emoji emoji : style2) {
            ImageView emojiView = new ImageView(new Image(emoji.getIcon().toString()));
            emojiView.setFitWidth(30);
            emojiView.setFitHeight(30);

            Button emojiBtn = new Button();
            emojiBtn.setGraphic(emojiView);
            emojiBtn.setStyle("-fx-background-color: transparent; -fx-cursor: hand;");
            emojiBtn.setOnAction(e -> {
                sendEmoji(emoji.getId());
                emojiStage.close();
            });

            row.getChildren().add(emojiBtn);
            count++;
            if (count % 5 == 0) {
                emojiBox.getChildren().add(row);
                row = new HBox();
                row.setSpacing(5);
            }
        }

        if (!row.getChildren().isEmpty()) {
            emojiBox.getChildren().add(row);
        }

        Scene scene = new Scene(emojiBox);
        emojiStage.setScene(scene);
        emojiStage.setX(primaryStage.getX() + primaryStage.getWidth() - 300);
        emojiStage.setY(primaryStage.getY() + primaryStage.getHeight() - 300);
        emojiStage.show();
    }
    private void sendEmoji(int emojiId) {
        if (currentChat == null) return;

        connectionHandler.sendEmoji(currentChat, emojiId);

        if (!allContacts.contains(currentChat)) {
            allContacts.add(currentChat);
        }

        if (!conversations.containsKey(currentChat)) {
            conversations.put(currentChat, new ArrayList<>());
        }

        ConversationMessage message = new ConversationMessage(username, emojiId);
        conversations.get(currentChat).add(message);

        Message dbMessage = new Message();
        dbMessage.setSender(username);
        dbMessage.setReceiver(currentChat);
        dbMessage.setContent("Emoji");
        dbMessage.setType(Message.MessageType.EMOJI);
        dbMessage.setEmojiId(emojiId);
        databaseService.saveMessage(dbMessage);

        updateChatPanel(currentChat);
        updateConversationsList();
    }
    private VBox createConversationItem(String contact) {
        VBox item = new VBox();
        item.setPadding(new Insets(10));
        item.setStyle("-fx-background-color: white; -fx-border-color: #E0E0E0; -fx-border-width: 0 0 1 0;");

        HBox contentBox = new HBox();
        contentBox.setSpacing(10);

        StackPane avatarPane = new StackPane();
        Circle avatar = new Circle(25);
        Text avatarText = new Text(contact.substring(0, 1).toUpperCase());
        avatarText.setFill(Color.WHITE);
        avatarText.setFont(Font.font("Arial", FontWeight.BOLD, 16));

        byte[] profilePicture = databaseService.getProfilePicture(contact);
        if (profilePicture != null && profilePicture.length > 0) {
            try {
                Image image = new Image(new ByteArrayInputStream(profilePicture));
                ImageView profileImage = new ImageView(image);
                profileImage.setFitWidth(50);
                profileImage.setFitHeight(50);
                Circle clip = new Circle(25, 25, 25);
                profileImage.setClip(clip);
                avatarPane.getChildren().add(profileImage);
            } catch (Exception e) {
                System.err.println("Erreur lors du chargement de la photo de profil pour " + contact + " : " + e.getMessage());
                int hash = contact.hashCode();
                Color avatarColor = Color.rgb(
                        Math.abs(hash) % 200,
                        Math.abs(hash / 10) % 200 + 55,
                        Math.abs(hash / 100) % 200 + 55
                );
                avatar.setFill(avatarColor);
                avatarPane.getChildren().addAll(avatar, avatarText);
            }
        } else {
            int hash = contact.hashCode();
            Color avatarColor = Color.rgb(
                    Math.abs(hash) % 200,
                    Math.abs(hash / 10) % 200 + 55,
                    Math.abs(hash / 100) % 200 + 55
            );
            avatar.setFill(avatarColor);
            avatarPane.getChildren().addAll(avatar, avatarText);
        }

        Circle statusCircle = new Circle(8);
        boolean isOnline = userStatusMap.getOrDefault(contact, false);
        statusCircle.setFill(isOnline ? Color.GREEN : Color.GRAY);
        StackPane statusPane = new StackPane(statusCircle);
        statusPane.setAlignment(Pos.TOP_RIGHT);
        statusPane.setTranslateX(5);
        statusPane.setTranslateY(-5);
        avatarPane.getChildren().add(statusPane);

        VBox detailsBox = new VBox();
        detailsBox.setSpacing(3);
        HBox.setHgrow(detailsBox, Priority.ALWAYS);

        HBox topRow = new HBox();
        topRow.setAlignment(Pos.CENTER_LEFT);

        Label nameLabel = new Label(contact);
        nameLabel.setFont(Font.font("Arial", FontWeight.BOLD, 14));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label timeLabel = new Label("");
        timeLabel.setTextFill(Color.GRAY);
        timeLabel.setFont(Font.font("Arial", 12));

        topRow.getChildren().addAll(nameLabel, spacer, timeLabel);

        Label previewLabel = new Label("Aucun message pour l'instant");
        previewLabel.setTextFill(Color.GRAY);

        List<ConversationMessage> messages = conversations.get(contact);
        if (messages != null && !messages.isEmpty()) {
            ConversationMessage lastMessage = messages.get(messages.size() - 1);

            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm");
            timeLabel.setText(sdf.format(lastMessage.timestamp));

            String preview;
            if (lastMessage.isEmoji) {
                preview = (lastMessage.sender.equals(username) ? "Vous : " : lastMessage.sender + " : ") + "😊";
            } else {
                preview = (lastMessage.sender.equals(username) ? "Vous : " : lastMessage.sender + " : ") + lastMessage.content;
            }
            if (preview.length() > 40) {
                preview = preview.substring(0, 37) + "...";
            }

            previewLabel.setText(preview);
        }

        detailsBox.getChildren().addAll(topRow, previewLabel);

        contentBox.getChildren().addAll(avatarPane, detailsBox);

        item.getChildren().add(contentBox);

        item.setOnMouseClicked(e -> {
            currentChat = contact;
            updateChatPanel(contact);

            HBox header = (HBox) ((BorderPane) chatScrollPane.getParent()).getTop();
            StackPane avatarPane2 = (StackPane) header.getChildren().get(0);
            avatarPane2.getChildren().clear();

            byte[] headerProfilePicture = databaseService.getProfilePicture(contact);
            if (headerProfilePicture != null && headerProfilePicture.length > 0) {
                try {
                    Image image = new Image(new ByteArrayInputStream(headerProfilePicture));
                    ImageView profileImage = new ImageView(image);
                    profileImage.setFitWidth(40);
                    profileImage.setFitHeight(40);
                    Circle clip = new Circle(20, 20, 20);
                    profileImage.setClip(clip);
                    avatarPane2.getChildren().add(profileImage);
                } catch (Exception ex) {
                    Circle defaultAvatar = new Circle(20);
                    int hash = contact.hashCode();
                    Color avatarColor = Color.rgb(
                            Math.abs(hash) % 200,
                            Math.abs(hash / 10) % 200 + 55,
                            Math.abs(hash / 100) % 200 + 55
                    );
                    defaultAvatar.setFill(avatarColor);
                    Text defaultText = new Text(contact.substring(0, 1).toUpperCase());
                    defaultText.setFill(Color.WHITE);
                    avatarPane2.getChildren().addAll(defaultAvatar, defaultText);
                }
            } else {
                Circle defaultAvatar = new Circle(20);
                int hash = contact.hashCode();
                Color avatarColor = Color.rgb(
                        Math.abs(hash) % 200,
                        Math.abs(hash / 10) % 200 + 55,
                        Math.abs(hash / 100) % 200 + 55
                );
                defaultAvatar.setFill(avatarColor);
                Text defaultText = new Text(contact.substring(0, 1).toUpperCase());
                defaultText.setFill(Color.WHITE);
                avatarPane2.getChildren().addAll(defaultAvatar, defaultText);
            }

            Circle headerStatusCircle = new Circle(8);
            headerStatusCircle.setFill(isOnline ? Color.GREEN : Color.GRAY);
            StackPane headerStatusPane = new StackPane(headerStatusCircle);
            headerStatusPane.setAlignment(Pos.TOP_RIGHT);
            headerStatusPane.setTranslateX(5);
            headerStatusPane.setTranslateY(-5);
            avatarPane2.getChildren().add(headerStatusPane);

            Label contactLabel = (Label) header.getChildren().get(1);
            contactLabel.setText(contact);
        });

        item.setOnMouseEntered(e ->
                item.setStyle("-fx-background-color: #F5F5F5; -fx-border-color: #E0E0E0; -fx-border-width: 0 0 1 0;")
        );

        item.setOnMouseExited(e ->
                item.setStyle("-fx-background-color: white; -fx-border-color: #E0E0E0; -fx-border-width: 0 0 1 0;")
        );

        return item;
    }
    private void updateConversationsList() {
        conversationsListVBox.getChildren().clear();

        for (String contact : allContacts) {
            if (!conversations.containsKey(contact)) {
                conversations.put(contact, new ArrayList<>());
            }
        }

        ArrayList<String> sortedContacts = new ArrayList<>(allContacts);

        sortedContacts.sort((a, b) -> {
            List<ConversationMessage> messagesA = conversations.get(a);
            List<ConversationMessage> messagesB = conversations.get(b);

            if ((messagesA == null || messagesA.isEmpty()) &&
                    (messagesB == null || messagesB.isEmpty())) {
                return a.compareTo(b);
            }
            if (messagesA == null || messagesA.isEmpty()) {
                return 1;
            }
            if (messagesB == null || messagesB.isEmpty()) {
                return -1;
            }

            ConversationMessage lastA = messagesA.get(messagesA.size() - 1);
            ConversationMessage lastB = messagesB.get(messagesB.size() - 1);
            return lastB.timestamp.compareTo(lastA.timestamp);
        });

        for (String contact : sortedContacts) {
            conversationsListVBox.getChildren().add(createConversationItem(contact));
        }
    }
    private void updateChatPanel(String contact) {
        chatMessagesVBox.getChildren().clear();

        List<ConversationMessage> messages = conversations.get(contact);
        if (messages != null) {
            for (ConversationMessage message : messages) {
                chatMessagesVBox.getChildren().add(createMessageBubble(message));
            }
        }
    }
    private HBox createMessageBubble(ConversationMessage message) {
        boolean isMyMessage = message.sender.equals(username);

        HBox container = new HBox();
        container.setPadding(new Insets(5, 15, 5, 15));
        container.setAlignment(isMyMessage ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);

        VBox bubble = new VBox();
        bubble.setSpacing(3);
        bubble.setPadding(new Insets(8, 12, 8, 12));
        bubble.setMaxWidth(500);
        String LIGHT_GREEN = "#DCF8C6";
        bubble.setStyle(
                "-fx-background-color: " + (isMyMessage ? LIGHT_GREEN : "white") + ";" +
                        "-fx-background-radius: 10;" +
                        "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.1), 3, 0, 0, 1);"
        );

        if (!isMyMessage) {
            Label senderLabel = new Label(message.sender);
            senderLabel.setFont(Font.font("Arial", FontWeight.BOLD, 12));
            String PRIMARY_COLOR = "#009688";
            senderLabel.setTextFill(Color.web(PRIMARY_COLOR));
            bubble.getChildren().add(senderLabel);
        }

        if (message.isEmoji) {
            Model_Emoji emoji = emojiManager.getImoji(message.emojiId);
            ImageView emojiView = new ImageView(new Image(emoji.getIcon().toString()));
            emojiView.setFitWidth(30);
            emojiView.setFitHeight(30);
            bubble.getChildren().add(emojiView);
        } else if (message.isVoice) {
            HBox voiceBox = new HBox();
            voiceBox.setSpacing(10);
            voiceBox.setAlignment(Pos.CENTER_LEFT);

            Label micIcon = new Label("🎤");
            micIcon.setFont(Font.font("Arial", 16));

            int durationSeconds = message.fileData != null ? message.fileData.length / 8000 : 0;
            Label durationLabel = new Label(String.format("%d:%02d", durationSeconds / 60, durationSeconds % 60));

            Button playButton = createIconButton("/image/play.png", "Lire");

            playButton.setOnAction(e -> {
                if (message.fileData == null || message.fileData.length == 0) {
                    showNotification("Aucun audio à lire.");
                    return;
                }
                playButton.setDisable(true);
                playButton.setText("⏳");

                new Thread(() -> {
                    AudioRecorder.playAudio(message.fileData);

                    Platform.runLater(() -> {
                        playButton.setDisable(false);
                        playButton.setText("▶");
                    });
                }).start();
            });

            voiceBox.getChildren().addAll(micIcon, playButton, durationLabel);
            bubble.getChildren().add(voiceBox);
        } else if (message.isFile) {
            HBox fileBox = new HBox();
            fileBox.setSpacing(10);
            fileBox.setAlignment(Pos.CENTER_LEFT);

            ImageView fileIcon;
            try {
                InputStream iconStream = getClass().getResourceAsStream("/image/file.png");
                if (iconStream != null) {
                    Image image = new Image(iconStream);
                    fileIcon = new ImageView(image);
                    fileIcon.setFitHeight(20);
                    fileIcon.setFitWidth(20);
                    iconStream.close();
                } else {
                    Label fileIconLabel = new Label("📁");
                    fileIconLabel.setFont(Font.font("Arial", 16));
                    fileBox.getChildren().add(fileIconLabel);
                    fileIcon = null;
                }
            } catch (Exception e) {
                Label fileIconLabel = new Label("📁");
                fileIconLabel.setFont(Font.font("Arial", 16));
                fileBox.getChildren().add(fileIconLabel);
                fileIcon = null;
            }

            Button downloadButton = new Button("Télécharger");
            downloadButton.setOnAction(e -> {
                try {
                    File downloadDir = new File("downloads");
                    if (!downloadDir.exists()) {
                        downloadDir.mkdir();
                    }

                    File receivedFile = new File(downloadDir, message.fileName);
                    Files.write(receivedFile.toPath(), message.fileData);

                    showNotification("Fichier téléchargé à : " + receivedFile.getAbsolutePath());
                } catch (IOException ex) {
                    ex.printStackTrace();
                    showNotification("Erreur lors de l'enregistrement du fichier : " + ex.getMessage());
                }
            });

            if (fileIcon != null) {
                fileBox.getChildren().addAll(fileIcon, downloadButton);
            } else {
                fileBox.getChildren().addAll(downloadButton);
            }
            bubble.getChildren().add(fileBox);
        } else {
            Label contentLabel = new Label(message.content);
            contentLabel.setWrapText(true);
            bubble.getChildren().add(contentLabel);
        }

        HBox infoBox = new HBox();
        infoBox.setAlignment(Pos.CENTER_RIGHT);
        infoBox.setSpacing(5);

        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm");
        Label timeLabel = new Label(sdf.format(message.timestamp));
        timeLabel.setFont(Font.font("Arial", 10));
        timeLabel.setTextFill(Color.GRAY);

        infoBox.getChildren().add(timeLabel);

        if (isMyMessage) {
            Label checkLabel = new Label("✓✓");
            checkLabel.setFont(Font.font("Arial", 10));
            checkLabel.setTextFill(Color.web("#4FC3F7"));
            infoBox.getChildren().add(checkLabel);
        }

        bubble.getChildren().add(infoBox);
        container.getChildren().add(bubble);

        return container;
    }
    private void sendMessage() {
        String text = messageField.getText().trim();
        if (text.isEmpty() || currentChat == null) return;

        connectionHandler.sendMessage(currentChat, text);

        if (!allContacts.contains(currentChat)) {
            allContacts.add(currentChat);
        }

        if (!conversations.containsKey(currentChat)) {
            conversations.put(currentChat, new ArrayList<>());
        }

        ConversationMessage message = new ConversationMessage(username, text);
        conversations.get(currentChat).add(message);

        Message dbMessage = new Message();
        dbMessage.setSender(username);
        dbMessage.setReceiver(currentChat);
        dbMessage.setContent(text);
        dbMessage.setType(Message.MessageType.TEXT);
        databaseService.saveMessage(dbMessage);

        updateChatPanel(currentChat);
        updateConversationsList();

        messageField.setText("");
    }

    public void show() {
        primaryStage.show();
    }
}