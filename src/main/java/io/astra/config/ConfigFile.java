package io.astra.config;

import io.astra.util.Strings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * One of the nine supplied configuration files plus typed, validating accessors.
 *
 * <p>The class never rewrites a file the user may have edited: it only reads it and
 * records {@link ConfigIssue}s. Adding keys that exist in the shipped default but
 * not in the user file is handled textually by {@link ConfigUpdater} so comments
 * and formatting survive.</p>
 */
public final class ConfigFile {

    private final String name;
    private final Path path;
    private final YamlConfiguration yaml = new YamlConfiguration();
    private final List<ConfigIssue> issues = new ArrayList<>();
    private final String rawText;
    private final boolean loaded;

    private ConfigFile(String name, Path path, String rawText, boolean loaded) {
        this.name = name;
        this.path = path;
        this.rawText = rawText == null ? "" : rawText;
        this.loaded = loaded;
    }

    /**
     * Load a configuration file. When the file is missing or corrupt, an empty
     * configuration is used and the problem is recorded as an issue.
     */
    public static ConfigFile load(String name, Path path) {
        String text = null;
        try {
            if (Files.exists(path)) text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            text = null;
        }
        ConfigFile file = new ConfigFile(name, path, text, text != null);
        if (text != null) {
            try {
                file.yaml.loadFromString(text);
            } catch (InvalidConfigurationException e) {
                file.issues.add(ConfigIssue.error(name, "<file>",
                    "invalid YAML (" + e.getMessage() + ") - defaults are in use for this file"));
            }
        }
        return file;
    }

    /** Build an in-memory configuration (used by tests and defaults comparison). */
    public static ConfigFile inMemory(String name, String yamlText) {
        ConfigFile file = new ConfigFile(name, null, yamlText, true);
        try {
            file.yaml.loadFromString(yamlText);
        } catch (InvalidConfigurationException e) {
            file.issues.add(ConfigIssue.error(name, "<memory>", "invalid YAML: " + e.getMessage()));
        }
        return file;
    }

    public String name() {
        return name;
    }

    public Path path() {
        return path;
    }

    public YamlConfiguration yaml() {
        return yaml;
    }

    public String rawText() {
        return rawText;
    }

    public boolean loadedFromDisk() {
        return loaded;
    }

    public List<ConfigIssue> issues() {
        return Collections.unmodifiableList(issues);
    }

    public void addIssue(ConfigIssue issue) {
        issues.add(issue);
    }

    // ------------------------------------------------------------------
    // Typed accessors with default fallbacks
    // ------------------------------------------------------------------

    public String getString(String key, String fallback) {
        Object value = yaml.get(key);
        if (value == null) {
            issues.add(ConfigIssue.missing(name, key, fallback));
            return fallback;
        }
        return String.valueOf(value);
    }

    public boolean getBoolean(String key, boolean fallback) {
        Object value = yaml.get(key);
        if (value == null) {
            issues.add(ConfigIssue.missing(name, key, String.valueOf(fallback)));
            return fallback;
        }
        if (value instanceof Boolean b) return b;
        String text = String.valueOf(value).trim();
        if (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false")) return Boolean.parseBoolean(text);
        issues.add(new ConfigIssue(ConfigIssue.Severity.WARNING, name, key,
            "'" + text + "' is not a boolean, using " + fallback, List.of("true", "false")));
        return fallback;
    }

    public int getInt(String key, int fallback, int min, int max) {
        Object value = yaml.get(key);
        if (value == null) {
            issues.add(ConfigIssue.missing(name, key, String.valueOf(fallback)));
            return fallback;
        }
        int parsed;
        if (value instanceof Number n) {
            parsed = n.intValue();
        } else {
            try {
                parsed = Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException e) {
                issues.add(new ConfigIssue(ConfigIssue.Severity.WARNING, name, key,
                    "'" + value + "' is not a whole number, using " + fallback,
                    List.of(String.valueOf(fallback))));
                return fallback;
            }
        }
        if (parsed < min || parsed > max) {
            issues.add(new ConfigIssue(ConfigIssue.Severity.WARNING, name, key,
                parsed + " is outside the allowed range " + min + ".." + max + ", using "
                    + Math.max(min, Math.min(max, parsed)), List.of()));
            return Math.max(min, Math.min(max, parsed));
        }
        return parsed;
    }

    public double getDouble(String key, double fallback, double min, double max) {
        Object value = yaml.get(key);
        if (value == null) {
            issues.add(ConfigIssue.missing(name, key, String.valueOf(fallback)));
            return fallback;
        }
        double parsed;
        if (value instanceof Number n) {
            parsed = n.doubleValue();
        } else {
            try {
                parsed = Double.parseDouble(String.valueOf(value).trim());
            } catch (NumberFormatException e) {
                issues.add(new ConfigIssue(ConfigIssue.Severity.WARNING, name, key,
                    "'" + value + "' is not a number, using " + fallback, List.of()));
                return fallback;
            }
        }
        if (parsed < min || parsed > max) {
            issues.add(new ConfigIssue(ConfigIssue.Severity.WARNING, name, key,
                parsed + " is outside the allowed range " + min + ".." + max, List.of()));
            return Math.max(min, Math.min(max, parsed));
        }
        return parsed;
    }

    public List<String> getStringList(String key, List<String> fallback) {
        Object value = yaml.get(key);
        if (value == null) {
            issues.add(ConfigIssue.missing(name, key, String.valueOf(fallback)));
            return fallback;
        }
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object o : list) if (o != null) out.add(String.valueOf(o));
        } else {
            out.add(String.valueOf(value));
        }
        return out;
    }

    /**
     * Enum-like accessor: returns the matching constant or the fallback, recording a
     * suggestion list built from the accepted values (used for keys such as
     * {@code storage.type} and {@code logging.level}).
     */
    public String getChoice(String key, String fallback, List<String> accepted) {
        Object value = yaml.get(key);
        if (value == null) {
            issues.add(ConfigIssue.missing(name, key, fallback));
            return fallback;
        }
        String text = String.valueOf(value).trim().toLowerCase(java.util.Locale.ROOT);
        for (String candidate : accepted) {
            if (candidate.equalsIgnoreCase(text)) return candidate;
        }
        String suggestion = Strings.closest(text, accepted, 3);
        issues.add(new ConfigIssue(ConfigIssue.Severity.WARNING, name, key,
            "'" + text + "' is not supported, using '" + fallback + "'",
            suggestion == null ? accepted : List.of(suggestion)));
        return fallback;
    }

    public ConfigurationSection getSection(String key) {
        return yaml.getConfigurationSection(key);
    }

    public boolean has(String key) {
        return yaml.contains(key);
    }

    /** All dotted leaf paths present in this file (used to detect missing keys). */
    public Set<String> leafKeys() {
        Set<String> keys = new LinkedHashSet<>();
        collect(yaml, "", keys);
        return keys;
    }

    private static void collect(ConfigurationSection section, String prefix, Set<String> out) {
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            String path = prefix.isEmpty() ? key : prefix + '.' + key;
            if (value instanceof ConfigurationSection nested) {
                collect(nested, path, out);
            } else {
                out.add(path);
            }
        }
    }
}
