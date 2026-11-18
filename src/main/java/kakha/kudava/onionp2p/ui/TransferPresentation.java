package kakha.kudava.onionp2p.ui;

import javafx.css.PseudoClass;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.shape.SVGPath;
import java.util.Locale;

final class TransferPresentation {
    static void fitList(javafx.scene.control.ScrollPane scroll, javafx.scene.layout.VBox rows, double width) {
        rows.applyCss();
        scroll.setPrefHeight(Math.min(220, Math.max(48, rows.prefHeight(Math.max(100, width - 20)))));
    }
    private static final PseudoClass CANCELLED = PseudoClass.getPseudoClass("cancelled");
    static void queued(Region row, Label status, boolean queued) {
        row.pseudoClassStateChanged(PseudoClass.getPseudoClass("queued"), queued);
        if (queued) {
            var icon = new SVGPath(); icon.setContent("M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0 M12 7v5l3 2");
            icon.getStyleClass().add("transfer-queued-glyph"); status.setGraphic(icon);
        }
    }
    static void cancelled(Region row, Label status, boolean cancelled) {
        row.pseudoClassStateChanged(CANCELLED, cancelled);
        if (cancelled) {
            var icon = new SVGPath();
            icon.setContent("M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0 M6 6l12 12");
            icon.getStyleClass().add("transfer-cancelled-glyph"); status.setGraphic(icon);
        } else status.setGraphic(null);
    }
    static String speedAndEta(long received, long total, double speed) {
        String rate = speed >= 1000000 ? String.format(Locale.ROOT, "%.1f MB/s", speed / 1000000)
                : speed >= 1000 ? String.format(Locale.ROOT, "%.1f KB/s", speed / 1000) : String.format(Locale.ROOT, "%.0f B/s", speed);
        if (total >= 0 && received >= total) return rate + " · Finishing…";
        if (speed <= 0 || total < 0) return rate + " · Estimating time…";
        double estimate = Math.ceil((total - received) / speed);
        if (!Double.isFinite(estimate) || estimate > 604800) return rate + " · More than 7 days left";
        long seconds = Math.max(1, (long) estimate);
        String time = seconds < 60 ? seconds + "s" : seconds < 3600 ? (seconds + 59) / 60 + "m"
                : (seconds + 59) / 3600 + "h " + ((seconds + 59) / 60) % 60 + "m";
        return rate + " · ~" + time + " left";
    }
}
