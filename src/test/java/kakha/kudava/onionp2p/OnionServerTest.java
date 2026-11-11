package kakha.kudava.onionp2p;

import kakha.kudava.onionp2p.tor.OnionServer;

import org.junit.jupiter.api.Test;
import java.net.HttpURLConnection;
import java.net.URI;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class OnionServerTest {
    @Test
    void servesHelloWorldOnlyOnLoopback() throws Exception {
        var server = OnionServer.createHttpServer();
        server.start();
        try {
            assertTrue(server.getAddress().getAddress().isLoopbackAddress());
            var connection = (HttpURLConnection) URI.create("http://127.0.0.1:"
                    + server.getAddress().getPort() + "/").toURL().openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            try {
                assertEquals(200, connection.getResponseCode());
                assertEquals("text/plain; charset=utf-8", connection.getContentType());
                try (var input = connection.getInputStream()) {
                    assertEquals("Hello world!\n", new String(input.readAllBytes(), UTF_8));
                }
            } finally {
                connection.disconnect();
            }
        } finally {
            server.stop(0);
        }
    }
}
