package Client.Media.video;

import javax.imageio.ImageIO;
import javax.sound.sampled.*;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import com.github.sarxos.webcam.Webcam;

import java.awt.Dimension;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class VideoCall implements Runnable {
    private JLabel display_label;
    private JLabel recv_label;
    private JFrame frame;
    private int audioPort;
    private int videoPort;
    private DatagramSocket audioSocket;
    private DatagramSocket videoSocket;
    private InetAddress serverIP;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private Sending send;

    // Optimisations avec des threads pools
    private ThreadPoolExecutor videoExecutor;
    private ThreadPoolExecutor audioExecutor;
    private ThreadPoolExecutor displayExecutor;

    // Buffer optimisé pour la réception
    private final byte[] receiveBuffer = new byte[30000];

    // Queue pour les images avec limite pour éviter l'accumulation
    private final BlockingQueue<BufferedImage> imageQueue = new LinkedBlockingQueue<>(5);
    private final AtomicReference<BufferedImage> latestRemoteImage = new AtomicReference<>();
    private final AtomicReference<BufferedImage> latestLocalImage = new AtomicReference<>();

    // Contrôle de framerate pour l'affichage
    private volatile long lastDisplayUpdate = 0;
    private static final int DISPLAY_FPS = 15; // 20 FPS pour l'affichage
    private static final long DISPLAY_INTERVAL = 1000 / DISPLAY_FPS;
    private boolean isMicMuted = false;
    private TargetDataLine microphone;

    public VideoCall(JLabel display_label, JLabel recv_label, JFrame frame, int audioPort, int videoPort,
                     DatagramSocket audioSocket, DatagramSocket videoSocket, InetAddress serverIP) {
        this.display_label = display_label;
        this.recv_label = recv_label;
        this.frame = frame;
        this.audioPort = audioPort;
        this.videoPort = videoPort;
        this.audioSocket = audioSocket;
        this.videoSocket = videoSocket;
        this.serverIP = serverIP;

        // Initialisation des thread pools optimisés
        videoExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "VideoReceiver");
            t.setPriority(Thread.NORM_PRIORITY + 1);
            return t;
        });

        audioExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), r -> new Thread(r, "AudioReceiver"));

        displayExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), r -> new Thread(r, "DisplayUpdater"));

        // Configuration des labels
        this.display_label.setPreferredSize(new Dimension(200, 150));
        this.recv_label.setPreferredSize(new Dimension(760, 500));
        this.recv_label.setOpaque(true);
        this.recv_label.setBackground(java.awt.Color.BLACK);
        this.recv_label.setText("Connexion en cours...");
        this.recv_label.setForeground(java.awt.Color.WHITE);
        this.recv_label.setHorizontalAlignment(JLabel.CENTER);
    }

    @Override
    public void run() {
        try {
            // Démarrer l'envoi
            send = new Sending(audioPort, videoPort, serverIP, audioSocket, videoSocket);
            send.start();
            System.out.println("Envoi démarré vers " + serverIP.getHostAddress());

            // Démarrer les récepteurs avec les thread pools
            videoExecutor.execute(new ReceiveVideoOptimized());
            audioExecutor.execute(new ReceiveAudioOptimized());
            displayExecutor.execute(new DisplayOptimized());

            System.out.println("Tous les threads de réception ont été démarrés");

        } catch (Exception e) {
            System.out.println("Erreur lors du démarrage de VideoCall: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void stop() {
        System.out.println("Arrêt de VideoCall...");
        running.set(false);

        // Arrêter l'envoi
        if (send != null) {
            send.stop();
        }

        // Arrêter les thread pools
        shutdownExecutor(videoExecutor, "Video");
        shutdownExecutor(audioExecutor, "Audio");
        shutdownExecutor(displayExecutor, "Display");

        // Fermer les sockets
        if (audioSocket != null && !audioSocket.isClosed()) {
            audioSocket.close();
        }
        if (videoSocket != null && !videoSocket.isClosed()) {
            videoSocket.close();
        }

        // Nettoyer les ressources
        imageQueue.clear();
        latestRemoteImage.set(null);
        latestLocalImage.set(null);

        System.out.println("VideoCall arrêté proprement");
    }

    private void shutdownExecutor(ThreadPoolExecutor executor, String name) {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
                System.out.println("Executor " + name + " arrêté");
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    public void setMicMuted(boolean muted) {
        isMicMuted = muted;
        if (microphone != null) {
            if (muted) {
                microphone.stop();
            } else {
                microphone.start();
            }
        }
    }

    private class ReceiveVideoOptimized implements Runnable {
        @Override
        public void run() {
            System.out.println("Réception vidéo optimisée démarrée sur le port " + videoPort);

            DatagramPacket packet = new DatagramPacket(receiveBuffer, receiveBuffer.length);
            int frameCount = 0;
            long lastStatsTime = System.currentTimeMillis();

            try {
                videoSocket.setSoTimeout(500); // Timeout pour éviter les blocages

                while (running.get()) {
                    try {
                        packet.setLength(receiveBuffer.length);
                        videoSocket.receive(packet);

                        // Vérifier l'expéditeur
                        if (!packet.getAddress().getHostAddress().equals(serverIP.getHostAddress())) {
                            continue;
                        }

                        if (packet.getLength() > 0) {
                            // Traitement asynchrone de l'image
                            byte[] imageData = new byte[packet.getLength()];
                            System.arraycopy(packet.getData(), packet.getOffset(), imageData, 0, packet.getLength());

                            BufferedImage image = decodeImageFast(imageData);
                            if (image != null) {
                                // Mise à jour atomique de la dernière image
                                latestRemoteImage.set(image);
                                frameCount++;
                            }
                        }

                        // Stats périodiques
                        long now = System.currentTimeMillis();
                        if (now - lastStatsTime > 5000) {
                            System.out.println("Réception vidéo: " + (frameCount / 5.0) + " FPS");
                            frameCount = 0;
                            lastStatsTime = now;
                        }

                    } catch (java.net.SocketTimeoutException e) {
                        // Timeout normal, continuer
                    } catch (Exception e) {
                        if (running.get()) {
                            System.out.println("Erreur réception vidéo: " + e.getMessage());
                            Thread.sleep(100);
                        }
                    }
                }
            } catch (Exception e) {
                if (running.get()) {
                    System.out.println("Erreur critique réception vidéo: " + e.getMessage());
                }
            }
            System.out.println("Réception vidéo terminée");
        }

        private BufferedImage decodeImageFast(byte[] data) {
            try {
                return ImageIO.read(new ByteArrayInputStream(data));
            } catch (Exception e) {
                return null;
            }
        }
    }

    private class ReceiveAudioOptimized implements Runnable {
        @Override
        public void run() {
            System.out.println("Réception audio optimisée démarrée sur le port " + audioPort);

            try {
                // Format audio optimisé (même que l'envoi)
                AudioFormat format = new AudioFormat(44100.0f, 16, 1, true, false);
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
                SourceDataLine sourceLine = (SourceDataLine) AudioSystem.getLine(info);
                sourceLine.open(format, 8192); // Buffer plus grand
                sourceLine.start();

                byte[] buffer = new byte[1024];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                audioSocket.setSoTimeout(500);

                while (running.get()) {
                    try {
                        packet.setLength(buffer.length);
                        audioSocket.receive(packet);

                        if (packet.getLength() > 0) {
                            // Écriture directe sans copie supplémentaire
                            sourceLine.write(packet.getData(), packet.getOffset(), packet.getLength());
                        }
                    } catch (java.net.SocketTimeoutException e) {
                        // Timeout normal
                    } catch (Exception e) {
                        if (running.get()) {
                            System.out.println("Erreur réception audio: " + e.getMessage());
                        }
                    }
                }

                sourceLine.drain();
                sourceLine.close();
                System.out.println("Réception audio terminée");
            } catch (Exception e) {
                if (running.get()) {
                    System.out.println("Erreur critique réception audio: " + e.getMessage());
                }
            }
        }
    }

    private class DisplayOptimized implements Runnable {
        private Webcam localWebcam;

        @Override
        public void run() {
            System.out.println("Affichage optimisé démarré");

            try {
                // Initialiser la webcam locale pour l'affichage
                initLocalWebcam();

                while (running.get()) {
                    long currentTime = System.currentTimeMillis();

                    // Contrôle de framerate
                    if (currentTime - lastDisplayUpdate < DISPLAY_INTERVAL) {
                        Thread.sleep(10);
                        continue;
                    }

                    // Mise à jour de l'affichage distant
                    updateRemoteDisplay();

                    // Mise à jour de l'affichage local
                    updateLocalDisplay();

                    lastDisplayUpdate = currentTime;
                }
            } catch (Exception e) {
                if (running.get()) {
                    System.out.println("Erreur dans l'affichage: " + e.getMessage());
                }
            } finally {
                if (localWebcam != null && localWebcam.isOpen()) {
                    localWebcam.close();
                }
            }
            System.out.println("Affichage terminé");
        }

        private void initLocalWebcam() {
            try {
                localWebcam = Webcam.getDefault();
                if (localWebcam != null && !localWebcam.isOpen()) {
                    localWebcam.setViewSize(new Dimension(320, 240));
                    localWebcam.open(true);
                }
            } catch (Exception e) {
                System.out.println("Erreur d'initialisation webcam locale: " + e.getMessage());
            }
        }

        private void updateRemoteDisplay() {
            BufferedImage remoteImage = latestRemoteImage.get();
            if (remoteImage != null) {
                javax.swing.SwingUtilities.invokeLater(() -> {
                    try {
                        int width = Math.max(recv_label.getWidth(), 640);
                        int height = Math.max(recv_label.getHeight(), 480);

                        Image scaledImage = remoteImage.getScaledInstance(width, height, Image.SCALE_FAST);
                        recv_label.setText(null);
                        recv_label.setIcon(new ImageIcon(scaledImage));
                        recv_label.revalidate();
                    } catch (Exception e) {
                        System.out.println("Erreur affichage distant: " + e.getMessage());
                    }
                });
            }
        }


        private void updateLocalDisplay() {
            if (localWebcam != null && localWebcam.isOpen()) {
                try {
                    BufferedImage localImage = localWebcam.getImage();
                    if (localImage != null) {
                        javax.swing.SwingUtilities.invokeLater(() -> {
                            try {
                                int width = Math.max(display_label.getWidth(), 200);
                                int height = Math.max(display_label.getHeight(), 150);

                                Image scaledImage = localImage.getScaledInstance(width, height, Image.SCALE_FAST);
                                display_label.setIcon(new ImageIcon(scaledImage));
                                display_label.revalidate();
                            } catch (Exception e) {
                                System.out.println("Erreur affichage local: " + e.getMessage());
                            }
                        });
                    }
                } catch (Exception e) {
                    System.out.println("Erreur capture locale: " + e.getMessage());
                }
            }
        }
    }
}