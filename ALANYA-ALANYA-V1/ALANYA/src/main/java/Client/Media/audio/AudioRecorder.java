package Client.Media.audio;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class AudioRecorder {
    private TargetDataLine line;
    private ByteArrayOutputStream out;
    private boolean isRecording = false;
    private final AudioFormat format;

    public AudioRecorder() {
        format = new AudioFormat(44100.0f, 16, 1, true, false); // Mono, 8kHz, 16-bit
    }

    public boolean isRecording() {
        return isRecording;
    }

    public boolean startRecording() {
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            if (!AudioSystem.isLineSupported(info)) {
                System.err.println("Ligne audio non supportée pour le format : " + format);
                return false;
            }

            line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(format);
            line.start();

            out = new ByteArrayOutputStream();
            isRecording = true;

            new Thread(() -> {
                byte[] buffer = new byte[1024];
                int totalBytesRead = 0;
                while (isRecording) {
                    int count = line.read(buffer, 0, buffer.length);
                    if (count > 0) {
                        out.write(buffer, 0, count);
                        totalBytesRead += count;
                        System.out.println("Enregistrement en cours, octets lus : " + totalBytesRead);
                    }
                }
            }).start();

            System.out.println("Enregistrement démarré avec le format : " + format);
            return true;
        } catch (LineUnavailableException e) {
            System.err.println("Erreur lors du démarrage de l'enregistrement : " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public byte[] stopRecording() {
        if (!isRecording || line == null) {
            System.err.println("Aucun enregistrement en cours.");
            return new byte[0];
        }

        isRecording = false;
        line.stop();
        line.close();

        byte[] audioData = out.toByteArray();
        try {
            out.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        System.out.println("Enregistrement arrêté. Taille des données audio : " + audioData.length + " octets.");
        return audioData;
    }

    public static void playAudio(byte[] audioData) {
        if (audioData == null || audioData.length == 0) {
            System.err.println("Aucune donnée audio à lire.");
            return;
        }

        System.out.println("Tentative de lecture audio, taille des données : " + audioData.length + " octets.");

        try {
            AudioFormat format = new AudioFormat(44100.0f, 16, 1, true, false);
            ByteArrayInputStream bais = new ByteArrayInputStream(audioData);
            AudioInputStream ais = new AudioInputStream(bais, format, audioData.length / format.getFrameSize());

            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            if (!AudioSystem.isLineSupported(info)) {
                System.err.println("Ligne de lecture audio non supportée pour le format : " + format);
                return;
            }

            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(format);
            line.start();

            byte[] buffer = new byte[1024];
            int count;
            int totalBytesWritten = 0;
            while ((count = ais.read(buffer, 0, buffer.length)) != -1) {
                line.write(buffer, 0, count);
                totalBytesWritten += count;
                System.out.println("Lecture en cours, octets écrits : " + totalBytesWritten);
            }

            line.drain();
            line.close();
            ais.close();
            bais.close();

            System.out.println("Lecture audio terminée.");
        } catch (LineUnavailableException | IOException e) {
            System.err.println("Erreur lors de la lecture audio : " + e.getMessage());
            e.printStackTrace();
        }
    }
}