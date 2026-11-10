package kakha.kudava.onionp2p;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

/** Live recording waveform or saved recording with a playback cursor. */
final class WaveformView extends Region {
    private static final int BAR_COUNT = 38;
    private static final Color LIVE_BAR = Color.web("#aeb7c2");
    private static final Color PLAYBACK_BAR = Color.web("#9cb2ca");
    private static final Color PLAYED_BAR = Color.web("#dbeafe");
    private static final Color PLAYHEAD = Color.web("#82c5ff");
    private final double[] levels = new double[BAR_COUNT];
    private final Canvas canvas = new Canvas();
    private final boolean playback;
    private final DoubleProperty progress = new SimpleDoubleProperty();
    private double[] previewLevels = levels;
    private int cursor;

    WaveformView() { this(false); }

    WaveformView(boolean playback) {
        this.playback = playback;
        getChildren().add(canvas);
        setMinWidth(75);
        setPrefWidth(playback ? 190 : 220);
        setMinHeight(playback ? 28 : 38);
        setPrefHeight(playback ? 28 : 38);
        if (playback) {
            setMaxHeight(28);
            progress.addListener(ignored -> draw());
        }
    }

    DoubleProperty progressProperty() { return progress; }

    void setPreview(double[] levels) {
        previewLevels = levels;
        draw();
    }

    void addLevel(double level) {
        levels[cursor] = Math.max(0, Math.min(1, level));
        cursor = (cursor + 1) % levels.length;
        draw();
    }

    void clear() {
        java.util.Arrays.fill(levels, 0);
        cursor = 0;
        draw();
    }

    @Override protected void layoutChildren() {
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
        draw();
    }

    private void draw() {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        if (width <= 0 || height <= 0) return;
        GraphicsContext graphics = canvas.getGraphicsContext2D();
        graphics.clearRect(0, 0, width, height);
        double[] bars = playback ? previewLevels : levels;
        double inset = playback ? 5 : 0;
        double step = (width - inset * 2) / bars.length;
        double playhead = inset + progress.get() * (width - inset * 2);
        double barWidth = Math.max(1.5, Math.min(3.0, step * 0.46));
        graphics.setFill(playback ? PLAYBACK_BAR : LIVE_BAR);
        for (int i = 0; i < bars.length; i++) {
            double level = bars[(cursor + i) % bars.length];
            double barHeight = 3 + level * (height - 5);
            double x = inset + i * step + (step - barWidth) / 2;
            if (playback) graphics.setFill(x < playhead ? PLAYED_BAR : PLAYBACK_BAR);
            graphics.fillRoundRect(x, (height - barHeight) / 2, barWidth, barHeight, barWidth, barWidth);
        }
        if (playback) {
            graphics.setFill(PLAYHEAD);
            graphics.fillOval(playhead - 4, height / 2 - 4, 8, 8);
        }
    }
}
