package io.astra.language.nl;

import io.astra.language.ast.Expr;
import io.astra.language.compiler.AstraCompiler;
import io.astra.language.compiler.CompilationResult;
import io.astra.language.diagnostics.Diagnostic;
import io.astra.language.diagnostics.DiagnosticCollector;
import io.astra.language.diagnostics.Severity;
import io.astra.language.parser.AstraParser;
import io.astra.language.parser.ParseResult;
import io.astra.runtime.Registries;
import io.astra.runtime.Value;
import io.astra.runtime.builtin.MaterialTable;
import io.astra.runtime.script.CompiledRule;
import io.astra.runtime.script.CompiledStmt;
import io.astra.runtime.script.RuleKind;
import io.astra.runtime.vocab.BuiltinVocabulary;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for the natural-language front end.
 *
 * <p>The point of these tests is that a sentence must not merely "compile": it must
 * produce the same runtime rules and the same actions a structured rule would, with the
 * author's text intact. An empty body used to pass the old smoke tests, which is exactly
 * the kind of silent drop this suite now prevents.</p>
 */
class NaturalLanguageCoverageTest {

    private static final Registries REGISTRIES = Registries.builtins();

    private static CompilationResult compileNatural(String sentence) {
        DiagnosticCollector collector = new DiagnosticCollector();
        collector.setFile("coverage.ar");
        BuiltinVocabulary vocabulary = new BuiltinVocabulary(REGISTRIES, MaterialTable.shared(), true, true);
        ParseResult parsed = new AstraParser(collector, vocabulary).parse(sentence, "coverage.ar");
        assertFalse(collector.hasErrors(), "the sentence must parse: " + sentence + " -> "
            + collector.diagnostics());
        CompilationResult compiled = new AstraCompiler(REGISTRIES).compile(parsed);
        assertTrue(compiled.success(), "the sentence must compile: " + sentence + " -> " + compiled.errors());
        return compiled;
    }

    private static CompiledRule onlyRule(CompilationResult result) {
        assertEquals(1, result.script().rules().size(), "expected exactly one rule");
        return result.script().rules().get(0);
    }

    /** All action ids in a rule body, including nested branches. */
    private static List<String> actionIds(CompiledStmt statement) {
        List<String> ids = new ArrayList<>();
        if (statement instanceof CompiledStmt.Action action) {
            ids.add(action.definition().id());
        } else if (statement instanceof CompiledStmt.If branch) {
            branch.then().forEach(nested -> ids.addAll(actionIds(nested)));
            branch.otherwise().forEach(nested -> ids.addAll(actionIds(nested)));
        } else if (statement instanceof CompiledStmt.Repeat loop) {
            loop.body().forEach(nested -> ids.addAll(actionIds(nested)));
        }
        return ids;
    }

    private static List<String> actionIds(CompiledRule rule) {
        List<String> ids = new ArrayList<>();
        rule.body().forEach(statement -> ids.addAll(actionIds(statement)));
        return ids;
    }

    /** The text of every literal argument in the rule, for message-fidelity checks. */
    private static List<String> literals(CompiledStmt statement) {
        List<String> values = new ArrayList<>();
        if (statement instanceof CompiledStmt.Action action) {
            for (Expr argument : action.arguments().values()) {
                if (argument instanceof Expr.Lit lit && lit.value() instanceof Value.Str text) {
                    values.add(text.value());
                }
                if (argument instanceof Expr.Item itemExpression
                    && itemExpression.material() instanceof Expr.Lit material
                    && material.value() instanceof Value.Str text) {
                    values.add(text.value());
                }
            }
        } else if (statement instanceof CompiledStmt.If branch) {
            branch.then().forEach(nested -> values.addAll(literals(nested)));
            branch.otherwise().forEach(nested -> values.addAll(literals(nested)));
        } else if (statement instanceof CompiledStmt.Repeat loop) {
            loop.body().forEach(nested -> values.addAll(literals(nested)));
        }
        return values;
    }

    @Test
    void everySupportedSentenceProducesRulesWithRealBodies() {
        record Case(String sentence, RuleKind kind, String trigger, String... actions) { }
        List<Case> cases = List.of(
            new Case("When a player joins, tell them \"Welcome!\"", RuleKind.EVENT, "player join", "tell"),
            new Case("When a player leaves, broadcast \"Goodbye\"", RuleKind.EVENT, "player quit", "broadcast"),
            new Case("When a player dies, tell them \"You died\"", RuleKind.EVENT, "player death", "tell"),
            new Case("When a player respawns, tell them \"Welcome back\"", RuleKind.EVENT, "player respawn", "tell"),
            new Case("When a player kills a zombie, give them 5 coins", RuleKind.EVENT, "player kills entity",
                "add-data"),
            new Case("When a zombie dies, tell everyone \"Zombie down\"", RuleKind.EVENT, "entity death", "tell"),
            new Case("When a player breaks a diamond ore, give them 1 diamond", RuleKind.EVENT, "block break",
                "give"),
            new Case("When a player places a block, log \"placed\"", RuleKind.EVENT, "block place", "log"),
            new Case("When a player chats, cancel the event if the message contains badword",
                RuleKind.EVENT, "player chat", "cancel"),
            new Case("Every 5 minutes, tell everyone \"Hello\"", RuleKind.TIMER, "timer", "tell"),
            new Case("Make /heal heal the player who runs it", RuleKind.COMMAND, "heal", "heal"));

        for (Case testCase : cases) {
            CompilationResult result = compileNatural(testCase.sentence());
            if (testCase.kind() == RuleKind.COMMAND) {
                // Sentences that declare a command compile to a command, not to a rule.
                assertEquals(1, result.script().commands().size(), testCase.sentence());
                var command = result.script().commands().get(0);
                assertEquals(testCase.trigger(), command.name(), testCase.sentence());
                List<String> commandIds = new ArrayList<>();
                command.body().forEach(statement -> commandIds.addAll(actionIds(statement)));
                for (String expected : testCase.actions()) {
                    assertTrue(commandIds.contains(expected),
                        testCase.sentence() + " must run '" + expected + "' but runs " + commandIds);
                }
                continue;
            }
            CompiledRule rule = onlyRule(result);
            assertEquals(testCase.kind(), rule.kind(), testCase.sentence());
            assertEquals(testCase.trigger(), rule.triggerId(), testCase.sentence());
            assertTrue(rule.natural(), testCase.sentence() + " must be marked as natural language");
            List<String> ids = actionIds(rule);
            for (String expected : testCase.actions()) {
                if (expected.equals("cancel")) {
                    assertTrue(rule.body().stream().anyMatch(CompiledStmt.Cancel.class::isInstance),
                        testCase.sentence() + " must cancel the event");
                } else {
                    assertTrue(ids.contains(expected),
                        testCase.sentence() + " must run '" + expected + "' but runs " + ids);
                }
            }
        }
    }

