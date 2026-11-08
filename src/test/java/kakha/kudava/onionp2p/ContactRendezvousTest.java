package kakha.kudava.onionp2p;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class ContactRendezvousTest {
    @TempDir Path directory;

    @Test void peersDeriveEachOthersEndpointAndKeepItAcrossReloads() throws Exception {
        Path alice = directory.resolve("alice"), bob = directory.resolve("bob");
        String a = ContactLink.loadOrCreate(alice), b = ContactLink.loadOrCreate(bob);
        var fromAlice = ContactRendezvous.importLink(alice, "  " + b + "\n");
        var fromBob = ContactRendezvous.importLink(bob, a);
        assertEquals(fromAlice.remote(), fromBob.local());
        assertEquals(fromAlice.local(), fromBob.remote());
        assertNotEquals(fromAlice.local(), fromAlice.remote());
        assertEquals(fromAlice, ContactRendezvous.importLink(alice, b));
        assertTrue(fromAlice.remote().matches("[a-z2-7]{56}\\.onion"));
        String c = ContactLink.loadOrCreate(directory.resolve("charlie"));
        assertNotEquals(fromAlice.remote(), ContactRendezvous.importLink(alice, c).remote());
    }

    @Test void rejectsBadAndOwnLinks() throws Exception {
        String own = ContactLink.loadOrCreate(directory);
        for (String bad : new String[]{"", "briar://abc", own + "=", own + "?x=1",
                ContactLink.PREFIX + "abc", own.replace("/v1/", "/v2/"), own,
                ContactLink.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32])}) {
            assertThrows(IllegalArgumentException.class, () -> ContactRendezvous.importLink(directory, bad));
        }
    }

    @Test void torAddressMatchesIndependentVector() throws Exception {
        // Ed25519 RFC 8032 test public key; expected address calculated independently with Python hashlib/base64.
        byte[] key = HexFormat.of().parseHex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
        assertEquals("25njqamcweflpvkl73j4szahhihoc4xt3ktcgjnpaingr5yhkenl5sid.onion", ContactRendezvous.onionAddress(key));
    }
}
