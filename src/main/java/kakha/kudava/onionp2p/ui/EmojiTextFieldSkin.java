package kakha.kudava.onionp2p.ui;

import javafx.scene.canvas.Canvas;
import javafx.scene.control.TextField;
import javafx.scene.control.skin.TextFieldSkin;

import java.util.List;
import java.util.Objects;

/** Paints emoji artwork over the native editor's layout without changing the draft. */
final class EmojiTextFieldSkin extends TextFieldSkin {
    private final Canvas artwork = new Canvas();
    private String cachedText;
    private List<EmojiArtwork.Span> spans;

    EmojiTextFieldSkin(TextField field) {
        super(field);
        artwork.setManaged(false);
        artwork.setMouseTransparent(true);
        getChildren().add(artwork);
        registerChangeListener(field.textProperty(), ignored -> field.requestLayout());
        registerChangeListener(field.caretPositionProperty(), ignored -> {
            if (!spansFor(field.getText()).isEmpty()) field.requestLayout();
        });
    }

    private List<EmojiArtwork.Span> spansFor(String text) {
        if (spans == null || !Objects.equals(text, cachedText)) {
            spans = EmojiArtwork.findIn(text);
            cachedText = text;
        }
        return spans;
    }

    @Override
    protected String maskText(String text) {
        List<EmojiArtwork.Span> matches = spansFor(text);
        if (matches.isEmpty()) return text;

        StringBuilder display = new StringBuilder(text);
        for (var emoji : matches) {
            // Reserve one visible space while keeping the editor's UTF-16 offsets intact.
            display.setCharAt(emoji.start(), '\u2003');
            for (int i = emoji.start() + 1; i < emoji.end(); i++) display.setCharAt(i, '\u034f');
        }
        return display.toString();
    }

    @Override
    protected void layoutChildren(double x, double y, double width, double height) {
        super.layoutChildren(x, y, width, height);
        artwork.relocate(x, y);
        artwork.setWidth(Math.max(0, width));
        artwork.setHeight(Math.max(0, height));
        var graphics = artwork.getGraphicsContext2D();
        graphics.clearRect(0, 0, width, height);
        for (var emoji : spansFor(getSkinnable().getText())) {
            // The last code unit carries the shaped width of the placeholder cluster.
            var bounds = getCharacterBounds(emoji.end() - 1);
            double left = bounds.getMinX() - x;
            if (left + bounds.getWidth() <= 0 || left >= width) continue;
            double size = Math.max(0, Math.min(bounds.getWidth() - 2, height));
            double centerY = bounds.getMinY() - y + bounds.getHeight() / 2;
            graphics.drawImage(emoji.image(), left + (bounds.getWidth() - size) / 2, centerY - size / 2, size, size);
        }
    }
}
