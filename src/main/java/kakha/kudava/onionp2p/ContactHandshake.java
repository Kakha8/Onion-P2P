package kakha.kudava.onionp2p;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.*;
import java.net.Socket;
import java.security.*;
import java.util.Arrays;
import static java.nio.charset.StandardCharsets.US_ASCII;

/** Pairing-only v2: fresh challenges, role-bound HMAC proofs and encrypted acknowledgments.
 * Not a messaging protocol and does not claim forward secrecy. */
final class ContactHandshake {
    private static final int MAGIC = 0x4f503032; // OP02; adds authenticated usernames
    private static final SecureRandom RANDOM = new SecureRandom();
    @FunctionalInterface interface Save { void run() throws Exception; }

    static void client(Socket socket, ContactRendezvous.Material material, Save save) throws Exception {
        socket.setSoTimeout(20000);
        var input = new DataInputStream(socket.getInputStream());
        var output = new DataOutputStream(socket.getOutputStream());
        byte[] nonce = nonce();
        byte[] hello = join(material.localPublic(), material.remotePublic(), name(material.localUsername()), nonce);
        output.writeInt(MAGIC); output.write(nonce);
        output.write(proof(material.sharedSecret(), "client-hello", hello)); output.flush();
        byte[] serverNonce = read(input, 32);
        byte[] transcript = join(hello, name(material.remoteUsername()), serverNonce);
        verify(read(input, 32), proof(material.sharedSecret(), "server-proof", transcript));
        output.write(encrypt(material.sharedSecret(), transcript, "client-ack", "ACK")); output.flush();
        decrypt(material.sharedSecret(), transcript, "server-saved", read(input, 21), "SAVED");
        save.run();
    }

    static void server(Socket socket, ContactRendezvous.Material material, Save save) throws Exception {
        socket.setSoTimeout(20000);
        var input = new DataInputStream(socket.getInputStream());
        var output = new DataOutputStream(socket.getOutputStream());
        if (input.readInt() != MAGIC) throw new IOException("Unsupported contact protocol.");
        byte[] clientNonce = read(input, 32);
        byte[] hello = join(material.remotePublic(), material.localPublic(), name(material.remoteUsername()), clientNonce);
        verify(read(input, 32), proof(material.sharedSecret(), "client-hello", hello));
        byte[] nonce = nonce();
        byte[] transcript = join(hello, name(material.localUsername()), nonce);
        output.write(nonce); output.write(proof(material.sharedSecret(), "server-proof", transcript)); output.flush();
        decrypt(material.sharedSecret(), transcript, "client-ack", read(input, 19), "ACK");
        save.run();
        output.write(encrypt(material.sharedSecret(), transcript, "server-saved", "SAVED")); output.flush();
    }

    private static byte[] nonce() { byte[] nonce = new byte[32]; RANDOM.nextBytes(nonce); return nonce; }
    private static byte[] name(String name) throws IOException {
        if (!name.matches("[A-Za-z0-9_.-]{3,32}")) throw new IOException("Invalid contact username.");
        byte[] bytes = name.getBytes(US_ASCII);
        if (bytes.length < 3 || bytes.length > 32) throw new IOException("Invalid contact username.");
        var framed = new ByteArrayOutputStream();
        framed.write(bytes.length); framed.writeBytes(bytes);
        return framed.toByteArray();
    }
    private static byte[] read(DataInputStream input, int length) throws IOException {
        byte[] data = new byte[length]; input.readFully(data); return data;
    }
    private static byte[] join(byte[]... values) {
        var output = new ByteArrayOutputStream();
        for (byte[] value : values) output.writeBytes(value);
        return output.toByteArray();
    }
    private static byte[] key(byte[] shared, String label, byte[] transcript) {
        var hkdf = new HKDFBytesGenerator(new SHA256Digest());
        hkdf.init(new HKDFParameters(shared, ("Onion-P2P/pairing/v1/" + label).getBytes(US_ASCII), transcript));
        byte[] key = new byte[32]; hkdf.generateBytes(key, 0, 32); return key;
    }
    private static byte[] proof(byte[] shared, String label, byte[] transcript) throws Exception {
        byte[] key = key(shared, "authentication", new byte[0]);
        try {
            var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(join(label.getBytes(US_ASCII), transcript));
        } finally { Arrays.fill(key, (byte) 0); }
    }
    private static void verify(byte[] actual, byte[] expected) throws GeneralSecurityException {
        if (!MessageDigest.isEqual(actual, expected)) throw new GeneralSecurityException("Contact authentication failed.");
    }
    private static byte[] crypt(int mode, byte[] shared, byte[] transcript, String label, byte[] data) throws Exception {
        byte[] key = key(shared, label, transcript);
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            // Each direction/phase has a unique key for this fresh 64-byte nonce pair; used exactly once.
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, new byte[12]));
            cipher.updateAAD(transcript);
            return cipher.doFinal(data);
        } finally { Arrays.fill(key, (byte) 0); }
    }
    private static byte[] encrypt(byte[] shared, byte[] transcript, String label, String message) throws Exception {
        return crypt(Cipher.ENCRYPT_MODE, shared, transcript, label, message.getBytes(US_ASCII));
    }
    private static void decrypt(byte[] shared, byte[] transcript, String label, byte[] ciphertext, String expected) throws Exception {
        verify(crypt(Cipher.DECRYPT_MODE, shared, transcript, label, ciphertext), expected.getBytes(US_ASCII));
    }
}
