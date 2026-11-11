package kakha.kudava.onionp2p;

import kakha.kudava.onionp2p.contacts.ContactLink;
import kakha.kudava.onionp2p.contacts.ContactManager;
import kakha.kudava.onionp2p.contacts.ContactStore;
import kakha.kudava.onionp2p.tor.OnionServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in network test: mvnw -Dtest=TorPairingIT -Donion.liveTest=true test */
@EnabledIfSystemProperty(named = "onion.liveTest", matches = "true")
class TorPairingIT {
    @Test void twoIndependentTorProcessesPairAndPersist() throws Exception {
        Path root = Files.createTempDirectory(Path.of("target"), "live pairing ");
        Path alice = root.resolve("alice"), bob = root.resolve("bob");
        String a = ContactLink.loadOrCreate(alice), b = ContactLink.loadOrCreate(bob);
        try (var at = new OnionServer(alice); var bt = new OnionServer(bob);
             var am = new ContactManager(alice, at, e -> System.out.println("Alice: " + e.message()));
             var bm = new ContactManager(bob, bt, e -> System.out.println("Bob: " + e.message()));
             var executor = Executors.newFixedThreadPool(2)) {
            var af = executor.submit(() -> { at.start(); return null; });
            var bf = executor.submit(() -> { bt.start(); return null; });
            am.add(b); bm.add(a);
            af.get(210, TimeUnit.SECONDS); bf.get(210, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
            while ((am.contacts().isEmpty() || bm.contacts().isEmpty()) && System.nanoTime() < deadline)
                Thread.sleep(1000);
            assertEquals(1, am.contacts().size()); assertEquals(1, bm.contacts().size());
            assertEquals(b, new ContactStore(alice).all().getFirst().link());
            assertEquals(a, new ContactStore(bob).all().getFirst().link());
            System.out.println("LIVE PAIRING PASSED: both SQLite databases contain the acknowledged peer.");
        }
    }
}
