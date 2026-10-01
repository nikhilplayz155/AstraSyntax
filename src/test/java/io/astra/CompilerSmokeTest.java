package io.astra;

import io.astra.language.compiler.AstraCompiler;
import io.astra.language.diagnostics.Diagnostic;
import io.astra.language.diagnostics.DiagnosticCollector;
import io.astra.language.parser.AstraParser;
import io.astra.language.parser.ParseResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test: the supplied example .ar files must parse and compile.
 *
 * <p>These are the authoritative examples shipped with AstraSyntax; if they
 * stop compiling, the language has drifted from its spec.</p>
 */
class CompilerSmokeTest {

    private static final Path EXAMPLES = Path.of(
        System.getProperty("user.dir"), "src", "test", "resources", "examples");

    private ParseResult parse(String source) {
        DiagnosticCollector diag = new DiagnosticCollector();
        AstraParser parser = new AstraParser(diag);
        ParseResult result = parser.parse(source, "<test>");
        if (!diag.isEmpty()) {
            throw new AssertionError("Parse diagnostics:\n" + diag.format());
        }
        return result;
    }

    @Test
    void welcomeParsesAndCompiles() {
        String src = read("01-welcome.ar");
        ParseResult ast = parse(src);
        AstraCompiler compiler = new AstraCompiler();
        io.astra.language.compiler.CompilationResult result = compiler.compile(ast);
        assertTrue(result.errors().isEmpty(), () -> "Compile errors: " + result.errors());
        assertFalse(result.script().rules().isEmpty(), "Expected at least one rule");
    }

    @Test
    void commandParsesAndCompiles() {
        String src = read("03-command.ar");
        ParseResult ast = parse(src);
        AstraCompiler compiler = new AstraCompiler();
        io.astra.language.compiler.CompilationResult result = compiler.compile(ast);
        assertTrue(result.errors().isEmpty(), () -> "Compile errors: " + result.errors());
    }

    @Test
    void coinsParsesAndCompiles() {
        String src = read("04-coins.ar");
        ParseResult ast = parse(src);
        AstraCompiler compiler = new AstraCompiler();
        io.astra.language.compiler.CompilationResult result = compiler.compile(ast);
        assertTrue(result.errors().isEmpty(), () -> "Compile errors: " + result.errors());
    }

    @Test
    void timerParsesAndCompiles() {
        String src = read("05-timer.ar");
        ParseResult ast = parse(src);
        AstraCompiler compiler = new AstraCompiler();
        io.astra.language.compiler.CompilationResult result = compiler.compile(ast);
        assertTrue(result.errors().isEmpty(), () -> "Compile errors: " + result.errors());
    }

    @Test
    void naturalLanguageParsesAndCompiles() {
        String src = read("02-natural-language.ar");
        ParseResult ast = parse(src);
        AstraCompiler compiler = new AstraCompiler();
        io.astra.language.compiler.CompilationResult result = compiler.compile(ast);
        assertTrue(result.errors().isEmpty(), () -> "Compile errors: " + result.errors());
    }

    @Test
    void gameplayExampleParsesAndCompiles() {
        ParseResult ast = parse(read("06-gameplay.ar"));
        AstraCompiler compiler = new AstraCompiler();
        io.astra.language.compiler.CompilationResult result = compiler.compile(ast);
        assertTrue(result.errors().isEmpty(), () -> "Compile errors: " + result.errors());
        assertFalse(result.script().items().isEmpty(), "the example declares a custom item");
        assertFalse(result.script().menus().isEmpty(), "the example declares a menu");
        assertFalse(result.script().recipes().isEmpty(), "the example declares a recipe");
        assertFalse(result.script().regions().isEmpty(), "the example declares a region");
    }

    /**
     * A blank line between two rules must not swallow the second one: the lexer has to
     * close the first block before the next top-level line is read.
     */
    @Test
    void topLevelBlocksSeparatedByBlankLinesAllCompile() {
        String source = "on player join:\n"
            + "    tell player \"hi\"\n"
            + "\n"
            + "on player quit:\n"
            + "    tell player \"bye\"\n";
        ParseResult ast = parse(source);
        io.astra.language.compiler.CompilationResult result = new AstraCompiler().compile(ast);
        assertTrue(result.errors().isEmpty(), () -> "Compile errors: " + result.errors());
        assertEquals(2, result.script().rules().size(),
            () -> "both top-level blocks must compile: " + result.script().rules());
    }

    @Test
    void typoSuggestsDiamonds() {
        String src = "on player join:\n    give player 5 dimonds\n";
        DiagnosticCollector diag = new DiagnosticCollector();
        AstraParser parser = new AstraParser(diag);
        parser.parse(src, "<test>");
        // Unknown material "dimonds" must produce a diagnostic with a suggestion.
        List<Diagnostic> diags = diag.diagnostics();
        assertFalse(diags.isEmpty(), "Expected a diagnostic for unknown material");
        boolean hasSuggestion = diags.stream()
            .flatMap(d -> d.suggestions().stream())
            .anyMatch(s -> s.contains("diamond"));
        assertTrue(hasSuggestion, "Expected a 'diamond' suggestion, got: "
            + diags.stream().map(Diagnostic::suggestions).toList());
    }

    private static String read(String name) {
        try {
            return Files.readString(EXAMPLES.resolve(name));
        } catch (Exception e) {
            throw new RuntimeException("Could not read example " + name, e);
        }
    }
}