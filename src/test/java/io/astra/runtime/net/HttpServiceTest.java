package io.astra.runtime.net;

import com.sun.net.httpserver.HttpServer;
import io.astra.logging.AstraLogger;
import io.astra.runtime.ActionFailure;
import io.astra.runtime.ExecContext;
import io.astra.security.SecurityGate;
import io.astra.security.SecurityPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@code security.yml} really governs outbound HTTP: the gate is
 * consulted before the request, the response size cap aborts oversized answers, and
 * credentials never reach a log line.
 */
class HttpServiceTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastBody = new AtomicReference<>("");
    private final AtomicReference<String> lastContentType = new AtomicReference<>("");

    private final AstraLogger logger = new AstraLogger(message -> { });

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> respond(exchange, 200, "{\"price\":42}"));
        server.createContext("/big", exchange -> {
            byte[] payload = new byte[4096];
            java.util.Arrays.fill(payload, (byte) 'x');
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(750);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "late");
        });
        server.createContext("/hook", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            respond(exchange, 204, "");
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
        throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, payload.length == 0 ? -1 : payload.length);
        if (payload.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        }
        exchange.close();
    }

    @Test
    void allowedRequestReturnsTheBody() {
        HttpService service = new HttpService(new AllowAllGate(), logger, 5_000, 64 * 1024);
        HttpService.Response response = service.get(baseUrl + "/ok", null);
        assertTrue(response.ok(), response.describe());
        assertEquals(200, response.status());
        assertEquals("{\"price\":42}", response.body());
    }

    @Test
    void oversizedResponsesAreAbortedInsteadOfBuffered() {
        HttpService service = new HttpService(new AllowAllGate(), logger, 5_000, 1024);
        HttpService.Response response = service.get(baseUrl + "/big", null);
        assertFalse(response.ok());
        assertEquals("", response.body());
        assertTrue(response.error().contains("max-http-response-size"), response.error());
    }

    @Test
    void denialsFromTheSecurityGateStopTheRequest() {
        SecurityGate denying = new AllowAllGate() {
            @Override
            public void checkHttp(String url, boolean webhook, ExecContext context) {
                throw new ActionFailure("http.allowed-domains is empty in security.yml");
            }
        };
        HttpService service = new HttpService(denying, logger, 5_000, 1024);
        ActionFailure failure = assertThrows(ActionFailure.class, () -> service.get(baseUrl + "/ok", null));
        assertTrue(failure.getMessage().contains("allowed-domains"));
    }

    @Test
    void timeoutsAreReportedNotSwallowed() {
        HttpService service = new HttpService(new AllowAllGate(), logger, 250, 64 * 1024);
        HttpService.Response response = service.get(baseUrl + "/slow", null);
        assertFalse(response.ok());
        assertTrue(response.error().contains("timed out"), response.error());
    }

    @Test
    void webhookPostsAJsonBody() {
        HttpService service = new HttpService(new AllowAllGate(), logger, 5_000, 64 * 1024);
        HttpService.Response response = service.post(baseUrl + "/hook",
            HttpService.webhookPayload("Player \"Alex\" joined\nwelcome"), "application/json", true, null);
        assertTrue(response.ok(), response.describe());
        assertEquals("{\"content\":\"Player \\\"Alex\\\" joined\\nwelcome\"}", lastBody.get());
        assertTrue(lastContentType.get().startsWith("application/json"));
    }

    @Test
    void credentialsAreRedactedEverywhere() {
        assertEquals("https://user:***@example.com/hook?token=1",
            HttpService.redact("https://user:sup3rsecr3t@example.com/hook?token=1"));
        assertEquals("https://example.com/hook", HttpService.redact("https://example.com/hook"));
        assertEquals("not a url", HttpService.redact("not a url"));
    }

    @Test
    void jsonEscapingCoversControlCharacters() {
        assertEquals("a\\\"b\\\\c\\nd\\te", HttpService.jsonEscape("a\"b\\c\nd\te"));
    }

    /** A gate that permits everything, used as the base for the denial test. */
    private static class AllowAllGate implements SecurityGate {

        @Override
        public SecurityPolicy policy() {
            return new SecurityPolicy(true, true, true, List.of("127.0.0.1"), true,
                List.of("127.0.0.1"), false, false, false, 1000, 100, 64 * 1024);
        }

        @Override
        public void checkAction(String actionId, ExecContext context) {
        }

        @Override
        public void checkConsoleCommand(String command, ExecContext context) {
        }

        @Override
        public void checkFileAccess(String relativePath, ExecContext context) {
        }

        @Override
        public void checkHttp(String url, boolean webhook, ExecContext context) {
        }

        @Override
        public void checkModuleLoad(String moduleName, String sourcePath) {
        }

        @Override
        public boolean domainAllowed(String url, List<String> allowedDomains) {
            return true;
        }
    }
}
