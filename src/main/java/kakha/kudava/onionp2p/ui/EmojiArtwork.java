package kakha.kudava.onionp2p.ui;

import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipInputStream;

/** Shared artwork for the picker, composer, and chat history. */
final class EmojiArtwork {
    static final Pattern GRAPHEME = Pattern.compile("\\X");

    record Span(int start, int end, Image image) {}

    private static final class Archive {
        private static final Map<String, Image> IMAGES = load();
    }

    private EmojiArtwork() {}

    static Image imageFor(String symbol) {
        String key = symbol.contains("\u200d") ? symbol : symbol.replace("\ufe0f", "");
        String file = key.codePoints().mapToObj(Integer::toHexString).collect(Collectors.joining("-")) + ".png";
        return Archive.IMAGES.get(file);
    }

    static List<Span> findIn(String text) {
        if (text == null || text.chars().allMatch(character -> character < 128)) return List.of();
        var matcher = GRAPHEME.matcher(text);
        List<Span> emojis = new ArrayList<>();
        while (matcher.find()) {
            if (matcher.end() - matcher.start() == 1 && text.charAt(matcher.start()) < 128) continue;
            Image image = imageFor(matcher.group());
            if (image != null) emojis.add(new Span(matcher.start(), matcher.end(), image));
        }
        return emojis;
    }

    static ImageView icon(Image image, double size) {
        ImageView view = new ImageView(image);
        view.setFitWidth(size);
        view.setFitHeight(size);
        view.setPreserveRatio(true);
        return view;
    }

    private static Map<String, Image> load() {
        Map<String, Image> images = new HashMap<>();
        // Keep the artwork archive at the shared resource root. This class moved into
        // the UI package, so a package-relative lookup would silently miss the archive.
        try (var stream = EmojiArtwork.class.getResourceAsStream("/kakha/kudava/onionp2p/emoji-artwork.zip")) {
            if (stream == null) return images;
            try (var archive = new ZipInputStream(stream)) {
                for (var entry = archive.getNextEntry(); entry != null; entry = archive.getNextEntry()) {
                    if (!entry.getName().endsWith(".png")) continue;
                    Image image = new Image(new ByteArrayInputStream(archive.readAllBytes()));
                    if (!image.isError()) images.put(entry.getName(), image);
                }
            }
        } catch (IOException e) {
            System.err.println("Could not load emoji artwork: " + e.getMessage());
        }
        return images;
    }
}
