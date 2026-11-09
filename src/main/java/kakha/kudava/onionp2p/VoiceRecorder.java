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
import java.util.function.DoubleConsumer;
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
    private volatile boolean paused;
    private volatile boolean finishRequested;
    private volatile boolean cancelRequested;

    VoiceRecorder(Path destination) { this.destination = destination; }

    void start(Runnable onStarted, DoubleConsumer onLevel, Consumer<Path> onFinished,
               Consumer<Exception> onError, Runnable onCancelled) {
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
                long lastLevelUpdate = 0;
                while (true) {
                    if (cancelRequested) break;
                    if (paused && !finishRequested) {
                        Thread.sleep(20);
                        continue;
                    }
                    int count;
                    try {
                        if (!paused) capture.start();
                        int available = capture.available();
                        int bytes = Math.min(buffer.length, available);
                        bytes -= bytes % capture.getFormat().getFrameSize();
                        if (bytes <= 0) {
                            if (finishRequested) break;
                            Thread.sleep(10);
                            continue;
                        }
                        count = capture.read(buffer, 0, bytes);
                    } catch (Exception e) {
                        if (cancelRequested) break;
                        throw e;
                    }
                    if (count <= 0) continue;
                    pcm.write(buffer, 0, count);
                    long now = System.nanoTime();
                    if (now - lastLevelUpdate >= 80_000_000L) {
                        onLevel.accept(level(buffer, count));
                        lastLevelUpdate = now;
                    }
                }
                if (cancelRequested) { onCancelled.run(); return; }
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
        finishRequested = true;
        paused = false;
        TargetDataLine capture = line;
        if (capture != null) try { capture.start(); } catch (Exception ignored) {}
    }

    void pause() {
        TargetDataLine capture = line;
        if (capture != null && !finishRequested && !cancelRequested) {
            paused = true;
            capture.stop();
        }
    }

    void resume() {
        TargetDataLine capture = line;
        if (capture != null && paused && !finishRequested && !cancelRequested) {
            paused = false;
            capture.start();
        }
    }

    boolean isPaused() { return paused; }

    void cancel() {
        cancelRequested = true;
        TargetDataLine capture = line;
        if (capture != null) capture.close();
    }

    private static double level(byte[] bytes, int length) {
        if (length < 2) return 0;
        double sum = 0;
        int samples = 0;
        for (int i = 0; i + 1 < length; i += 2) {
            short sample = (short) ((bytes[i] & 0xff) | (bytes[i + 1] << 8));
            double normalized = sample / 32768.0;
            sum += normalized * normalized;
            samples++;
        }
        double rms = samples == 0 ? 0 : Math.sqrt(sum / samples);
        return Math.min(1, Math.max(0, (rms - 0.006) * 14));
    }
}
