package io.astra.logging;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes credentials from anything AstraSyntax prints.
 *
 * <p>Two complementary strategies are used:</p>
 * <ol>
 *   <li>key/value patterns such as {@code password=secret} or {@code "token": "..."}</li>
 *   <li>connection URIs that embed credentials ({@code jdbc:mysql://user:pass@host})</li>
 * </ol>
 */
public final class LogRedactor {

    public static final String MASK = "******";

    private static final Pattern KEY_VALUE = Pattern.compile(
        "(?i)\\b(pass(?:word)?|pwd|secret|token|api[-_]?key|access[-_]?key|credential[s]?|auth)\\b"
        + "(\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s,;}&]+)");

    private static final Pattern URI_CREDENTIALS = Pattern.compile(
        "(?i)\\b([a-z][a-z0-9+.-]*://[^\\s:/@]+):([^\\s/@]+)@");

    private LogRedactor() {}

    /** Mask credentials inside a message. Never throws, never returns null. */
    public static String redact(String message) {
        if (message == null || message.isEmpty()) return message;
        String result = KEY_VALUE.matcher(message).replaceAll(m -> m.group(1) + m.group(2) + MASK);
        Matcher uri = URI_CREDENTIALS.matcher(result);
        if (uri.find()) {
            result = uri.replaceAll("$1:" + MASK + "@");
        }
        return result;
    }

    /** Mask a single secret value (used for password fields read from config). */
    public static String mask(String secret) {
        if (secret == null || secret.isEmpty()) return "";
        return MASK;
    }

    /** Render a JDBC target without credentials, for diagnostics and logs. */
    public static String safeJdbcTarget(String jdbcUrl) {
        if (jdbcUrl == null) return "none";
        return URI_CREDENTIALS.matcher(jdbcUrl).replaceAll("$1:" + MASK + "@");
    }

    /** True when the text looks like it contains a credential (defensive check). */
    public static boolean containsSecret(String message) {
        return message != null && (KEY_VALUE.matcher(message).find() || URI_CREDENTIALS.matcher(message).find());
    }

    /** Convenience for tests and diagnostics. */
    public static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
