package io.astra.config;

import io.astra.logging.AstraLogger;
import io.astra.util.FileUtil;
import io.astra.util.Hash;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Installs and maintains the nine supplied configuration files.
 *
 * <p>Guarantees required by the project specification:</p>
 * <ul>
 *   <li>An existing file is never rewritten or reset - a user-edited file keeps every
 *       value and every comment they wrote.</li>
 *   <li>Keys that exist in the shipped default but are absent from the user file are
 *       appended in a clearly marked block at the end of the file (textual append, so
 *       the rest of the file stays byte-identical).</li>
 *   <li>The version/hash state of every file is recorded in a sidecar file
 *       ({@code data/config-state.yml}) so migrations can be applied deterministically
 *       without injecting extra keys into the user's files.</li>
 * </ul>
 */
public final class ConfigUpdater {

    /** Structure version of the shipped configuration files. */
    public static final int CONFIG_SCHEMA_VERSION = 1;

    private static final String STATE_FILE = "config-state.yml";

    private final ClassLoader loader;
    private final Path dataFolder;
    private final AstraLogger logger;

    public ConfigUpdater(ClassLoader loader, Path dataFolder, AstraLogger logger) {
        this.loader = loader;
        this.dataFolder = dataFolder;
        this.logger = logger;
    }

    /** Copy {@code <name>} from the jar when the user does not have it yet. */
    public boolean installDefault(String name) throws IOException {
        Path target = dataFolder.resolve(name);
        if (Files.exists(target)) return false;
        String content = FileUtil.readResource(loader, name);
        if (content == null) {
            logger.warn("Bundled default for " + name + " is missing from the jar; skipped");
            return false;
        }
        FileUtil.writeAtomic(target, content);
        logger.debug("Installed default configuration " + name);
        return true;
    }

    /**
     * Append keys that the shipped default defines but the user file does not, in a
     * marked block. Returns the list of added key paths.
     */
    public List<String> injectMissingKeys(String name) throws IOException {
        Path target = dataFolder.resolve(name);
        if (!Files.exists(target)) return List.of();

        String defaultText = FileUtil.readResource(loader, name);
        if (defaultText == null) return List.of();

        String userText = FileUtil.read(target);
        YamlConfiguration defaults = parseOrNull(defaultText);
        YamlConfiguration user = parseOrNull(userText);
        if (defaults == null || user == null) return List.of();

        Set<String> defaultKeys = leafKeys(defaults);
        Set<String> userKeys = leafKeys(user);
        Set<String> missing = new TreeSet<>(defaultKeys);
        missing.removeAll(userKeys);
        if (missing.isEmpty()) return List.of();

        String block = renderMissingBlock(defaults, missing, user);
        if (block.isEmpty()) return List.of();

        String separator = userText.endsWith(System.lineSeparator()) || userText.endsWith("\n") ? "" : System.lineSeparator();
        FileUtil.writeAtomic(target, userText + separator + block);
        logger.info("Added " + missing.size() + " new key(s) to " + name + " (existing values untouched)");
        return new ArrayList<>(missing);
    }

    /** Record hashes/versions for every managed file in the sidecar state file. */
    public void recordState(List<String> fileNames, String pluginVersion) throws IOException {
        Path state = stateFile();
        YamlConfiguration yaml = Files.exists(state) ? parseOrNull(FileUtil.read(state)) : new YamlConfiguration();
        if (yaml == null) yaml = new YamlConfiguration();
        for (String name : fileNames) {
            Path file = dataFolder.resolve(name);
            yaml.set("files." + name + ".hash", Files.exists(file) ? Hash.sha256(file) : "missing");
            yaml.set("files." + name + ".schema", CONFIG_SCHEMA_VERSION);
            yaml.set("files." + name + ".checked-at", Instant.now().toString());
        }
        yaml.set("plugin-version", pluginVersion);
        yaml.set("schema-version", CONFIG_SCHEMA_VERSION);
        FileUtil.writeAtomic(state, yaml.saveToString());
    }

