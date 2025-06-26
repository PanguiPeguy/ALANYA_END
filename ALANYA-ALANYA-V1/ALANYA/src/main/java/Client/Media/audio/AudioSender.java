package Client.Media.audio;

import java.io.OutputStream;
import java.net.Socket;
import javax.sound.sampled.TargetDataLine;

import Client.Media.audio.AudioSetup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AudioSender extends Thread {
    private static final Logger logger = LoggerFactory.getLogger(AudioSender.class);
    private final String ip;
    private final int port;
    private final AudioSetup audioSetup;
    private volatile boolean running = true;

    public AudioSender(String ip, int port, AudioSetup audioSetup) {
        this.ip = ip;
        this.port = port;
        this.audioSetup = audioSetup;
        setDaemon(true); // S'assurer que le thread ne bloque pas la fermeture de l'application
    }

    @Override
    public void run() {
        if (!running) return;

        Socket socket = null;
        OutputStream out = null;
        try {
            socket = new Socket();
            socket.connect(new java.net.InetSocketAddress(ip, port), 5000); // Timeout de connexion
            socket.setSoTimeout(10000); // Timeout pour les opérations de lecture/écriture
            out = socket.getOutputStream();

            logger.info("Connexion établie pour l'envoi audio vers {}:{}", ip, port);

            audioSetup.openMicrophone();
            TargetDataLine mic = audioSetup.getMicrophone();

            byte[] buffer = new byte[4096];
            while (running && !isInterrupted()) {
                int count = mic.read(buffer, 0, buffer.length);
                if (count > 0) {
                    out.write(buffer, 0, count);
                    out.flush(); // Assurer que les données sont envoyées immédiatement
                }
            }
        } catch (Exception e) {
            if (running) {
                logger.error("Erreur lors de l'envoi audio : {}", e.getMessage());
            } else {
                logger.info("Envoi audio arrêté");
            }
        } finally {
            audioSetup.closeMicrophone();
            logger.info("Microphone fermé");
            try {
                if (out != null) out.close();
                if (socket != null && !socket.isClosed()) socket.close();
            } catch (Exception e) {
                logger.error("Erreur lors de la fermeture des ressources : {}", e.getMessage());
            }
        }
    }

    public void stopSending() {
        running = false;
        interrupt();
        logger.info("Demande d'arrêt de l'envoi audio");
    }
}