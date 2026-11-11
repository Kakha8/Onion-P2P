package kakha.kudava.onionp2p;

import kakha.kudava.onionp2p.contacts.ContactLink;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.crypto.KeyAgreement;
import java.math.BigInteger;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class ContactLinkTest {
    @TempDir Path directory;

    @Test void linkSurvivesReloadAndContainsMatchingPublicKey() throws Exception {
        String link = ContactLink.loadOrCreate(directory);
        assertEquals(link, ContactLink.loadOrCreate(directory));
        assertNotEquals(link, ContactLink.loadOrCreate(directory.resolve("other")));
        assertTrue(link.startsWith(ContactLink.PREFIX));
        byte[] raw = Base64.getUrlDecoder().decode(link.substring(ContactLink.PREFIX.length()));
        assertEquals(32, raw.length);
        // Convert the little-endian public coordinate for Java's XEC key spec.
        for (int i = 0; i < raw.length / 2; i++) {
            byte b = raw[i]; raw[i] = raw[raw.length - 1 - i]; raw[raw.length - 1 - i] = b;
        }
        var factory = KeyFactory.getInstance("X25519");
        PublicKey publicKey = factory.generatePublic(new XECPublicKeySpec(
                NamedParameterSpec.X25519, new BigInteger(1, raw)));
        PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(
                Files.readAllBytes(directory.resolve("contact-x25519.pk8"))));
        var peer = KeyPairGenerator.getInstance("X25519").generateKeyPair();
        assertArrayEquals(secret(privateKey, peer.getPublic()), secret(peer.getPrivate(), publicKey));
    }

    @Test void corruptKeyIsNotSilentlyReplaced() throws Exception {
        Path key = directory.resolve("contact-x25519.pk8");
        byte[] invalid = {1, 2, 3};
        Files.write(key, invalid);
        assertThrows(GeneralSecurityException.class, () -> ContactLink.loadOrCreate(directory));
        assertArrayEquals(invalid, Files.readAllBytes(key));
    }

    private static byte[] secret(PrivateKey privateKey, PublicKey publicKey) throws Exception {
        var agreement = KeyAgreement.getInstance("X25519");
        agreement.init(privateKey);
        agreement.doPhase(publicKey, true);
        return agreement.generateSecret();
    }
}
