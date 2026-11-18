package kakha.kudava.onionp2p.ui;

import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;

/** A global, non-modal connection notice, hidden when contact connections recover. */
final class TorRecoveryNotice extends HBox {
    static final String MESSAGE = "Tor connection trouble · Trying another route…";

    TorRecoveryNotice() {
        var glyph = new SVGPath();
        glyph.setContent("M18 7 A7 7 0 0 0 5 5 L3 8 M3 3 V8 H8 M2 13 A7 7 0 0 0 15 15 L17 12 M17 17 V12 H12");
        glyph.getStyleClass().add("tor-recovery-glyph");
        var icon = new StackPane(glyph);
        icon.setMinSize(20, 20); icon.setPrefSize(20, 20); icon.setMaxSize(20, 20);
        var title = new Label("Tor connection trouble");
        title.getStyleClass().add("tor-recovery-title");
        title.setMinWidth(USE_PREF_SIZE);
        var separator = new Label("·");
        separator.getStyleClass().add("tor-recovery-detail");
        var detail = new Label("Trying another route…");
        detail.getStyleClass().add("tor-recovery-detail");
        getChildren().addAll(icon, title, separator, detail);
        getStyleClass().add("tor-recovery-notice");
        setAlignment(Pos.CENTER_LEFT);
        setAccessibleRole(AccessibleRole.TEXT);
        setAccessibleText(MESSAGE);
        Tooltip.install(this, new Tooltip("Reconnecting to your contacts automatically."));
        setRecovering(false);
    }

    void setRecovering(boolean recovering) { setVisible(recovering); setManaged(recovering); }
}
