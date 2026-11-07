package kakha.kudava.onionp2p;

import javax.crypto.KeyAgreement;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;

/** Version 1 links contain an unpadded Base64url raw, little-endian X25519 public key. */
final class ContactLink {
    static final String PREFIX = "onionp2p://contact/v1/x25519/";

    private ContactLink() {}

    static synchronized String loadOrCreate(Path directory) throws IOException, GeneralSecurityException {
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
                PrivateKey key = KeyFactory.getInstance("X25519")
                        .generatePrivate(new PKCS8EncodedKeySpec(encoded));
                // Scalar multiplication by the standard base point derives the public key.
                PublicKey basePoint = KeyFactory.getInstance("X25519").generatePublic(
                        new XECPublicKeySpec(NamedParameterSpec.X25519, BigInteger.valueOf(9)));
                var agreement = KeyAgreement.getInstance("X25519");
                agreement.init(key);
                agreement.doPhase(basePoint, true);
                return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(agreement.generateSecret());
            } finally {
                Arrays.fill(encoded, (byte) 0);
            }
        }
    }

    private static void restrictToOwner(Path file) throws IOException {
        var acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (acl != null) {
            acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                    .setPrincipal(Files.getOwner(file))
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
        } else if (Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }
    }
}
