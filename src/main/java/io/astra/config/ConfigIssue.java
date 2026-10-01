package io.astra.config;

import java.util.List;

/**
 * A single problem found while loading a configuration file.
 *
 * <p>Issues never abort startup on their own: invalid values fall back to the
 * documented default and are reported so the server owner can fix them. This is
 * what keeps a typo in {@code storage.yml} from preventing the plugin from
 * loading while still being visible and actionable.</p>
 *
 * @param severity   how serious the problem is
 * @param file       the configuration file name (for example {@code storage.yml})
 * @param path       the dotted YAML path (for example {@code storage.mysql.host})
 * @param message    human readable description of the problem
 * @param suggestions possible corrections
 */
public record ConfigIssue(Severity severity, String file, String path, String message, List<String> suggestions) {

    /** Issue severities used by the configuration validator. */
    public enum Severity {
        /** The value was missing and the documented default is used. */
        MISSING,
        /** The value could not be interpreted; the default is used instead. */
        WARNING,
        /** The value is unusable and the dependent feature is disabled. */
        ERROR
    }

    public ConfigIssue {
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
    }

    public static ConfigIssue warning(String file, String path, String message) {
        return new ConfigIssue(Severity.WARNING, file, path, message, List.of());
    }

    public static ConfigIssue error(String file, String path, String message) {
        return new ConfigIssue(Severity.ERROR, file, path, message, List.of());
    }

    public static ConfigIssue missing(String file, String path, String usedDefault) {
        return new ConfigIssue(Severity.MISSING, file, path,
            "missing, using default '" + usedDefault + "'", List.of());
    }

    /** One-line rendering used in the startup summary. */
    public String describe() {
        String base = file + " -> " + path + ": " + message;
        return suggestions.isEmpty() ? base : base + " (did you mean: " + String.join(", ", suggestions) + "?)";
    }
}
