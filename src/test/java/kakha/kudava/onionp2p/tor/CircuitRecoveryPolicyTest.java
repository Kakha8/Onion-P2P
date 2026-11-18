package kakha.kudava.onionp2p.tor;

import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class CircuitRecoveryPolicyTest {
    private static final String TARGET = "a".repeat(56) + ".onion:80";
    private static final String NEW = "650 STREAM 1 NEW 0 " + TARGET;
    private static final String FAILURE = "650 CIRC 100 FAILED PURPOSE=HS_CLIENT_HSDIR REASON=DESTROYED REMOTE_REASON=RESOURCELIMIT";
    private final AtomicLong clock = new AtomicLong();
    private final CircuitRecoveryPolicy policy = new CircuitRecoveryPolicy(clock::get);

    private boolean eventAt(long seconds, String event) {
        clock.set(TimeUnit.SECONDS.toNanos(seconds));
        return policy.event(event);
    }
    private boolean failuresFrom(long seconds) {
        for (int i = 0; i < 5; i++) assertFalse(eventAt(seconds + i * 9, FAILURE));
        return eventAt(seconds + 45, FAILURE);
    }

    @Test void offlineContactAndIsolatedCircuitFailuresDoNotRotateCircuits() {
        for (int i = 0; i < 20; i++) {
            assertFalse(eventAt(60 + i * 10, NEW));
            assertFalse(policy.event("650 STREAM 1 FAILED 0 " + TARGET + " REASON=TIMEOUT"));
            assertFalse(policy.event(FAILURE));
        }
        assertFalse(failuresFrom(300)); // No pending onion stream.
        policy.event(NEW);
        for (int i = 0; i < 20; i++)
            assertFalse(eventAt(400 + i * 10, "650 CIRC 100 FAILED PURPOSE=HS_CLIENT_REND REASON=TIMEOUT"));
    }

    @Test void startupAndShortFailureBurstsAreGivenTimeToRecoverNormally() {
        policy.event(NEW);
        for (int i = 0; i < 60; i++) assertFalse(eventAt(i, FAILURE));
        for (int i = 0; i < 20; i++) assertFalse(eventAt(60, FAILURE));
        assertFalse(eventAt(104, FAILURE));
        assertTrue(eventAt(105, FAILURE));
    }

    @Test void sustainedRelayFailuresRequestFreshCircuitsEvenIfGeneralCircuitsBuild() {
        policy.event(NEW);
        assertFalse(eventAt(60, FAILURE));
        assertFalse(eventAt(69, FAILURE));
        assertFalse(eventAt(75, "650 CIRC 9 BUILT PURPOSE=GENERAL"));
        assertFalse(eventAt(78, FAILURE));
        assertFalse(eventAt(87, FAILURE));
        assertFalse(eventAt(96, FAILURE));
        assertTrue(eventAt(105, FAILURE));
    }

    @Test void actualOnionAndAuthenticatedContactSuccessResetFailureStreak() {
        policy.event(NEW);
        for (int i = 0; i < 5; i++) assertFalse(eventAt(60 + i * 9, FAILURE));
        assertFalse(eventAt(100, "650 STREAM 2 SUCCEEDED 10 " + TARGET));
        assertFalse(eventAt(105, FAILURE));
        clock.set(TimeUnit.SECONDS.toNanos(140));
        policy.exchangeSucceeded();
        assertFalse(eventAt(150, FAILURE));
        for (int i = 1; i < 5; i++) assertFalse(eventAt(150 + i * 9, FAILURE));
        assertTrue(eventAt(195, FAILURE));
    }

    @Test void cooldownAndHourlyBudgetPreventRepeatedCircuitChurn() {
        policy.event(NEW);
        assertTrue(failuresFrom(60));
        assertFalse(failuresFrom(120));
        assertTrue(failuresFrom(360));
        assertTrue(failuresFrom(660));
        assertFalse(failuresFrom(960));
        assertTrue(failuresFrom(3700));
    }

    @Test void oldFailuresAndFinishedRemappedStreamsCannotTriggerRecovery() {
        policy.event(NEW);
        for (int i = 0; i < 5; i++) assertFalse(eventAt(60 + i * 9, FAILURE));
        assertFalse(eventAt(300, FAILURE));
        policy.event("650 STREAM 1 CLOSED 10 127.0.0.1:80 REASON=DONE");
        assertFalse(failuresFrom(310));
        policy.event(NEW);
        assertFalse(eventAt(400, "650 CIRC 100 FAILED REASON=MEASUREMENT_EXPIRED"));
        assertFalse(eventAt(410, "650 CIRC 100 CLOSED REASON=FINISHED"));
    }

    @Test void monitorReconnectClearsStaleStreamsButRetainsRecoveryCooldown() {
        policy.event(NEW);
        assertTrue(failuresFrom(60));
        policy.monitorDisconnected();
        assertFalse(eventAt(110, FAILURE));
        policy.event(NEW);
        assertFalse(failuresFrom(120));
        assertTrue(failuresFrom(360));
    }
}
