package kakha.kudava.onionp2p;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;

/** One-message, authenticated end-to-end encrypted transport over a contact's Tor service. */
final class ContactMessaging {
    static final int MAGIC = 0x4f503033; // OP03
    private static final int MAX_BODY_BYTES = 4096;
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
        byte[] aad = aad(idBytes, timestamp, material.localPublic(), material.remotePublic());
        byte[] messageKey = key(material.sharedSecret(), nonce, material.localPublic(), material.remotePublic(), "message");
        byte[] ciphertext;
        try { ciphertext = crypt(Cipher.ENCRYPT_MODE, messageKey, nonce, aad, plaintext); }
        finally { Arrays.fill(messageKey, (byte) 0); Arrays.fill(plaintext, (byte) 0); }

        socket.setSoTimeout(45000);
        var output = new DataOutputStream(socket.getOutputStream());
        var input = new DataInputStream(socket.getInputStream());
        if (newConnection) output.writeInt(MAGIC);
        output.write(idBytes); output.writeLong(timestamp);
        output.write(nonce); output.writeInt(ciphertext.length); output.write(ciphertext); output.flush();
        byte[] receipt = new byte[21]; input.readFully(receipt);
        byte[] ackKey = key(material.sharedSecret(), nonce, material.localPublic(), material.remotePublic(), "receipt");
        try {
            byte[] expected = crypt(Cipher.ENCRYPT_MODE, ackKey, nonce, aad, "SAVED".getBytes(StandardCharsets.US_ASCII));
            if (!MessageDigest.isEqual(receipt, expected)) throw new GeneralSecurityException("Message receipt authentication failed.");
        } finally { Arrays.fill(ackKey, (byte) 0); }
        var saved = new MessageStore.Message(id, link, true, body, timestamp);
        store.save(link, id, true, body, timestamp);
        return saved;
    }

    static void receive(Socket socket, ContactRendezvous.Material material, MessageStore store, String link) throws Exception {
        socket.setSoTimeout(0);
        var input = new DataInputStream(socket.getInputStream());
        var output = new DataOutputStream(socket.getOutputStream());
        while (true) receiveOne(input, output, material, store, link);
    }

    private static void receiveOne(DataInputStream input, DataOutputStream output,
                                   ContactRendezvous.Material material, MessageStore store, String link) throws Exception {
        byte[] idBytes = new byte[16];
        try { input.readFully(idBytes); }
        catch (java.io.EOFException disconnected) { throw disconnected; }
        String id = uuidString(idBytes);
        long timestamp = input.readLong();
        long now = System.currentTimeMillis();
        if (timestamp < now - 30L * 24 * 60 * 60 * 1000 || timestamp > now + 5 * 60 * 1000)
            throw new IOException("Message timestamp is outside the accepted window.");
        byte[] nonce = new byte[12]; input.readFully(nonce);
        int length = input.readInt();
        if (length < 16 || length > MAX_BODY_BYTES + 16) throw new IOException("Invalid message size.");
        byte[] ciphertext = new byte[length]; input.readFully(ciphertext);
        byte[] aad = aad(idBytes, timestamp, material.remotePublic(), material.localPublic());
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
        byte[] ackKey = key(material.sharedSecret(), nonce, material.remotePublic(), material.localPublic(), "receipt");
        try {
            byte[] receipt = crypt(Cipher.ENCRYPT_MODE, ackKey, nonce, aad, "SAVED".getBytes(StandardCharsets.US_ASCII));
            output.write(receipt); output.flush();
        } finally { Arrays.fill(ackKey, (byte) 0); }
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

    private static byte[] aad(byte[] id, long timestamp, byte[] sender, byte[] recipient) {
        return ByteBuffer.allocate(16 + 8 + 32 + 32).put(id).putLong(timestamp).put(sender).put(recipient).array();
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
