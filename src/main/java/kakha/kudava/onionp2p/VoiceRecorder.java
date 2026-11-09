package kakha.kudava.onionp2p;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import java.nio.file.Path;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;

/** Captures a compact mono WAV voice note using the system microphone. */
final class VoiceRecorder {
    private static final AudioFormat[] FORMATS = {
            new AudioFormat(16_000f, 16, 1, true, false),
            new AudioFormat(44_100f, 16, 1, true, false),
            new AudioFormat(48_000f, 16, 1, true, false),
            new AudioFormat(48_000f, 16, 2, true, false)
    };

    private final Path destination;
    private volatile TargetDataLine line;
    private volatile boolean stopRequested;

    VoiceRecorder(Path destination) { this.destination = destination; }

    void start(Runnable onStarted, Consumer<Path> onFinished, Consumer<Exception> onError) {
        Thread.ofPlatform().daemon().name("voice-recording").start(() -> {
            TargetDataLine capture = null;
            try {
                Exception lastError = null;
                for (AudioFormat format : FORMATS) {
                    TargetDataLine candidate = null;
                    try {
                        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
                        candidate = (TargetDataLine) AudioSystem.getLine(info);
                        candidate.open(format);
                        capture = candidate;
                        break;
                    } catch (Exception e) {
                        lastError = e;
                        if (candidate != null) candidate.close();
                    }
                }
                if (capture == null) throw new IllegalStateException("No compatible microphone format is available.", lastError);
                line = capture;
                capture.start();
                onStarted.run();
                ByteArrayOutputStream pcm = new ByteArrayOutputStream(32 * 1024);
                byte[] buffer = new byte[8 * 1024];
                while (!stopRequested) {
                    try {
                        int count = capture.read(buffer, 0, buffer.length);
                        if (count < 0) break;
                        if (count > 0) pcm.write(buffer, 0, count);
                    } catch (Exception e) {
                        if (!stopRequested) throw e;
                    }
                }
                if (pcm.size() == 0) throw new IllegalStateException("No audio was captured.");
                byte[] audioBytes = pcm.toByteArray();
                AudioFormat captureFormat = capture.getFormat();
                try (AudioInputStream audio = new AudioInputStream(new ByteArrayInputStream(audioBytes),
                        captureFormat, audioBytes.length / captureFormat.getFrameSize())) {
                    AudioSystem.write(audio, AudioFileFormat.Type.WAVE, destination.toFile());
                } finally {
                    java.util.Arrays.fill(audioBytes, (byte) 0);
                    java.util.Arrays.fill(buffer, (byte) 0);
                    pcm.reset();
                }
                onFinished.accept(destination);
            } catch (Exception e) {
                onError.accept(e);
            } finally {
                line = null;
                if (capture != null) {
                    try { capture.stop(); } catch (Exception ignored) {}
                    capture.close();
                }
            }
        });
    }

    void stop() {
        stopRequested = true;
        TargetDataLine capture = line;
        if (capture != null) {
            try { capture.stop(); } catch (Exception ignored) {}
            capture.close();
        }
    }
}
