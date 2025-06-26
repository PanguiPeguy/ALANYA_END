package Client;

import javafx.application.Application;
import Client.service.LoginPage;

public class Client {
    public static void main(String[] args) {
        // Lancer l'application JavaFX
        Application.launch(LoginPage.class, args);
    }
}