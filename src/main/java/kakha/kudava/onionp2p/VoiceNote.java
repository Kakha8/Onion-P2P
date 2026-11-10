package kakha.kudava.onionp2p;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Duration and amplitude summary of a saved voice recording. */
record VoiceNote(long seconds, double[] levels) {
    private static final int BAR_COUNT = 40;
    private static final long MAX_WAV_BYTES = 8L * 1024 * 1024;

    static VoiceNote read(Path path) throws IOException, UnsupportedAudioFileException {
        long fileSize = Files.size(path);
        if (fileSize > MAX_WAV_BYTES) throw new IOException("Voice recording is too large.");
        try (var audio = AudioSystem.getAudioInputStream(path.toFile())) {
            AudioFormat format = audio.getFormat();
            int channels = format.getChannels();
            int frameSize = format.getFrameSize();
            long frames = audio.getFrameLength();
            if (!AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding())
                    || format.getSampleSizeInBits() != 16 || channels < 1 || channels > 2
                    || frameSize != channels * 2 || format.getFrameRate() <= 0
                    || frames <= 0 || frames > fileSize / frameSize) {
                throw new IOException("Unsupported voice recording format.");
            }

            double[] levels = new double[BAR_COUNT];
            long[] samples = new long[BAR_COUNT];
            byte[] buffer = new byte[8192];
            long frame = 0;
            int count;
            while ((count = audio.read(buffer)) > 0) {
                for (int offset = 0; offset < count; offset += frameSize) {
                    int bar = (int) (frame++ * BAR_COUNT / frames);
                    for (int channel = 0; channel < channels; channel++) {
                        int sampleOffset = offset + channel * 2;
                        int high = format.isBigEndian() ? sampleOffset : sampleOffset + 1;
                        int low = format.isBigEndian() ? sampleOffset + 1 : sampleOffset;
                        short sample = (short) ((buffer[high] << 8) | (buffer[low] & 0xff));
                        double amplitude = sample / 32768.0;
                        levels[bar] += amplitude * amplitude;
                        samples[bar]++;
                    }
                }
            }
            if (frame != frames) throw new IOException("Voice recording is incomplete.");

            double peak = 0.06;
            for (int i = 0; i < levels.length; i++) {
                levels[i] = Math.sqrt(levels[i] / Math.max(1, samples[i]));
                peak = Math.max(peak, levels[i]);
            }
            for (int i = 0; i < levels.length; i++) {
                levels[i] = Math.pow(levels[i] / peak, 0.7);
            }
            return new VoiceNote(Math.round(frames / (double) format.getFrameRate()), levels);
        }
    }
}
