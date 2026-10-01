package io.astra.logging;

import io.astra.util.FileUtil;
import io.astra.util.Strings;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * AstraSyntax logger.
 *
 * <p>Behaviour is driven entirely by the supplied {@code logging.yml}:</p>
 * <ul>
 *   <li>{@code logging.level} - INFO/WARN/ERROR/DEBUG filtering</li>
 *   <li>{@code logging.console} - console mirror</li>
 *   <li>{@code logging.file} / {@code logging.file-path}</li>
 *   <li>{@code errors.separate-file} / {@code errors.file-path}</li>
 *   <li>{@code debug.include-stack-traces} / {@code debug.include-script-source}</li>
 * </ul>
 *
 * <p>Two protective behaviours are built in: identical messages are rate limited so
 * a failing rule inside a repeating task cannot flood the console, and every line
 * passes through {@link LogRedactor} so credentials from {@code storage.yml} or
 * module configuration never reach a log file.</p>
 */
public final class AstraLogger {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long DUPLICATE_WINDOW_MILLIS = 60_000L;

    private final Consumer<String> consoleSink;
    private final Map<String, long[]> duplicateCounters = new ConcurrentHashMap<>();

    private volatile LogLevel level = LogLevel.INFO;
    private volatile boolean consoleEnabled = true;
    private volatile boolean fileEnabled = true;
    private volatile boolean separateErrorFile = true;
    private volatile boolean includeStackTraces = false;
    private volatile boolean includeScriptSource = true;
    private volatile Path logFile;
    private volatile Path errorFile;

    public AstraLogger(Consumer<String> consoleSink) {
        this.consoleSink = consoleSink == null ? message -> { } : consoleSink;
    }

    /** Apply the values read from {@code logging.yml}. */
    public void configure(LogLevel level, boolean console, boolean file, Path logFile,
                          boolean separateErrorFile, Path errorFile,
                          boolean includeStackTraces, boolean includeScriptSource) {
        this.level = level == null ? LogLevel.INFO : level;
        this.consoleEnabled = console;
        this.fileEnabled = file;
        this.logFile = logFile;
        this.separateErrorFile = separateErrorFile;
        this.errorFile = errorFile;
        this.includeStackTraces = includeStackTraces;
        this.includeScriptSource = includeScriptSource;
    }

    public LogLevel level() {
        return level;
    }

    /** True when DEBUG output is currently emitted. */
    public boolean isDebugEnabled() {
        return level.isEnabledAt(LogLevel.DEBUG);
    }

    public boolean includeStackTraces() {
        return includeStackTraces;
    }

    public boolean includeScriptSource() {
        return includeScriptSource;
    }

    public void info(String message) {
        log(LogLevel.INFO, message, null);
    }

    public void warn(String message) {
        log(LogLevel.WARN, message, null);
    }

    public void error(String message) {
        log(LogLevel.ERROR, message, null);
    }

    public void error(String message, Throwable cause) {
        log(LogLevel.ERROR, message, cause);
    }

    public void debug(String message) {
        log(LogLevel.DEBUG, message, null);
    }

    /** Log a message only when DEBUG is active; avoids building strings in hot paths. */
    public void debug(java.util.function.Supplier<String> messageSupplier) {
        if (isDebugEnabled()) log(LogLevel.DEBUG, messageSupplier.get(), null);
    }

    /** Log a warning at most once per minute per distinct message. */
    public void warnThrottled(String key, String message) {
        long now = System.currentTimeMillis();
        long[] state = duplicateCounters.computeIfAbsent(key, k -> new long[]{now, 0});
        boolean emit;
        long suppressed = 0;
        synchronized (state) {
            if (now - state[0] > DUPLICATE_WINDOW_MILLIS) {
                suppressed = state[1];
                state[0] = now;
                state[1] = 0;
                emit = true;
            } else {
                state[1]++;
                emit = false;
            }
        }
        if (emit) {
            log(LogLevel.WARN, suppressed > 0 ? message + " (" + suppressed + " similar messages suppressed)" : message, null);
        }
    }

    /** Core log routine; all other methods funnel through here. */
    public void log(LogLevel messageLevel, String message, Throwable cause) {
        if (!messageLevel.isEnabledAt(level)) return;
        String safe = LogRedactor.redact(message == null ? "null" : message);
        String line = "[" + messageLevel.label() + "] " + safe;
        long now = System.currentTimeMillis();
        long[] state = duplicateCounters.computeIfAbsent(messageLevel + "|" + safe, k -> new long[]{now, 0});
        long suppressed = 0;
        boolean emit;
        synchronized (state) {
            if (now - state[0] > DUPLICATE_WINDOW_MILLIS) {
                suppressed = state[1];
                state[0] = now;
                state[1] = 0;
                emit = true;
            } else if (state[1] == 0) {
                // first repeat inside the window: still count, but keep the original line visible
                state[1]++;
                emit = true;
            } else {
                state[1]++;
                emit = false;
            }
        }
        if (!emit) return;
        if (suppressed > 0) line = line + " (repeated " + suppressed + "x in the last minute)";

        if (consoleEnabled) consoleSink.accept(line);
        if (fileEnabled) {
            writeFile(resolveLogFile(), line, cause != null && !separateErrorFile);
        }
        if (messageLevel == LogLevel.ERROR && separateErrorFile && fileEnabled) {
            writeFile(resolveErrorFile(), line, true);
        }
    }

    /** Flush hook - file writers are opened per write so shutdown needs no extra work. */
    public void shutdown() {
        duplicateCounters.clear();
    }

    public Path resolveLogFile() {
        Path path = logFile;
        return path == null ? Path.of("logs", "astra.log") : path;
    }

    public Path resolveErrorFile() {
        Path path = errorFile;
        return path == null ? Path.of("logs", "errors.log") : path;
    }

    private void writeFile(Path target, String line, boolean withCause) {
        if (target == null) return;
        try {
            FileUtil.append(target, "[" + LocalDateTime.now().format(TIMESTAMP) + "] " + line);
        } catch (IOException ignored) {
            // A logging failure must never break the server; the console line was already emitted.
        }
    }

    /** Format a throwable for a log line, honouring {@code debug.include-stack-traces}. */
    public String describe(Throwable cause) {
        if (cause == null) return "";
        if (!includeStackTraces) {
            String message = cause.getMessage();
            return cause.getClass().getSimpleName() + (Strings.isBlank(message) ? "" : ": " + message);
        }
        java.io.StringWriter writer = new java.io.StringWriter();
        cause.printStackTrace(new java.io.PrintWriter(writer));
        return writer.toString();
    }
}
