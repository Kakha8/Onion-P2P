package kakha.kudava.onionp2p.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransferPresentationTest {
    @Test void estimatesRemainingTimeAndHandlesUnknownSpeedAndSize() {
        assertEquals("1.0 KB/s · ~9s left", TransferPresentation.speedAndEta(1000, 10000, 1000));
        assertEquals("0 B/s · Estimating time…", TransferPresentation.speedAndEta(1000, 10000, 0));
        assertTrue(TransferPresentation.speedAndEta(1000, -1, 1000).contains("Estimating time"));
        assertTrue(TransferPresentation.speedAndEta(10000, 10000, 1000).contains("Finishing"));
        assertTrue(TransferPresentation.speedAndEta(0, 7199, 1).contains("2h 0m"));
    }
}
