package io.astra.logging;

import java.util.Locale;

/** Log levels supported by the supplied {@code logging.yml} ({@code logging.level}). */
public enum LogLevel {

    DEBUG(0, "DEBUG"),
    INFO(1, "INFO"),
    WARN(2, "WARN"),
    ERROR(3, "ERROR");

    private final int severity;
    private final String label;

    LogLevel(int severity, String label) {
        this.severity = severity;
        this.label = label;
    }

    public int severity() {
        return severity;
    }

    public String label() {
        return label;
    }

    /** True when a message at this level passes {@code configured} level filtering. */
    public boolean isEnabledAt(LogLevel configured) {
        return severity >= configured.severity;
    }

    /** Lenient parse; unknown values fall back to {@link #INFO}. */
    public static LogLevel parse(String raw) {
        if (raw == null) return INFO;
        switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "TRACE": case "DEBUG": case "FINE": case "FINEST": return DEBUG;
            case "WARN": case "WARNING": return WARN;
            case "ERROR": case "SEVERE": case "FATAL": return ERROR;
            default: return INFO;
        }
    }
}
