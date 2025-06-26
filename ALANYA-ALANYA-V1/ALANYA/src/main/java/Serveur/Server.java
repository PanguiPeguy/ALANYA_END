package Serveur;

import Serveur.service.ServerConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Scanner;

public class Server {
    private static final Logger logger = LoggerFactory.getLogger(Server.class);
    private static final int DEFAULT_PORT = 8080;

    public static void main(String[] args) {
        int port = DEFAULT_PORT;

        // Permettre de spécifier un port différent en argument
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                logger.error("Invalid port format. Using default port: {}", DEFAULT_PORT);
            }
        }

        ServerConnection server = null;
        try {
            server = new ServerConnection(port);
            server.start();
            logger.info("Server started on port {}. Type 'exit' to stop.", port);

            // Attendre la commande "exit" pour arrêter proprement le serveur
            Scanner scanner = new Scanner(System.in);
            while (true) {
                String command = scanner.nextLine();
                if ("exit".equalsIgnoreCase(command)) {
                    server.close();
                    break;
                }
            }

            scanner.close();
        } catch (Exception e) {
            logger.error("Error starting server: {}", e.getMessage(), e);
        }
    }
}