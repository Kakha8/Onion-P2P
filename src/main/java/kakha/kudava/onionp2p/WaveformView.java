package kakha.kudava.onionp2p;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

/** Compact scrolling live-amplitude waveform for voice recording. */
final class WaveformView extends Region {
    private static final int BAR_COUNT = 38;
    private final double[] levels = new double[BAR_COUNT];
    private final Canvas canvas = new Canvas();
    private int cursor;

    WaveformView() {
        getChildren().add(canvas);
        setMinWidth(75);
        setPrefWidth(220);
        setMinHeight(38);
        setPrefHeight(38);
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

    @Override protected double computePrefWidth(double height) { return 220; }
    @Override protected double computePrefHeight(double width) { return 38; }

    private void draw() {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        if (width <= 0 || height <= 0) return;
        GraphicsContext graphics = canvas.getGraphicsContext2D();
        graphics.clearRect(0, 0, width, height);
        graphics.setFill(Color.web("#aeb7c2"));
        double step = width / levels.length;
        double barWidth = Math.max(1.5, Math.min(3.0, step * 0.46));
        for (int i = 0; i < levels.length; i++) {
            double level = levels[(cursor + i) % levels.length];
            double barHeight = 3 + level * (height - 5);
            double x = i * step + (step - barWidth) / 2;
            graphics.fillRoundRect(x, (height - barHeight) / 2, barWidth, barHeight, barWidth, barWidth);
        }
    }
}
