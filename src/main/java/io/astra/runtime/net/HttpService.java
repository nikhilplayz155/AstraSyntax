package io.astra.runtime.net;

import io.astra.logging.AstraLogger;
import io.astra.runtime.ExecContext;
import io.astra.security.SecurityGate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/**
 * Outbound HTTP for scripts - requests and webhooks - with {@code security.yml}
 * enforced before a single byte leaves the server.
 *
 * <p>Enforcement points, in order:</p>
 * <ol>
 *   <li>{@link SecurityGate#checkHttp(String, boolean, ExecContext)} (allow-http /
 *       webhooks.enabled, the domain allowlists, and the {@code features.http} /
 *       {@code features.webhooks} switches at load time);</li>
 *   <li>a hard response-size cap ({@code security.limits.max-http-response-size}),
 *       enforced while reading so an oversized response is aborted rather than
 *       buffered into memory;</li>
 *   <li>connect and request timeouts, so a hanging endpoint cannot pin a thread.</li>
 * </ol>
 *
 * <p>The service never runs on the server thread: callers use
 * {@code SchedulerService.runAsync}. {@link #redact(String)} keeps credentials out of
 * logs and diagnostics, which is why every message produced here goes through it.</p>
 */
public final class HttpService {

    /** Default user agent; servers like to know who is calling. */
    public static final String USER_AGENT = "AstraSyntax/1.21.11-26.2";

    /** Outcome of one request. */
    public record Response(boolean ok, int status, String body, long elapsedMillis, String error) {

        /** True when the request failed locally (timeout, refusal, size cap, security). */
        public boolean failed() {
            return !ok;
        }

        /** A short, credential-free description for logs. */
        public String describe() {
            return ok ? ("HTTP " + status + " in " + elapsedMillis + "ms") : ("HTTP failed: " + error);
        }
    }

    private final SecurityGate gate;
    private final AstraLogger logger;
    private final long timeoutMillis;
    private final int maxResponseBytes;
    private final HttpClient client;

    public HttpService(SecurityGate gate, AstraLogger logger, long timeoutMillis, int maxResponseBytes) {
        this.gate = gate;
        this.logger = logger;
        this.timeoutMillis = Math.max(250L, timeoutMillis);
        this.maxResponseBytes = Math.max(1024, maxResponseBytes);
        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(this.timeoutMillis))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    public int maxResponseBytes() {
        return maxResponseBytes;
    }

    public long timeoutMillis() {
        return timeoutMillis;
    }

    /** GET a URL. Security is checked before the request is made. */
    public Response get(String url, ExecContext context) {
        return request("GET", url, null, "text/plain", false, context);
    }

    /** POST a body, used for webhooks and simple APIs. */
    public Response post(String url, String body, String contentType, boolean webhook, ExecContext context) {
        return request("POST", url, body, contentType, webhook, context);
    }

    /**
     * Performs one request. Any {@link io.astra.runtime.ActionFailure} thrown by the
     * security gate propagates unchanged, so a denial stops the rule with the gate's
     * own explanation instead of turning into a generic HTTP error.
     */
    public Response request(String method, String url, String body, String contentType, boolean webhook,
                            ExecContext context) {
        gate.checkHttp(url, webhook, context);
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException error) {
            return new Response(false, 0, "", 0, "malformed URL: " + redact(url));
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return new Response(false, 0, "", 0, "only http and https URLs are supported: " + redact(url));
        }

        long started = System.nanoTime();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(timeoutMillis))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, text/plain, */*");
            if ("POST".equalsIgnoreCase(method)) {
                builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
                builder.header("Content-Type", contentType == null ? "text/plain; charset=utf-8" : contentType);
            } else {
                builder.GET();
            }

            HttpResponse<java.io.InputStream> response = client.send(builder.build(),
                HttpResponse.BodyHandlers.ofInputStream());
            byte[] payload;
            try (var stream = response.body()) {
                payload = stream.readNBytes(maxResponseBytes + 1);
            }
            long elapsed = (System.nanoTime() - started) / 1_000_000L;
            if (payload.length > maxResponseBytes) {
                String error = "response larger than " + maxResponseBytes
                    + " bytes (security.limits.max-http-response-size)";
                logger.debug("HTTP " + method + " " + redact(url) + " aborted: " + error);
                return new Response(false, response.statusCode(), "", elapsed, error);
            }
            boolean ok = response.statusCode() >= 200 && response.statusCode() < 400;
            String text = new String(payload, java.nio.charset.StandardCharsets.UTF_8);
            logger.debug("HTTP " + method + " " + redact(url) + " -> " + response.statusCode()
                + " (" + payload.length + " bytes, " + elapsed + "ms)");
            return new Response(ok, response.statusCode(), text, elapsed,
                ok ? "" : "server answered HTTP " + response.statusCode());
        } catch (java.net.http.HttpTimeoutException timeout) {
            return new Response(false, 0, "", elapsedMillis(started),
                "timed out after " + timeoutMillis + "ms");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Response(false, 0, "", elapsedMillis(started), "cancelled");
        } catch (Exception error) {
            String message = error.getClass().getSimpleName()
                + (error.getMessage() == null ? "" : ": " + error.getMessage());
            logger.debug("HTTP " + method + " " + redact(url) + " failed: " + message);
            return new Response(false, 0, "", elapsedMillis(started), message);
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    /**
     * Removes {@code user:password@} from a URL so it can appear in logs, command
     * output and diagnostics without leaking credentials.
     */
    public static String redact(String url) {
        if (url == null || url.isBlank()) return "";
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) return url;
        int authorityStart = schemeEnd + 3;
        int authorityEnd = url.length();
        for (int index = authorityStart; index < url.length(); index++) {
            char character = url.charAt(index);
            if (character == '/' || character == '?' || character == '#') {
                authorityEnd = index;
                break;
            }
        }
        String authority = url.substring(authorityStart, authorityEnd);
        int at = authority.lastIndexOf('@');
        if (at < 0) return url;
        String user = authority.substring(0, at);
        String host = authority.substring(at + 1);
        int colon = user.indexOf(':');
        String safeUser = colon < 0 ? user : user.substring(0, colon);
        return url.substring(0, authorityStart) + safeUser + ":***@" + host + url.substring(authorityEnd);
    }

    /** JSON string escaping for webhook payloads. */
    public static String jsonEscape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (character < 0x20) {
                        out.append(String.format("\\u%04x", (int) character));
                    } else {
                        out.append(character);
                    }
                }
            }
        }
        return out.toString();
    }

    /** The {@code {\"content\": ...}} payload Discord-style webhooks expect. */
    public static String webhookPayload(String message) {
        return "{\"content\":\"" + jsonEscape(message == null ? "" : message) + "\"}";
    }
}
