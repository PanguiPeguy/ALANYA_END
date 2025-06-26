package Client.Media.audio;

import javax.sound.sampled.*;

public class AudioSetup {
    private final AudioFormat format;
    private TargetDataLine microphone;
    private SourceDataLine speakers;
    private boolean isMicMuted = false;

    public AudioSetup() {
        this.format = new AudioFormat(44100.0f, 16, 1, true, false);
    }

    public void openMicrophone() throws LineUnavailableException {
        DataLine.Info micInfo = new DataLine.Info(TargetDataLine.class, format);
        microphone = (TargetDataLine) AudioSystem.getLine(micInfo);
        microphone.open(format);
        microphone.start();
    }

    public void closeMicrophone() {
        if (microphone != null) {
            microphone.stop();
            microphone.close();
        }
    }

    public TargetDataLine getMicrophone() {
        return microphone;
    }

    public void openSpeakers() throws LineUnavailableException {
        DataLine.Info speakerInfo = new DataLine.Info(SourceDataLine.class, format);
        speakers = (SourceDataLine) AudioSystem.getLine(speakerInfo);
        speakers.open(format);
        speakers.start();
    }

    public void closeSpeakers() {
        if (speakers != null) {
            speakers.drain();
            speakers.stop();
            speakers.close();
        }
    }

    public SourceDataLine getSpeakers() {
        return speakers;
    }

    public AudioFormat getFormat() {
        return format;
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

    public boolean isMicMuted() {
        return isMicMuted;
    }
}