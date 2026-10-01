package io.astra;

import io.astra.config.ConfigManager;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the packaged artifact: the plugin descriptor, the nine configuration files, the
 * example scripts and the entry-point class must all be on the class path exactly as they
 * are shipped, because a rename here is the difference between "loads on a server" and
 * "silently does nothing".
 */
class PackagingTest {

    private static final List<String> CONFIG_FILES = List.of(
        "config.yml", "storage.yml", "performance.yml", "security.yml", "language.yml",
        "modules.yml", "packages.yml", "integrations.yml", "logging.yml");

    private static final List<String> EXAMPLES = List.of(
        "examples/01-welcome.ar", "examples/02-natural-language.ar", "examples/03-command.ar",
        "examples/04-coins.ar", "examples/05-timer.ar", "examples/06-gameplay.ar");

    private static String resource(String name) {
        try (InputStream in = PackagingTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, "missing packaged resource: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new AssertionError("could not read packaged resource " + name, error);
        }
    }

    @Test
    void pluginDescriptorDeclaresWhatTheServerNeeds() {
        String plugin = resource("plugin.yml");
        assertTrue(plugin.contains("name: AstraSyntax"), plugin);
        assertTrue(plugin.contains("main: io.astra.plugin.AstraPlugin"),
            "the declared main class must be the real bootstrap class");
        assertTrue(plugin.contains("api-version: '1.21'"), plugin);
        assertTrue(plugin.contains("folia-supported: true"),
            "Folia only loads plugins that declare support");
        assertTrue(plugin.contains("astra.command"), "the base permission must be declared");
        for (String subcommand : List.of("reload", "load", "unload", "scripts", "check", "info", "debug",
            "explain", "trace", "performance", "errors", "package")) {
            assertTrue(plugin.contains("/astra " + subcommand),
                "plugin.yml usage must document /astra " + subcommand);
        }
    }

    @Test
    void theEntryPointClassIsPackaged() {
        assertNotNull(PackagingTest.class.getClassLoader().getResource("io/astra/plugin/AstraPlugin.class"),
            "io.astra.plugin.AstraPlugin must be in the artifact");
        assertNotNull(PackagingTest.class.getClassLoader().getResource("io/astra/data/LibLoader.class"));
    }

    @Test
    void everyConfigurationFileIsPackagedUnderTheNameTheLoaderReads() {
        assertEquals(CONFIG_FILES, ConfigManager.FILES,
            "the loader must read exactly the nine supplied configuration files");
        for (String name : CONFIG_FILES) {
            String content = resource(name);
            assertTrue(content.length() > 50, name + " looks truncated");
            assertTrue(content.lines().anyMatch(line -> line.contains(":")), name + " is not YAML");
        }
    }

    @Test
    void theSuppliedExamplesArePackagedUnchanged() {
        for (String example : EXAMPLES) {
            assertTrue(!resource(example).isBlank(), example + " is empty");
        }
        // Content the author supplied must survive packaging.
        assertTrue(resource("examples/01-welcome.ar").contains("Welcome to the server!"));
        String natural = resource("examples/02-natural-language.ar");
        assertTrue(natural.contains("for the first time"), natural);
        assertTrue(natural.contains("Stop creepers from destroying blocks"), natural);
        assertTrue(resource("examples/03-command.ar").contains("command /heal"));
        assertTrue(resource("examples/05-timer.ar").contains("every 10 minutes"));
    }

    @Test
    void securityDefaultsStayOptIn() {
        String security = resource("security.yml");
        assertTrue(security.contains("allow-console-commands: true"));
        assertTrue(security.contains("allow-file-access: false"),
            "file access must stay off by default");
        assertTrue(security.contains("allow-http-requests: false"),
            "outbound HTTP must stay off by default");
        assertTrue(security.contains("max-http-response-size: 2MB"),
            "the response-size cap the service enforces must exist in the supplied file");
    }
}
