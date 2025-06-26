package Client.Media.audio;

import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import javax.sound.sampled.SourceDataLine;

import Client.Media.audio.AudioSetup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AudioReceiver extends Thread {
    private static final Logger logger = LoggerFactory.getLogger(AudioReceiver.class);
    private final int port;
    private final AudioSetup audioSetup;
    private volatile boolean running = true;
    private ServerSocket server;

    public AudioReceiver(int port, AudioSetup audioSetup) {
        this.port = port;
        this.audioSetup = audioSetup;
        setDaemon(true);
    }

    @Override
    public void run() {
        if (!running) return;

        Socket socket = null;
        InputStream in = null;
        try {
            server = new ServerSocket(port);
            server.setSoTimeout(30000); // Timeout pour éviter un blocage prolongé
            logger.info("Serveur de réception audio démarré sur le port {}", port);

            socket = server.accept();
            socket.setSoTimeout(10000); // Timeout pour les opérations de lecture
            logger.info("Connexion entrante depuis {}", socket.getInetAddress().getHostAddress());

            in = socket.getInputStream();
            audioSetup.openSpeakers();
            SourceDataLine speakers = audioSetup.getSpeakers();

            byte[] buffer = new byte[4096];
            int count;
            while (running && (count = in.read(buffer)) > 0) {
                speakers.write(buffer, 0, count);
            }
        } catch (SocketException se) {
            if (!running) {
                logger.info("Socket fermé suite à l'arrêt du récepteur audio");
            } else {
                logger.error("Erreur de socket : {}", se.getMessage());
            }
        } catch (Exception e) {
            if (running) {
                logger.error("Erreur lors de la réception audio : {}", e.getMessage());
            } else {
                logger.info("Réception audio arrêtée");
            }
        } finally {
            audioSetup.closeSpeakers();
            logger.info("Haut-parleurs fermés");
            try {
                if (in != null) in.close();
                if (socket != null && !socket.isClosed()) socket.close();
                closeServer();
            } catch (Exception e) {
                logger.error("Erreur lors de la fermeture des ressources : {}", e.getMessage());
            }
        }
    }

    public void stopReceiving() {
        running = false;
        logger.info("Demande d'arrêt de la réception audio");
        closeServer();
        interrupt();
    }

    private void closeServer() {
        if (server != null && !server.isClosed()) {
            try {
                server.close();
                logger.info("Serveur de réception audio fermé");
            } catch (Exception e) {
                logger.error("Erreur lors de la fermeture du serveur : {}", e.getMessage());
            }
        }
    }
}