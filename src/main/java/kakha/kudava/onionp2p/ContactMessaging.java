package kakha.kudava.onionp2p;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.LongConsumer;
import java.util.function.BooleanSupplier;

/** One-message, authenticated end-to-end encrypted transport over a contact's Tor service. */
final class ContactMessaging {
    static final int MAGIC = 0x4f503034; // OP04 adds encrypted file frames
    private static final int MAX_BODY_BYTES = 4096;
    static final long MAX_FILE_BYTES = 1024L * 1024 * 1024;
    private static final int CHUNK_BYTES = 64 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ContactMessaging() {}

    static MessageStore.Message send(Socket socket, ContactRendezvous.Material material,
                                     MessageStore store, String link, String body, boolean newConnection) throws Exception {
        byte[] plaintext = body.getBytes(StandardCharsets.UTF_8);
        if (plaintext.length == 0 || plaintext.length > MAX_BODY_BYTES)
            throw new IllegalArgumentException("Message must be between 1 and 4096 UTF-8 bytes.");
        String id = UUID.randomUUID().toString();
        long timestamp = System.currentTimeMillis();
        byte[] idBytes = uuidBytes(id);
        byte[] nonce = new byte[12]; RANDOM.nextBytes(nonce);
        byte[] aad = aad(idBytes, timestamp, (byte) 0, material.localPublic(), material.remotePublic());
        byte[] messageKey = key(material.sharedSecret(), nonce, material.localPublic(), material.remotePublic(), "message");
        byte[] ciphertext;
        try { ciphertext = crypt(Cipher.ENCRYPT_MODE, messageKey, nonce, aad, plaintext); }
        finally { Arrays.fill(messageKey, (byte) 0); Arrays.fill(plaintext, (byte) 0); }

        socket.setSoTimeout(45000);
        var output = new DataOutputStream(socket.getOutputStream());
        var input = new DataInputStream(socket.getInputStream());
        if (newConnection) output.writeInt(MAGIC);
        output.write(idBytes); output.writeLong(timestamp);
        output.write(nonce); output.writeByte(0); output.writeInt(ciphertext.length); output.write(ciphertext); output.flush();
        awaitReceipt(input, material, nonce, aad);
        var saved = new MessageStore.Message(id, link, true, body, null, null, 0, timestamp);
        store.save(link, id, true, body, timestamp);
        return saved;
    }

    static void ping(Socket socket, ContactRendezvous.Material material) throws Exception {
        ping(socket, material, false);
    }

    static void ping(Socket socket, ContactRendezvous.Material material, boolean newConnection) throws Exception {
        socket.setSoTimeout(12000);
        String id = UUID.randomUUID().toString();
        long timestamp = System.currentTimeMillis();
        byte[] idBytes = uuidBytes(id);
        byte[] nonce = new byte[12]; RANDOM.nextBytes(nonce);
        byte type = 2;
        byte[] aad = aad(idBytes, timestamp, type, material.localPublic(), material.remotePublic());
        byte[] messageKey = key(material.sharedSecret(), nonce, material.localPublic(), material.remotePublic(), "message");
        byte[] ciphertext;
        try { ciphertext = crypt(Cipher.ENCRYPT_MODE, messageKey, nonce, aad, "PING".getBytes(StandardCharsets.US_ASCII)); }
        finally { Arrays.fill(messageKey, (byte) 0); }
        var output = new DataOutputStream(socket.getOutputStream());
        var input = new DataInputStream(socket.getInputStream());
        if (newConnection) output.writeInt(MAGIC);
        output.write(idBytes); output.writeLong(timestamp); output.write(nonce); output.writeByte(type);
        output.writeInt(ciphertext.length); output.write(ciphertext); output.flush();
        awaitReceipt(input, material, nonce, aad);
    }

