package Client.Media.video;

import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import com.github.sarxos.webcam.Webcam;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Iterator;

public class Sending {
    private int audioPort;
    private int videoPort;
    public Webcam webcam;
    public DatagramSocket audioSocket;
    public DatagramSocket videoSocket;
    public InetAddress server;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread sendVideoThread;
    private Thread sendAudioThread;

    // Optimisations pour la vidéo
    private final int MAX_PACKET_SIZE = 60000; // Taille maximale des paquets UDP
    private final int TARGET_FPS = 10; // FPS cible réduit pour de meilleures performances
    private final int FRAME_DELAY = 1000 / TARGET_FPS; // Délai entre les frames
    private final float JPEG_QUALITY = 0.3f; // Qualité JPEG réduite pour compression

    // Réutilisation des buffers pour éviter les allocations répétées
    private BufferedImage reusableImage;
    private Graphics2D reusableGraphics;

    public Sending(int audioPort, int videoPort, InetAddress server, DatagramSocket audioSocket, DatagramSocket videoSocket) {
        this.audioPort = audioPort;
        this.videoPort = videoPort;
        this.server = server;
        this.audioSocket = audioSocket;
        this.videoSocket = videoSocket;
    }

    public void start() {
        try {
            webcam = Webcam.getDefault();
            if (webcam == null) {
                System.out.println("Aucune webcam détectée");
                return;
            }

            // Résolution optimisée pour de meilleures performances
            webcam.setViewSize(new Dimension(320, 240));

            // Préparer l'image réutilisable
            reusableImage = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
            reusableGraphics = reusableImage.createGraphics();
            reusableGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            // Ouvrir la webcam avec retry
            boolean webcamOpened = false;
            for (int retries = 0; retries < 3 && !webcamOpened; retries++) {
                try {
                    webcam.open(true);
                    webcamOpened = true;
                    System.out.println("Webcam ouverte: " + webcam.getName());
                } catch (Exception e) {
                    System.out.println("Tentative " + (retries + 1) + " d'ouverture de la webcam a échoué: " + e.getMessage());
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            if (!webcamOpened) {
                System.out.println("Impossible d'ouvrir la webcam après plusieurs tentatives.");
                return;
            }

            // Démarrer les threads optimisés
            sendVideoThread = new Thread(new SendVideoOptimized(), "SendVideoThread");
            sendVideoThread.setPriority(Thread.NORM_PRIORITY + 1); // Priorité légèrement plus élevée
            sendVideoThread.start();

            sendAudioThread = new Thread(new SendAudioOptimized(), "SendAudioThread");
            sendAudioThread.start();

            System.out.println("Threads d'envoi démarrés avec optimisations");
        } catch (Exception e) {
            System.out.println("Erreur lors du démarrage de la capture: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void stop() {
        running.set(false);

        if (reusableGraphics != null) {
            reusableGraphics.dispose();
        }

        if (webcam != null && webcam.isOpen()) {
            try {
                webcam.close();
                System.out.println("Webcam fermée");
            } catch (Exception e) {
                System.out.println("Erreur lors de la fermeture de la webcam: " + e.getMessage());
            }
        }

        if (sendVideoThread != null) {
            sendVideoThread.interrupt();
        }

        if (sendAudioThread != null) {
            sendAudioThread.interrupt();
        }
    }

    private class SendVideoOptimized implements Runnable {
        @Override
        public void run() {
            System.out.println("Démarrage de l'envoi vidéo optimisé vers " + server.getHostAddress() + ":" + videoPort);

            long lastFrameTime = System.currentTimeMillis();
            int frameCount = 0;
            int skipFrames = 0;

            while (running.get()) {
                try {
                    long currentTime = System.currentTimeMillis();

                    // Contrôle de framerate précis
                    if (currentTime - lastFrameTime < FRAME_DELAY) {
                        continue;
                    }

                    if (webcam != null && webcam.isOpen()) {
                        BufferedImage image = webcam.getImage();
                        if (image != null) {
                            // Copier l'image dans le buffer réutilisable pour éviter les allocations
                            reusableGraphics.drawImage(image, 0, 0, null);

                            byte[] imageData = compressImageOptimized(reusableImage);

                            if (imageData != null && imageData.length > 0 && imageData.length <= MAX_PACKET_SIZE) {
                                DatagramPacket packet = new DatagramPacket(imageData, imageData.length, server, videoPort);

                                try {
                                    videoSocket.send(packet);
                                    frameCount++;
                                    lastFrameTime = currentTime;
                                } catch (Exception e) {
                                    System.out.println("Erreur d'envoi vidéo: " + e.getMessage());
                                    Thread.sleep(100); // Pause en cas d'erreur
                                }
                            } else if (imageData != null && imageData.length > MAX_PACKET_SIZE) {
                                // Image trop grande, on la skip
                                skipFrames++;
                                System.out.println("Image skippée (trop grande): " + imageData.length + " octets");
                            }

                            // Stats toutes les 5 secondes
                            if (currentTime - lastFrameTime > 5000 && frameCount > 0) {
                                double fps = frameCount / 5.0;
                                System.out.println("Envoi vidéo: " + String.format("%.1f", fps) + " FPS, " + skipFrames + " frames skippées");
                                frameCount = 0;
                                skipFrames = 0;
                            }
                        }
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    if (running.get()) {
                        System.out.println("Erreur dans SendVideo: " + e.getMessage());
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
            System.out.println("Thread d'envoi vidéo optimisé terminé");
        }
    }

    private byte[] compressImageOptimized(BufferedImage image) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();

            // Utiliser l'API d'écriture JPEG optimisée
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
            if (!writers.hasNext()) {
                return null;
            }

            ImageWriter writer = writers.next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);

            try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
                writer.setOutput(ios);
                writer.write(null, new javax.imageio.IIOImage(image, null, null), param);
            }

            writer.dispose();
            return baos.toByteArray();

        } catch (Exception e) {
            System.out.println("Erreur de compression: " + e.getMessage());
            return null;
        }
    }

    private class SendAudioOptimized implements Runnable {
        @Override
        public void run() {
            System.out.println("Démarrage de l'envoi audio optimisé vers " + server.getHostAddress() + ":" + audioPort);

            // Format audio optimisé pour de meilleures performances réseau
            AudioFormat format = new AudioFormat(44100.0f, 16, 1, true, false); // 22kHz au lieu de 44kHz
            TargetDataLine microphone = null;

            try {
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

                if (!AudioSystem.isLineSupported(info)) {
                    System.out.println("Format audio non supporté");
                    return;
                }

                microphone = (TargetDataLine) AudioSystem.getLine(info);
                microphone.open(format, 8192); // Buffer plus grand pour réduire la latence
                microphone.start();

                System.out.println("Capture audio optimisée démarrée");

                byte[] buffer = new byte[1024];
                int consecutiveErrors = 0;

                while (running.get()) {
                    try {
                        int bytesRead = microphone.read(buffer, 0, buffer.length);

                        if (bytesRead > 0) {
                            // Réutiliser le même buffer si possible
                            DatagramPacket packet = new DatagramPacket(buffer, bytesRead, server, audioPort);

                            try {
                                audioSocket.send(packet);
                                consecutiveErrors = 0;
                            } catch (Exception e) {
                                consecutiveErrors++;
                                if (consecutiveErrors > 5) {
                                    System.out.println("Erreurs audio consécutives: " + consecutiveErrors);
                                    Thread.sleep(1);
                                }
                            }
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        if (running.get()) {
                            System.out.println("Erreur audio: " + e.getMessage());
                            try {
                                Thread.sleep(1);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("Erreur d'initialisation audio: " + e.getMessage());
            } finally {
                if (microphone != null) {
                    microphone.stop();
                    microphone.close();
                    System.out.println("Capture audio arrêtée");
                }
            }
        }
    }
}