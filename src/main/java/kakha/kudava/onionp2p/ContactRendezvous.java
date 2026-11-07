package kakha.kudava.onionp2p;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import javax.crypto.KeyAgreement;
import java.nio.file.Path;
import java.io.IOException;
import java.security.*;
import java.security.spec.*;
import java.util.Arrays;
import static java.nio.charset.StandardCharsets.US_ASCII;

/** App-specific v1 rendezvous derivation; NOT the Briar wire protocol or a running Tor service. */
final class ContactRendezvous {
    record Addresses(String local, String remote) {}

    record Material(Addresses addresses, byte[] localPublic, byte[] remotePublic,
                    byte[] sharedSecret, byte[] localSeed) implements AutoCloseable {
        @Override public void close() {
            Arrays.fill(sharedSecret, (byte) 0);
            Arrays.fill(localSeed, (byte) 0);
        }
    }

    static Addresses importLink(Path directory, String link) throws IOException, GeneralSecurityException {
        try (var material = derive(directory, link)) { return material.addresses(); }
    }

    static Material derive(Path directory, String link) throws IOException, GeneralSecurityException {
        byte[] remote = ContactLink.parse(link);
        PrivateKey privateKey = ContactLink.loadKey(directory);
        byte[] local = ContactLink.publicKey(privateKey);
        if (Arrays.equals(local, remote)) throw new IllegalArgumentException("This is your own link. Paste a contact's link.");
        var publicKey = KeyFactory.getInstance("X25519").generatePublic(
                new XECPublicKeySpec(NamedParameterSpec.X25519, ContactLink.coordinate(remote)));
        var agreement = KeyAgreement.getInstance("X25519");
        agreement.init(privateKey);
        try {
            agreement.doPhase(publicKey, true);
        } catch (InvalidKeyException e) {
            throw new IllegalArgumentException("The link contains an unusable X25519 public key.", e);
        }
        byte[] shared = agreement.generateSecret();
        try {
            return new Material(new Addresses(endpoint(shared, local, remote), endpoint(shared, remote, local)),
                    local, remote, shared, seed(shared, local, remote));
        } catch (GeneralSecurityException | RuntimeException e) {
            Arrays.fill(shared, (byte) 0);
            throw e;
        }
    }

    private static String endpoint(byte[] shared, byte[] owner, byte[] peer) throws GeneralSecurityException {
        byte[] seed = seed(shared, owner, peer);
        try {
            return onionAddress(new Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().getEncoded());
        } finally { Arrays.fill(seed, (byte) 0); }
    }

    private static byte[] seed(byte[] shared, byte[] owner, byte[] peer) {
        byte[] info = new byte[64];
        System.arraycopy(owner, 0, info, 0, 32);
        System.arraycopy(peer, 0, info, 32, 32);
        var hkdf = new HKDFBytesGenerator(new SHA256Digest());
        hkdf.init(new HKDFParameters(shared, "Onion-P2P/rendezvous/v1".getBytes(US_ASCII), info));
        byte[] seed = new byte[32];
        hkdf.generateBytes(seed, 0, seed.length);
        return seed;
    }

    static String onionAddress(byte[] publicKey) throws GeneralSecurityException {
        if (publicKey.length != 32) throw new IllegalArgumentException("Expected a 32-byte Ed25519 public key.");
        var hash = MessageDigest.getInstance("SHA3-256");
        hash.update(".onion checksum".getBytes(US_ASCII));
        hash.update(publicKey);
        byte[] checksum = hash.digest(new byte[]{3});
        byte[] address = Arrays.copyOf(publicKey, 35);
        address[32] = checksum[0]; address[33] = checksum[1]; address[34] = 3;
        String alphabet = "abcdefghijklmnopqrstuvwxyz234567";
        StringBuilder encoded = new StringBuilder(56);
        int buffer = 0, bits = 0;
        for (byte b : address) {
            buffer = (buffer << 8) | (b & 255);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                encoded.append(alphabet.charAt((buffer >>> bits) & 31));
            }
        }
        return encoded + ".onion";
    }
}
