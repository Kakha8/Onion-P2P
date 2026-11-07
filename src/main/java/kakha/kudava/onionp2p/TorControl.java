package kakha.kudava.onionp2p;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static java.nio.charset.StandardCharsets.US_ASCII;

/** Short-lived, cookie-authenticated loopback control connections. Never logs control commands or keys. */
final class TorControl implements AutoCloseable {
    private final Socket socket = new Socket();
    private final BufferedReader input;
    private final BufferedWriter output;

    TorControl(Path directory) throws IOException {
        String address = Files.readString(directory.resolve("control-port")).strip();
        if (!address.matches("PORT=127\\.0\\.0\\.1:[0-9]+")) throw new IOException("Invalid Tor control endpoint.");
        socket.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(address.substring(address.lastIndexOf(':') + 1))), 5000);
        socket.setSoTimeout(10000);
        input = new BufferedReader(new InputStreamReader(socket.getInputStream(), US_ASCII));
        output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), US_ASCII));
        try {
            byte[] cookie = Files.readAllBytes(directory.resolve("control-cookie"));
            try { command("AUTHENTICATE " + HexFormat.of().formatHex(cookie)); }
            finally { Arrays.fill(cookie, (byte) 0); }
        } catch (IOException | RuntimeException e) { socket.close(); throw e; }
    }

    private List<String> command(String command) throws IOException {
        output.write(command + "\r\n"); output.flush();
        var lines = new ArrayList<String>();
        for (int i = 0; i < 100; i++) {
            String line = input.readLine();
            if (line == null || !line.startsWith("250")) throw new IOException("Tor control command was rejected.");
            lines.add(line.substring(4));
            if (line.charAt(3) == ' ') return lines;
        }
        throw new IOException("Unexpected Tor control response.");
    }

    int socksPort() throws IOException {
        for (String line : command("GETINFO net/listeners/socks")) {
            var matcher = java.util.regex.Pattern.compile("net/listeners/socks=\"127\\.0\\.0\\.1:(\\d+)\"").matcher(line);
            if (matcher.matches()) return Integer.parseInt(matcher.group(1));
        }
        throw new IOException("Tor SOCKS listener is unavailable.");
    }

    void publish(byte[] seed, int localPort, String expectedAddress) throws Exception {
        // Tor's ED25519-V3 control format is the 64-byte expanded private key, not the 32-byte seed.
        byte[] expanded = MessageDigest.getInstance("SHA-512").digest(seed);
        expanded[0] &= (byte) 248;
        expanded[31] &= (byte) 63;
        expanded[31] |= (byte) 64;
        try {
            List<String> result = command("ADD_ONION ED25519-V3:" + Base64.getEncoder().encodeToString(expanded)
                    + " Flags=Detach,DiscardPK Port=80,127.0.0.1:" + localPort);
            if (!result.contains("ServiceID=" + expectedAddress.replace(".onion", "")))
                throw new IOException("Published onion identity differs from the expected address.");
        } finally { Arrays.fill(expanded, (byte) 0); }
    }

    void unpublish(String address) throws IOException {
        if (!address.matches("[a-z2-7]{56}\\.onion")) throw new IOException("Invalid onion address.");
        command("DEL_ONION " + address.substring(0, 56));
    }

    static Socket connect(int socksPort, String onion, int timeoutMillis) throws IOException {
        if (!onion.matches("[a-z2-7]{56}\\.onion")) throw new IOException("Invalid onion address.");
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", socksPort), 5000);
            socket.setSoTimeout(timeoutMillis);
            var in = new DataInputStream(socket.getInputStream());
            var out = new DataOutputStream(socket.getOutputStream());
            out.write(new byte[]{5, 1, 0}); out.flush();
            if (in.readUnsignedByte() != 5 || in.readUnsignedByte() != 0) throw new IOException("SOCKS negotiation failed.");
            byte[] host = onion.getBytes(US_ASCII);
            out.write(new byte[]{5, 1, 0, 3}); out.writeByte(host.length); out.write(host); out.writeShort(80); out.flush();
            if (in.readUnsignedByte() != 5 || in.readUnsignedByte() != 0) throw new IOException("Contact is not reachable yet.");
            if (in.readUnsignedByte() != 0) throw new IOException("Invalid SOCKS response.");
            int length = switch (in.readUnsignedByte()) {
                case 1 -> 4; case 4 -> 16; case 3 -> in.readUnsignedByte();
                default -> throw new IOException("Invalid SOCKS address.");
            };
            in.readNBytes(length + 2);
            return socket;
        } catch (IOException e) { socket.close(); throw e; }
    }

    @Override public void close() throws IOException { socket.close(); }
}
