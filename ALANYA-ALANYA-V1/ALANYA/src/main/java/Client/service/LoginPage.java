package Client.service;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.concurrent.Task;

import Client.service.ConversationService;

import java.io.File;
import java.io.FileInputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class LoginPage extends Application {

    private static final String DEFAULT_HOST = "10.2.61.28";
    private static final int DEFAULT_PORT = 8080;

    // Base de données configuration
    private static final String DB_URL = "jdbc:mysql://0.0.0.0:3306/ALANYA_BD";
    private static final String DB_USER = "root";
    private static final String DB_PASSWORD = "22p3?9";

    // UI components
    private TextField usernameField;
    private PasswordField passwordField;
    private Button loginButton;
    private Button registerButton;
    private Label statusLabel;

    @Override
    public void start(Stage primaryStage) {
        // Configuration de la fenêtre principale
        primaryStage.setTitle("ALANYA Messenger - Login");
        primaryStage.initStyle(StageStyle.UNDECORATED);

        // Création du conteneur principal avec fond stylisé
        VBox mainContainer = new VBox(20);
        mainContainer.setPadding(new Insets(30));
        mainContainer.setAlignment(Pos.CENTER);
        mainContainer.setStyle("-fx-background-color: linear-gradient(to bottom, #00967d, #006b5a)");

        // Logo et titre
        ImageView logoView = new ImageView(new Image(new File("image/alanya.jpg").toURI().toString()));
        logoView.setFitHeight(100);
        logoView.setFitWidth(100);
        logoView.setPreserveRatio(true);

        Label titleLabel = new Label("ALANYA Messenger");
        titleLabel.setFont(Font.font("Arial", FontWeight.BOLD, 26));
        titleLabel.setTextFill(Color.WHITE);

        // Formulaire de connexion
        VBox loginForm = new VBox(15);
        loginForm.setStyle("-fx-background-color: white; -fx-background-radius: 10px;");
        loginForm.setPadding(new Insets(25));
        loginForm.setMaxWidth(400);

        Label loginHeading = new Label("Connexion");
        loginHeading.setFont(Font.font("Arial", FontWeight.BOLD, 18));

        // Champ nom d'utilisateur
        Label usernameLabel = new Label("Nom d'utilisateur");
        usernameField = new TextField();
        usernameField.setPromptText("Entrez votre nom d'utilisateur");
        usernameField.setPrefHeight(40);
        usernameField.setStyle("-fx-background-radius: 5px;");

        // Champ mot de passe
        Label passwordLabel = new Label("Mot de passe");
        passwordField = new PasswordField();
        passwordField.setPromptText("Entrez votre mot de passe");
        passwordField.setPrefHeight(40);
        passwordField.setStyle("-fx-background-radius: 5px;");

        // Boutons de connexion et d'inscription
        HBox buttonContainer = new HBox(15);
        buttonContainer.setAlignment(Pos.CENTER);

        loginButton = new Button("Se connecter");
        loginButton.setPrefSize(150, 40);
        loginButton.setStyle("-fx-background-color: #00967d; -fx-text-fill: white; -fx-background-radius: 5px;");

        registerButton = new Button("S'inscrire");
        registerButton.setPrefSize(150, 40);
        registerButton.setStyle("-fx-background-color: #787878; -fx-text-fill: white; -fx-background-radius: 5px;");

        buttonContainer.getChildren().addAll(loginButton, registerButton);

        // Label pour les messages d'état/erreur
        statusLabel = new Label("");
        statusLabel.setTextFill(Color.RED);

        // Ajout des composants au formulaire
        loginForm.getChildren().addAll(
                loginHeading,
                usernameLabel, usernameField,
                passwordLabel, passwordField,
                buttonContainer,
                statusLabel
        );

        // Bouton pour fermer l'application
        Button closeButton = new Button("×");
        closeButton.setStyle("-fx-background-color: transparent; -fx-text-fill: white; -fx-font-size: 20px;");
        closeButton.setOnAction(e -> primaryStage.close());

        // Placer le bouton de fermeture en haut à droite
        StackPane topContainer = new StackPane();
        topContainer.getChildren().add(closeButton);
        StackPane.setAlignment(closeButton, Pos.TOP_RIGHT);

        // Ajouter tous les éléments au conteneur principal
        mainContainer.getChildren().addAll(topContainer, logoView, titleLabel, loginForm);

        // Créer la scène et l'afficher
        Scene scene = new Scene(mainContainer, 500, 650);
        primaryStage.setScene(scene);
        primaryStage.show();

        // Configurer les actions des boutons
        configureButtonActions(primaryStage);
    }

    private void configureButtonActions(Stage primaryStage) {
        // Action du bouton de connexion
        loginButton.setOnAction(e -> {
            String username = usernameField.getText().trim();
            String password = passwordField.getText();

            // Validation basique
            if (username.isEmpty() || password.isEmpty()) {
                statusLabel.setText("Veuillez remplir tous les champs");
                return;
            }

            // Désactiver les contrôles pendant la vérification
            setControlsDisabled(true);
            statusLabel.setText("Connexion en cours...");
            statusLabel.setTextFill(Color.BLUE);

            // Vérifier les identifiants dans la base de données
            Task<Boolean> loginTask = new Task<Boolean>() {
                @Override
                protected Boolean call() throws Exception {
                    return checkCredentials(username, password);
                }
            };

            loginTask.setOnSucceeded(event -> {
                if (loginTask.getValue()) {
                    // Connexion réussie
                    startConversationService(primaryStage, username, password);
                } else {
                    // Identifiants incorrects
                    statusLabel.setText("Nom d'utilisateur ou mot de passe incorrect");
                    statusLabel.setTextFill(Color.RED);
                    setControlsDisabled(false);
                }
            });

            loginTask.setOnFailed(event -> {
                statusLabel.setText("Erreur de connexion: " + loginTask.getException().getMessage());
                System.out.println(loginTask.getException().getMessage());
                statusLabel.setTextFill(Color.RED);
                setControlsDisabled(false);
            });

            new Thread(loginTask).start();
        });

        // Action du bouton d'inscription
        registerButton.setOnAction(e -> {
            openRegistrationDialog(primaryStage);
        });
    }

    private boolean checkCredentials(String username, String password) {
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            String query = "SELECT * FROM Users WHERE userName = ? AND CodeAcess = ?";
            PreparedStatement stmt = conn.prepareStatement(query);
            stmt.setString(1, username);
            stmt.setString(2, password); // Note: Dans une application réelle, utilisez un hachage sécurisé

            ResultSet rs = stmt.executeQuery();
            return rs.next(); // Retourne true si un utilisateur correspondant est trouvé
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private void registerUser(String username, String password, String phoneNumber, File profilePictureFile) {
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            // Vérifier d'abord si l'utilisateur existe déjà
            String checkQuery = "SELECT COUNT(*) FROM Users WHERE userName = ?";
            PreparedStatement checkStmt = conn.prepareStatement(checkQuery);
            checkStmt.setString(1, username);
            ResultSet rs = checkStmt.executeQuery();
            rs.next();
            int count = rs.getInt(1);

            if (count > 0) {
                throw new Exception("Ce nom d'utilisateur est déjà pris");
            }

            // Lire les données binaires de la photo
            byte[] profilePictureData = null;
            if (profilePictureFile != null) {
                try (FileInputStream fis = new FileInputStream(profilePictureFile)) {
                    profilePictureData = new byte[(int) profilePictureFile.length()];
                    fis.read(profilePictureData);
                }
            }

            // Insérer le nouvel utilisateur
            String insertQuery = "INSERT INTO Users (userName, CodeAcess, phoneNumber, profilPicture) VALUES (?, ?, ?, ?)";
            PreparedStatement insertStmt = conn.prepareStatement(insertQuery);
            insertStmt.setString(1, username);
            insertStmt.setString(2, password); // Dans une vraie application, hachez le mot de passe
            insertStmt.setString(3, phoneNumber);
            if (profilePictureData != null) {
                insertStmt.setBytes(4, profilePictureData);
            } else {
                insertStmt.setNull(4, java.sql.Types.BLOB);
            }

            insertStmt.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    private void startConversationService(Stage primaryStage, String username, String password) {
        Task<ConversationService> task = new Task<ConversationService>() {
            @Override
            protected ConversationService call() throws Exception {
                try {
                    Platform.runLater(() -> {
                        try {
                            ConversationService conversationService = new ConversationService(username, password, DEFAULT_HOST, DEFAULT_PORT, primaryStage);
                            conversationService.show();
                        } catch (Exception e) {
                            System.err.println("Erreur : " + e.getMessage());
                            e.printStackTrace();
                            Alert alert = new Alert(Alert.AlertType.ERROR);
                            alert.setTitle("Erreur");
                            alert.setHeaderText("Impossible de lancer la conversation");
                            alert.setContentText("Détails : " + e.getMessage());
                            alert.showAndWait();
                        }
                    });
                } catch (Exception e) {
                    System.err.println("Erreur lors de la création du service de conversation : " + e.getMessage());
                    throw e;
                }
                return null;
            }
        };

        new Thread(task).start();
    }

    private void setControlsDisabled(boolean disabled) {
        usernameField.setDisable(disabled);
        passwordField.setDisable(disabled);
        loginButton.setDisable(disabled);
        registerButton.setDisable(disabled);
    }

    private void openRegistrationDialog(Stage owner) {
        // Création d'une nouvelle fenêtre de dialogue
        Stage dialogStage = new Stage();
        dialogStage.initOwner(owner);
        dialogStage.setTitle("Inscription");
        dialogStage.initStyle(StageStyle.UNDECORATED);

        // Conteneur principal
        VBox dialogVbox = new VBox(15);
        dialogVbox.setPadding(new Insets(25));
        dialogVbox.setStyle("-fx-background-color: white; -fx-border-color: #00967d; -fx-border-width: 2px;");

        // Titre
        Label titleLabel = new Label("Créer un compte");
        titleLabel.setFont(Font.font("Arial", FontWeight.BOLD, 18));

        // Champs de formulaire
        TextField usernameRegField = new TextField();
        usernameRegField.setPromptText("Nom d'utilisateur");
        usernameRegField.setPrefHeight(40);

        PasswordField passwordRegField = new PasswordField();
        passwordRegField.setPromptText("Mot de passe");
        passwordRegField.setPrefHeight(40);

        PasswordField confirmPasswordField = new PasswordField();
        confirmPasswordField.setPromptText("Confirmer le mot de passe");
        confirmPasswordField.setPrefHeight(40);

        TextField phoneNumberField = new TextField();
        phoneNumberField.setPromptText("Numéro de téléphone");
        phoneNumberField.setPrefHeight(40);

        // Sélection de la photo de profil
        ImageView profilePictureView = new ImageView();
        profilePictureView.setFitHeight(100);
        profilePictureView.setFitWidth(100);
        profilePictureView.setPreserveRatio(true);

        Button choosePhotoButton = new Button("Choisir une photo");
        choosePhotoButton.setPrefSize(150, 40);
        choosePhotoButton.setStyle("-fx-background-color: #00967d; -fx-text-fill: white; -fx-background-radius: 5px;");
        final File[] selectedPhotoFile = {null};

        choosePhotoButton.setOnAction(e -> {
            FileChooser fileChooser = new FileChooser();
            fileChooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.gif")
            );
            File file = fileChooser.showOpenDialog(dialogStage);
            if (file != null) {
                selectedPhotoFile[0] = file;
                profilePictureView.setImage(new Image(file.toURI().toString()));
            }
        });

        // Message d'état
        Label regStatusLabel = new Label("");
        regStatusLabel.setTextFill(Color.RED);

        // Boutons
        HBox buttonBox = new HBox(10);
        buttonBox.setAlignment(Pos.CENTER);

        Button registerConfirmButton = new Button("S'inscrire");
        registerConfirmButton.setPrefSize(120, 40);
        registerConfirmButton.setStyle("-fx-background-color: #00967d; -fx-text-fill: white;");

        Button cancelButton = new Button("Annuler");
        cancelButton.setPrefSize(120, 40);
        cancelButton.setStyle("-fx-background-color: #787878; -fx-text-fill: white;");

        buttonBox.getChildren().addAll(registerConfirmButton, cancelButton);

        // Ajouter tous les composants
        dialogVbox.getChildren().addAll(
                titleLabel,
                usernameRegField,
                passwordRegField,
                confirmPasswordField,
                phoneNumberField,
                new HBox(10, new Label("Photo de profil:"), profilePictureView, choosePhotoButton),
                regStatusLabel,
                buttonBox
        );

        // Configuration des actions des boutons
        cancelButton.setOnAction(e -> dialogStage.close());

        registerConfirmButton.setOnAction(e -> {
            String username = usernameRegField.getText().trim();
            String password = passwordRegField.getText();
            String confirmPassword = confirmPasswordField.getText();
            String phoneNumber = phoneNumberField.getText().trim();

            // Validation
            if (username.isEmpty() || password.isEmpty() || phoneNumber.isEmpty()) {
                regStatusLabel.setText("Veuillez remplir tous les champs");
                return;
            }

            if (!password.equals(confirmPassword)) {
                regStatusLabel.setText("Les mots de passe ne correspondent pas");
                return;
            }

            try {
                registerUser(username, password, phoneNumber, selectedPhotoFile[0]);
                regStatusLabel.setText("Inscription réussie!");
                regStatusLabel.setTextFill(Color.GREEN);

                // Fermer la fenêtre d'inscription après un court délai
                new Thread(() -> {
                    try {
                        Thread.sleep(1500);
                        javafx.application.Platform.runLater(() -> dialogStage.close());
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }).start();
            } catch (Exception ex) {
                System.out.println("Erreur: " + ex.getMessage());
            }
        });

        Scene dialogScene = new Scene(dialogVbox, 400, 500);
        dialogStage.setScene(dialogScene);
        dialogStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}