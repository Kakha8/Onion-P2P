package kakha.kudava.onionp2p.contacts;

import kakha.kudava.onionp2p.account.AccountStore;
import kakha.kudava.onionp2p.account.AppData;

import javax.crypto.KeyAgreement;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;

/** v2 links also carry the advertised username; v1 remains importable as an unknown-name contact. */
public final class ContactLink {
    public static final String PREFIX_V1 = "onionp2p://contact/v1/x25519/";
    public static final String PREFIX = "onionp2p://contact/v2/";
    public record Parsed(String username, byte[] publicKey) {}

    private ContactLink() {}

    public static synchronized String loadOrCreate(Path directory) throws IOException, GeneralSecurityException {
        return PREFIX_V1 + Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey(loadKey(directory)));
    }

    public static synchronized String loadOrCreate(Path directory, String username) throws IOException, GeneralSecurityException {
        String validName = AccountStore.validateUsername(username);
        String encodedName = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(validName.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return PREFIX + encodedName + "/" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(publicKey(loadKey(directory)));
    }

    static synchronized PrivateKey loadKey(Path directory) throws IOException, GeneralSecurityException {
        Files.createDirectories(directory);
        // Separate from the Tor lock: links can be displayed even when Tor is offline.
        try (var channel = FileChannel.open(directory.resolve("contact.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var ignored = channel.lock()) {
            Path keyFile = directory.resolve("contact-x25519.pk8");
            if (!Files.exists(keyFile)) {
                var generator = KeyPairGenerator.getInstance("X25519");
                PrivateKey key = generator.generateKeyPair().getPrivate();
                Path temporary = Files.createTempFile(directory, "contact-", ".tmp");
                try {
                    restrictToOwner(temporary);
                    Files.write(temporary, key.getEncoded());
                    Files.move(temporary, keyFile, StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
            if (Files.size(keyFile) > 4096) throw new IOException("Contact key file is invalid.");
            byte[] encoded = Files.readAllBytes(keyFile);
            try {
                return KeyFactory.getInstance("X25519")
                        .generatePrivate(new PKCS8EncodedKeySpec(encoded));
            } finally {
                Arrays.fill(encoded, (byte) 0);
            }
        }
    }

    static byte[] publicKey(PrivateKey key) throws GeneralSecurityException {
        PublicKey basePoint = KeyFactory.getInstance("X25519").generatePublic(
                new XECPublicKeySpec(NamedParameterSpec.X25519, BigInteger.valueOf(9)));
        var agreement = KeyAgreement.getInstance("X25519");
        agreement.init(key);
        agreement.doPhase(basePoint, true);
        return agreement.generateSecret();
    }

    static byte[] parse(String input) {
        String link = input.strip();
        if (link.startsWith(PREFIX_V1)) return parsePublicKey(link.substring(PREFIX_V1.length()));
        Parsed parsed = parseLink(link);
        return parsed.publicKey();
    }

    public static Parsed parseLink(String input) {
        String link = input.strip();
        if (link.startsWith(PREFIX_V1)) return new Parsed("Unknown", parsePublicKey(link.substring(PREFIX_V1.length())));
        if (!link.startsWith(PREFIX)) throw new IllegalArgumentException("Use an onionp2p X25519 v1 or v2 contact link.");
        String[] parts = link.substring(PREFIX.length()).split("/", -1);
        if (parts.length != 2 || !parts[0].matches("[A-Za-z0-9_-]{4,43}"))
            throw new IllegalArgumentException("Contact link is malformed.");
        try {
            byte[] nameBytes = Base64.getUrlDecoder().decode(parts[0]);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(nameBytes).equals(parts[0]))
                throw new IllegalArgumentException();
            String username = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(nameBytes)).toString();
            if (!AccountStore.validateUsername(username).equals(username)) throw new IllegalArgumentException();
            return new Parsed(username, parsePublicKey(parts[1]));
        } catch (java.nio.charset.CharacterCodingException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Contact link contains an invalid username or key.", e);
        }
    }

    private static byte[] parsePublicKey(String encoded) {
        if (!encoded.matches("[A-Za-z0-9_-]{43}"))
            throw new IllegalArgumentException("The link must contain a 32-byte public key.");
        byte[] raw = Base64.getUrlDecoder().decode(encoded);
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(raw).equals(encoded)
                || coordinate(raw).compareTo(BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))) >= 0)
            throw new IllegalArgumentException("The public key encoding is not canonical.");
        return raw;
    }

    static BigInteger coordinate(byte[] raw) {
        byte[] reversed = raw.clone();
        for (int i = 0; i < reversed.length / 2; i++) {
            byte b = reversed[i];
            reversed[i] = reversed[reversed.length - 1 - i];
            reversed[reversed.length - 1 - i] = b;
        }
        return new BigInteger(1, reversed);
    }

    static void restrictToOwner(Path file) throws IOException {
        AppData.restrictToOwner(file);
    }
}
