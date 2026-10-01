package io.astra.runtime.script;

import io.astra.config.AstraSettings.Features;
import io.astra.language.compiler.AstraCompiler;
import io.astra.language.compiler.CompilationResult;
import io.astra.language.diagnostics.Diagnostic;
import io.astra.language.diagnostics.DiagnosticCollector;
import io.astra.language.diagnostics.Severity;
import io.astra.language.parser.AstraParser;
import io.astra.language.parser.ParseResult;
import io.astra.logging.AstraLogger;
import io.astra.runtime.Registries;
import io.astra.runtime.builtin.MaterialTable;
import io.astra.runtime.vocab.BuiltinVocabulary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code config.yml -> features} has to be enforced at load time, not merely recorded:
 * a script that uses a disabled capability must be refused with a diagnostic that names
 * the config key, while the same script loads fine when the switch is on.
 */
class FeatureGateTest {

    private static final String ECONOMY_SCRIPT = """
        on player join:
            pay 10 to player
        """;

    private static final String HTTP_SCRIPT = """
        on player join:
            http get "https://example.com/api" as answer
        """;

    private final AstraLogger logger = new AstraLogger(message -> { });

    /** Parses and compiles exactly like {@code ScriptManager.compile} does. */
    private static CompilationResult compileScript(String source) {
        Registries registries = Registries.builtins();
        BuiltinVocabulary vocabulary = new BuiltinVocabulary(registries, MaterialTable.shared(), true, true);
        DiagnosticCollector collector = new DiagnosticCollector();
        collector.setFile("gate-test.ar");
        ParseResult parsed = new AstraParser(collector, vocabulary).parse(source, "gate-test.ar");
        if (collector.hasErrors()) {
            return CompilationResult.failure(collector, "");
        }
        CompilationResult compiled = new AstraCompiler(registries).compile(parsed);
        assertTrue(compiled.success(), "the test script must compile; diagnostics: " + collector.diagnostics());
        return compiled;
    }

    private static Features featuresWith(boolean economy, boolean http, boolean webhooks) {
        return new Features(true, true, true, true, true, true, economy, true, true, true, true, true, true, true,
            webhooks, http, true);
    }

    @Test
    void theCompilerRecordsTheFeatureAScriptNeeds() {
        CompilationResult economy = compileScript(ECONOMY_SCRIPT);
        assertTrue(economy.script().requiredFeatures().contains("economy"),
            "economy actions must record the economy feature: " + economy.script().requiredFeatures());

        CompilationResult http = compileScript(HTTP_SCRIPT);
        assertTrue(http.script().requiredFeatures().contains("http"),
            "http actions must record the http feature: " + http.script().requiredFeatures());
    }

    @Test
    void aDisabledFeatureRefusesTheScriptWithAnActionableDiagnostic() {
        CompilationResult compiled = compileScript(ECONOMY_SCRIPT);
        DiagnosticCollector diagnostics = new DiagnosticCollector();
        diagnostics.setFile("gate-test.ar");

        boolean allowed = ScriptManager.featureProblems(featuresWith(false, true, true),
            compiled.script(), diagnostics, logger);

        assertFalse(allowed, "a script that needs a disabled feature must not be activated");
        List<Diagnostic> errors = diagnostics.errors();
        assertEquals(1, errors.size());
        Diagnostic error = errors.get(0);
        assertEquals(Severity.ERROR, error.severity());
        assertTrue(error.message().contains("economy"), error.message());
        assertTrue(error.explanation().contains("features.economy"), error.explanation());
        assertTrue(error.suggestions().stream().anyMatch(s -> s.contains("features.economy: true")),
            "the suggestion must name the exact config key: " + error.suggestions());
    }

    @Test
    void enabledFeaturesLoadWithoutAComplaint() {
        CompilationResult compiled = compileScript(ECONOMY_SCRIPT);
        DiagnosticCollector diagnostics = new DiagnosticCollector();
        assertTrue(ScriptManager.featureProblems(featuresWith(true, true, true), compiled.script(),
            diagnostics, logger));
        assertTrue(diagnostics.errors().isEmpty());
    }

    @Test
    void webhooksAndHttpAreSeparateSwitches() {
        CompilationResult http = compileScript(HTTP_SCRIPT);
        DiagnosticCollector diagnostics = new DiagnosticCollector();
        assertFalse(ScriptManager.featureProblems(featuresWith(true, false, true), http.script(),
            diagnostics, logger), "features.http: false must refuse http-request");
        assertTrue(diagnostics.errors().get(0).explanation().contains("features.http"));

        DiagnosticCollector second = new DiagnosticCollector();
        assertTrue(ScriptManager.featureProblems(featuresWith(true, true, true), http.script(), second, logger));
    }
}