    static MessageStore.Message sendFile(Socket socket, ContactRendezvous.Material material, MessageStore store,
                                         String link, Path file, boolean newConnection, LongConsumer progress,
                                         BooleanSupplier cancelled) throws Exception {
        long fileSize = Files.size(file);
        if (fileSize < 0 || fileSize > MAX_FILE_BYTES)
            throw new IllegalArgumentException("Files must be 1 GiB or smaller.");
        String fileName = safeFileName(file.getFileName().toString());
        byte[] nameBytes = fileName.getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length > 255) throw new IllegalArgumentException("File name is too long.");
        String id = UUID.randomUUID().toString();
        long timestamp = System.currentTimeMillis();
        byte[] idBytes = uuidBytes(id);
        byte[] nonce = new byte[12]; RANDOM.nextBytes(nonce);
        byte type = 1;
        byte[] aad = aad(idBytes, timestamp, type, material.localPublic(), material.remotePublic());
        byte[] messageKey = key(material.sharedSecret(), nonce, material.localPublic(), material.remotePublic(), "message");
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(messageKey, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad);
        Arrays.fill(messageKey, (byte) 0);

        long ciphertextLength = 2L + nameBytes.length + 8L + fileSize + 16L;
        socket.setSoTimeout(45000);
        var output = new DataOutputStream(socket.getOutputStream());
        var input = new DataInputStream(socket.getInputStream());
        if (newConnection) output.writeInt(MAGIC);
        output.write(idBytes); output.writeLong(timestamp); output.write(nonce); output.writeByte(type);
        output.writeInt(Math.toIntExact(ciphertextLength));
        byte[] metadata = ByteBuffer.allocate(2 + nameBytes.length + 8).putShort((short) nameBytes.length)
                .put(nameBytes).putLong(fileSize).array();
        byte[] encryptedMetadata = cipher.update(metadata);
        if (encryptedMetadata != null) output.write(encryptedMetadata);
        Arrays.fill(metadata, (byte) 0);
        byte[] buffer = new byte[CHUNK_BYTES];
        long transferred = 0;
        progress.accept(0);
        long lastReported = 0;
        long lastReportedAt = System.nanoTime();
        try (InputStream source = Files.newInputStream(file)) {
            while (transferred < fileSize) {
                checkCancelled(cancelled);
                int count = source.read(buffer, 0, (int) Math.min(buffer.length, fileSize - transferred));
                if (count < 0) throw new IOException("File changed while it was being sent.");
                byte[] encrypted = cipher.update(buffer, 0, count);
                if (encrypted != null) output.write(encrypted);
                transferred += count;
                long now = System.nanoTime();
                if (transferred - lastReported >= 256 * 1024 || now - lastReportedAt >= 250_000_000L || transferred == fileSize) {
                    progress.accept(transferred);
                    lastReported = transferred;
                    lastReportedAt = now;
                }
            }
            if (source.read() != -1) throw new IOException("File changed while it was being sent.");
        } finally { Arrays.fill(buffer, (byte) 0); Arrays.fill(nameBytes, (byte) 0); }
        checkCancelled(cancelled);
        output.write(cipher.doFinal());
        output.flush();
        socket.setSoTimeout(10 * 60 * 1000);
        checkCancelled(cancelled);
        awaitReceipt(input, material, nonce, aad);
        store.saveFile(link, id, true, fileName, file.toAbsolutePath().toString(), fileSize, timestamp);
        return new MessageStore.Message(id, link, true, "", fileName, file.toAbsolutePath().toString(), fileSize, timestamp);
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException("File transfer cancelled.");
    }

    static void receive(Socket socket, ContactRendezvous.Material material, MessageStore store, String link) throws Exception {
        socket.setSoTimeout(0);
        var input = new DataInputStream(socket.getInputStream());
        var output = new DataOutputStream(socket.getOutputStream());
        while (true) receiveOne(input, output, material, store, link);
    }

    private static void receiveOne(DataInputStream input, DataOutputStream output,
                                   ContactRendezvous.Material material, MessageStore store, String link) throws Exception {
        byte[] idBytes = new byte[16]; input.readFully(idBytes);
        String id = uuidString(idBytes);
        long timestamp = input.readLong();
        long now = System.currentTimeMillis();
        if (timestamp < now - 30L * 24 * 60 * 60 * 1000 || timestamp > now + 5 * 60 * 1000)
            throw new IOException("Message timestamp is outside the accepted window.");
        byte[] nonce = new byte[12]; input.readFully(nonce);
        byte type = input.readByte();
        int length = input.readInt();
        long maxLength = type == 1 ? MAX_FILE_BYTES + 281 : MAX_BODY_BYTES + 16;
        if (length < 16 || length > maxLength) throw new IOException("Invalid message size.");
        if (type != 0 && type != 1 && type != 2) throw new IOException("Unsupported message type.");
        byte[] aad = aad(idBytes, timestamp, type, material.remotePublic(), material.localPublic());
        if (type == 1) {
            receiveFile(input, output, material, store, link, id, timestamp, nonce, aad, length);
            return;
        }
        if (type == 2) {
            if (length != 20) throw new IOException("Invalid connection-check message.");
            byte[] ciphertext = new byte[length]; input.readFully(ciphertext);
            byte[] messageKey = key(material.sharedSecret(), nonce, material.remotePublic(), material.localPublic(), "message");
            byte[] plaintext;
            try { plaintext = crypt(Cipher.DECRYPT_MODE, messageKey, nonce, aad, ciphertext); }
            finally { Arrays.fill(messageKey, (byte) 0); Arrays.fill(ciphertext, (byte) 0); }
            try {
                if (!MessageDigest.isEqual(plaintext, "PING".getBytes(StandardCharsets.US_ASCII)))
                    throw new GeneralSecurityException("Invalid connection check.");
            } finally { Arrays.fill(plaintext, (byte) 0); }
            awaitReceiptSend(output, material, nonce, aad);
            return;
        }
        byte[] ciphertext = new byte[length]; input.readFully(ciphertext);
        byte[] messageKey = key(material.sharedSecret(), nonce, material.remotePublic(), material.localPublic(), "message");
        byte[] plaintext;
        try { plaintext = crypt(Cipher.DECRYPT_MODE, messageKey, nonce, aad, ciphertext); }
        finally { Arrays.fill(messageKey, (byte) 0); Arrays.fill(ciphertext, (byte) 0); }
        String body;
        try {
            body = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(plaintext)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new IOException("Message is not valid UTF-8.", e);
        } finally { Arrays.fill(plaintext, (byte) 0); }
        if (body.isEmpty() || body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES)
            throw new IOException("Invalid message text.");

        // Persist before acknowledging; a retry with the same ID is safely deduplicated.
        store.save(link, id, false, body, timestamp);
        awaitReceiptSend(output, material, nonce, aad);
    }

    private static void receiveFile(DataInputStream input, DataOutputStream output, ContactRendezvous.Material material,
                                    MessageStore store, String link, String id, long timestamp, byte[] nonce,
                                    byte[] aad, int ciphertextLength) throws Exception {
        byte[] messageKey = key(material.sharedSecret(), nonce, material.remotePublic(), material.localPublic(), "message");
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(messageKey, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad);
        Arrays.fill(messageKey, (byte) 0);
        Path downloads = AppData.directory().resolve("downloads");
        Files.createDirectories(downloads);
        Path temporary = Files.createTempFile(downloads, ".onion-incoming-", ".part");
        String fileName = null;
        long expectedSize = -1;
        try {
            try (var decrypted = new DataInputStream(new CipherInputStream(new LimitedInputStream(input, ciphertextLength), cipher))) {
                int nameLength = decrypted.readUnsignedShort();
                if (nameLength < 1 || nameLength > 255) throw new IOException("Invalid attachment name.");
                byte[] nameBytes = new byte[nameLength]; decrypted.readFully(nameBytes);
                try { fileName = safeFileName(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(nameBytes)).toString()); }
                catch (java.nio.charset.CharacterCodingException e) { throw new IOException("Invalid attachment name.", e); }
                finally { Arrays.fill(nameBytes, (byte) 0); }
                expectedSize = decrypted.readLong();
                if (expectedSize < 0 || expectedSize > MAX_FILE_BYTES) throw new IOException("Attachment exceeds the 1 GiB limit.");
                try (var target = Files.newOutputStream(temporary)) {
                    byte[] buffer = new byte[CHUNK_BYTES];
                    long written = 0;
                    try {
                        while (written < expectedSize) {
                            int count = decrypted.read(buffer, 0, (int) Math.min(buffer.length, expectedSize - written));
                            if (count < 0) throw new IOException("Attachment was truncated.");
                            target.write(buffer, 0, count); written += count;
                        }
                        if (decrypted.read() != -1) throw new IOException("Attachment size did not match its contents.");
                    } finally { Arrays.fill(buffer, (byte) 0); }
                }
            }
            Path destination = uniquePath(downloads, fileName);
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            try {
                store.saveFile(link, id, false, fileName, destination.toAbsolutePath().toString(), expectedSize, timestamp);
            } catch (Exception e) { Files.deleteIfExists(destination); throw e; }
            awaitReceiptSend(output, material, nonce, aad);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static String safeFileName(String name) throws IOException {
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..")
                || name.contains("/") || name.contains("\\") || name.chars().anyMatch(Character::isISOControl))
            throw new IOException("Invalid attachment name.");
        return name;
    }

    private static Path uniquePath(Path directory, String name) {
        Path first = directory.resolve(name);
        if (!Files.exists(first)) return first;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int n = 1; n < 10000; n++) {
            Path candidate = directory.resolve(base + " (" + n + ")" + extension);
            if (!Files.exists(candidate)) return candidate;
        }
        return directory.resolve(UUID.randomUUID() + "-" + name);
    }

    private static void awaitReceipt(DataInputStream input, ContactRendezvous.Material material,
                                     byte[] nonce, byte[] aad) throws Exception {
        byte[] receipt = new byte[21]; input.readFully(receipt);
        byte[] ackKey = key(material.sharedSecret(), nonce, material.localPublic(), material.remotePublic(), "receipt");
        try {
            byte[] expected = crypt(Cipher.ENCRYPT_MODE, ackKey, nonce, aad, "SAVED".getBytes(StandardCharsets.US_ASCII));
            if (!MessageDigest.isEqual(receipt, expected)) throw new GeneralSecurityException("Message receipt authentication failed.");
        } finally { Arrays.fill(ackKey, (byte) 0); }
    }

    private static void awaitReceiptSend(DataOutputStream output, ContactRendezvous.Material material,
                                        byte[] nonce, byte[] aad) throws Exception {
        byte[] ackKey = key(material.sharedSecret(), nonce, material.remotePublic(), material.localPublic(), "receipt");
        try {
            output.write(crypt(Cipher.ENCRYPT_MODE, ackKey, nonce, aad, "SAVED".getBytes(StandardCharsets.US_ASCII)));
            output.flush();
        } finally { Arrays.fill(ackKey, (byte) 0); }
    }

    private static final class LimitedInputStream extends InputStream {
        private final InputStream source;
        private long remaining;
        LimitedInputStream(InputStream source, long length) { this.source = source; remaining = length; }
        @Override public int read() throws IOException {
            if (remaining == 0) return -1;
            int value = source.read(); if (value >= 0) remaining--; return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (remaining == 0) return -1;
            int count = source.read(bytes, offset, (int) Math.min(length, remaining));
            if (count > 0) remaining -= count;
            return count;
        }
        @Override public void close() { /* The socket stream remains open for later chat messages. */ }
    }

    private static byte[] key(byte[] shared, byte[] nonce, byte[] sender, byte[] recipient, String purpose) {
        byte[] info = ByteBuffer.allocate(32 + 32 + purpose.length())
                .put(sender).put(recipient).put(purpose.getBytes(StandardCharsets.US_ASCII)).array();
        var hkdf = new HKDFBytesGenerator(new SHA256Digest());
        hkdf.init(new HKDFParameters(shared, nonce, info));
        byte[] result = new byte[32]; hkdf.generateBytes(result, 0, result.length);
        Arrays.fill(info, (byte) 0);
        return result;
    }

    private static byte[] crypt(int mode, byte[] key, byte[] nonce, byte[] aad, byte[] input) throws GeneralSecurityException {
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(input);
    }

    private static byte[] aad(byte[] id, long timestamp, byte type, byte[] sender, byte[] recipient) {
        return ByteBuffer.allocate(16 + 8 + 1 + 32 + 32).put(id).putLong(timestamp).put(type).put(sender).put(recipient).array();
    }

    private static byte[] uuidBytes(String id) {
        UUID uuid = UUID.fromString(id);
        return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
    }
    private static String uuidString(byte[] bytes) {
        var buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong()).toString();
    }
}