    /** The recorded schema version for a file, or {@code 0} when unknown. */
    public int recordedSchema(String name) {
        Path state = stateFile();
        if (!Files.exists(state)) return 0;
        try {
            YamlConfiguration yaml = parseOrNull(FileUtil.read(state));
            return yaml == null ? 0 : yaml.getInt("files." + name + ".schema", 0);
        } catch (IOException e) {
            return 0;
        }
    }

    /** True when the user file differs from the shipped default (diagnostics only). */
    public boolean isUserModified(String name) {
        String defaultText = FileUtil.readResource(loader, name);
        Path target = dataFolder.resolve(name);
        if (defaultText == null || !Files.exists(target)) return false;
        return !Hash.sha256(defaultText).equals(Hash.sha256(target));
    }

    public Path stateFile() {
        return dataFolder.resolve("data").resolve(STATE_FILE);
    }

    // ------------------------------------------------------------------ helpers

    private static YamlConfiguration parseOrNull(String text) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text == null ? "" : text);
            return yaml;
        } catch (InvalidConfigurationException e) {
            return null;
        }
    }

    private static Set<String> leafKeys(ConfigurationSection section) {
        Set<String> out = new TreeSet<>();
        collect(section, "", out);
        return out;
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

    private static String renderMissingBlock(YamlConfiguration defaults, Set<String> missing, YamlConfiguration user) {
        StringBuilder sb = new StringBuilder();
        sb.append(System.lineSeparator())
          .append("# ------------------------------------------------------------------").append(System.lineSeparator())
          .append("# Added by AstraSyntax: keys present in the shipped default configuration").append(System.lineSeparator())
          .append("# that were missing from this file. Existing values were not modified.").append(System.lineSeparator())
          .append("# ------------------------------------------------------------------").append(System.lineSeparator());

        // Group top-level sections so the appended block stays readable.
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (String key : missing) {
            int dot = key.indexOf('.');
            String group = dot < 0 ? "" : key.substring(0, dot);
            grouped.computeIfAbsent(group, g -> new ArrayList<>()).add(key);
        }
        boolean wrote = false;
        for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
            boolean groupIsLeaf = entry.getKey().isEmpty();
            if (!groupIsLeaf) {
                boolean sectionExists = user.isConfigurationSection(entry.getKey());
                if (!sectionExists) {
                    sb.append(System.lineSeparator()).append(entry.getKey()).append(':').append(System.lineSeparator());
                    for (String key : entry.getValue()) {
                        writeLeaf(sb, key.substring(entry.getKey().length() + 1), defaults.get(key), "  ");
                        wrote = true;
                    }
                    continue;
                }
                sb.append(System.lineSeparator());
                for (String key : entry.getValue()) {
                    writeLeaf(sb, key.substring(entry.getKey().length() + 1), defaults.get(key), "  ");
                    wrote = true;
                }
            } else {
                sb.append(System.lineSeparator());
                for (String key : entry.getValue()) {
                    writeLeaf(sb, key, defaults.get(key), "");
                    wrote = true;
                }
            }
        }
        return wrote ? sb.toString() : "";
    }

    private static void writeLeaf(StringBuilder sb, String key, Object value, String indent) {
        if (value instanceof List<?> list) {
            sb.append(indent).append(key).append(':').append(System.lineSeparator());
            for (Object element : list) {
                sb.append(indent).append("  - ").append(scalar(element)).append(System.lineSeparator());
            }
        } else {
            sb.append(indent).append(key).append(": ").append(scalar(value)).append(System.lineSeparator());
        }
    }

    private static String scalar(Object value) {
        if (value == null) return "''";
        if (value instanceof Boolean || value instanceof Number) return String.valueOf(value);
        String text = String.valueOf(value);
        if (text.isEmpty() || text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false")
            || text.equalsIgnoreCase("null") || text.equals("~")
            || text.startsWith(" ") || text.endsWith(" ")
            || text.contains(": ") || text.contains("#") || text.contains("'") || text.contains("\"")) {
            return "'" + text.replace("'", "''") + "'";
        }
        return text;
    }
}