    @Test
    void firstTimeJoinRemembersTheVisit() {
        CompilationResult result = compileNatural(
            "When a player joins for the first time, welcome them and give them 5 diamonds.");
        CompiledRule rule = onlyRule(result);
        assertEquals("player join", rule.triggerId());
        // The sentence must run its actions behind a "not seen before" guard and record
        // the visit, otherwise "for the first time" means nothing.
        List<String> ids = actionIds(rule);
        assertTrue(ids.contains("tell"), "the welcome must be there: " + ids);
        assertTrue(ids.contains("give"), "the diamonds must be there: " + ids);
        assertTrue(ids.contains("set-data"), "the visit must be remembered: " + ids);
        assertEquals(1, rule.body().stream().filter(CompiledStmt.If.class::isInstance).count(),
            "the first-time rule must be guarded by an if");
    }

    @Test
    void messagesKeepTheAuthorsCapitalisation() {
        CompiledRule rule = onlyRule(compileNatural("When a player joins, tell them \"Welcome!\""));
        List<String> texts = new ArrayList<>();
        rule.body().forEach(statement -> texts.addAll(literals(statement)));
        assertTrue(texts.contains("Welcome!"), "message text must keep its case: " + texts);

        CompiledRule shout = onlyRule(compileNatural("When a player leaves, broadcast \"Goodbye\""));
        List<String> shoutTexts = new ArrayList<>();
        shout.body().forEach(statement -> shoutTexts.addAll(literals(statement)));
        assertTrue(shoutTexts.contains("Goodbye"), "broadcast text must keep its case: " + shoutTexts);
    }

    @Test
    void stopGriefingSentencesCancelTheRightEvent() {
        CompiledRule creepers = onlyRule(compileNatural("Stop creepers from destroying blocks."));
        assertEquals("entity explodes", creepers.triggerId());
        assertTrue(creepers.body().stream().anyMatch(CompiledStmt.Cancel.class::isInstance));
    }

    @Test
    void unsupportedSentencesAreReportedNotSilentlyDropped() {
        DiagnosticCollector collector = new DiagnosticCollector();
        collector.setFile("coverage.ar");
        BuiltinVocabulary vocabulary = new BuiltinVocabulary(REGISTRIES, MaterialTable.shared(), true, true);
        new AstraParser(collector, vocabulary)
            .parse("When it rains, tell everyone \"It is raining\"", "coverage.ar");
        assertTrue(collector.hasErrors());
        Diagnostic diagnostic = collector.errors().get(0);
        assertEquals(Severity.ERROR, diagnostic.severity());
        assertTrue(diagnostic.message().contains("could not understand"), diagnostic.message());
        assertFalse(diagnostic.suggestions().isEmpty(), "the author must get example sentences back");
    }

    @Test
    void bothAuthoringModesProduceTheSameRuntimeRule() {
        // The same intent written twice must compile to the same trigger and action.
        CompiledRule natural = onlyRule(compileNatural("When a player breaks a diamond ore, give them 1 diamond"));
        CompilationResult structured = compileStructured("""
            on block break:
                give 1 diamond to player
            """);
        CompiledRule explicit = onlyRule(structured);
        assertEquals(explicit.triggerId(), natural.triggerId());
        assertEquals(actionIds(explicit), actionIds(natural));
        assertTrue(explicit.natural() == false && natural.natural());
    }

    private static CompilationResult compileStructured(String source) {
        DiagnosticCollector collector = new DiagnosticCollector();
        collector.setFile("coverage.ar");
        BuiltinVocabulary vocabulary = new BuiltinVocabulary(REGISTRIES, MaterialTable.shared(), true, true);
        ParseResult parsed = new AstraParser(collector, vocabulary).parse(source, "coverage.ar");
        assertFalse(collector.hasErrors(), "structured source must parse: " + collector.diagnostics());
        CompilationResult compiled = new AstraCompiler(REGISTRIES).compile(parsed);
        assertTrue(compiled.success(), "structured source must compile: " + compiled.errors());
        return compiled;
    }

}
