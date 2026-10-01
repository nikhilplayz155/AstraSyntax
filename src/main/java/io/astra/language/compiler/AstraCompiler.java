package io.astra.language.compiler;

import io.astra.language.ast.Cond;
import io.astra.language.ast.Declaration;
import io.astra.language.ast.Expr;
import io.astra.language.ast.ScriptFile;
import io.astra.language.ast.Span;
import io.astra.language.ast.Stmt;
import io.astra.language.diagnostics.Diagnostic;
import io.astra.language.diagnostics.DiagnosticCollector;
import io.astra.language.diagnostics.Severity;
import io.astra.language.parser.ParseResult;
import io.astra.runtime.Registries;
import io.astra.runtime.action.ActionDefinition;
import io.astra.runtime.condition.ConditionDefinition;
import io.astra.runtime.event.EventDefinition;
import io.astra.runtime.event.EventPropertyResolver;
import io.astra.runtime.expression.Properties;
import io.astra.runtime.script.CompiledCommand;
import io.astra.runtime.script.CompiledCondition;
import io.astra.runtime.script.CompiledFunction;
import io.astra.runtime.script.CompiledRule;
import io.astra.runtime.script.CompiledScript;
import io.astra.runtime.script.CompiledStmt;
import io.astra.runtime.script.RuleKind;
import io.astra.security.SecurityPolicy;
import io.astra.util.Hash;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a parsed {@code .ar} file into the runtime IR.
 *
 * <p>Compilation is where every name is resolved: event triggers become Bukkit event
 * classes, action statements become {@link ActionDefinition}s, conditions become
 * {@link ConditionDefinition}s, function calls become direct references and expression
 * calls become registry entries. Nothing is looked up by name while an event runs - that
 * is the whole point of the design, and it is also why a typo is reported at load time
 * with a suggestion instead of failing silently on the first event.</p>
 *
 * <p>The compiler also enforces the language contract that {@code language.yml} promises:
 * a script with any compile error produces no activatable result, so the script manager
 * can keep the previous version running.</p>
 */
public final class AstraCompiler {

    /** Action ids whose behaviour needs an optional feature from {@code config.yml}. */
    private static final Map<String, String> ACTION_FEATURES = Map.ofEntries(
        Map.entry("http-request", "http"),
        Map.entry("webhook", "webhooks"),
        Map.entry("discord", "webhooks"),
        Map.entry("cross-server", "cross-server"),
        Map.entry("remote-install", "remote-install"),
        // Gameplay systems: the loader refuses a script when the matching config.yml
        // feature switch is off (see io.astra.config.FeatureFlags).
        Map.entry("give-money", "economy"),
        Map.entry("take-money", "economy"),
        Map.entry("set-balance", "economy"));

    /** Trigger phrase prefixes that imply a feature flag. */
    private static final Map<String, String> TRIGGER_FEATURES = Map.of(
        "http", "http",
        "webhook", "webhooks",
        "discord", "webhooks");

    private final Registries registries;
    private final SecurityPolicy policy;

    public AstraCompiler() {
        this(Registries.builtins(), null);
    }

    public AstraCompiler(Registries registries) {
        this(registries, null);
    }

    public AstraCompiler(Registries registries, SecurityPolicy policy) {
        this.registries = registries == null ? Registries.builtins() : registries;
        this.policy = policy;
    }

    public Registries registries() {
        return registries;
    }

    /** Compile a parse result. The parser's own diagnostics are not copied here. */
    public CompilationResult compile(ParseResult parsed) {
        if (parsed == null) {
            return CompilationResult.failure(new DiagnosticCollector(), "");
        }
        return compile(parsed.file());
    }

    /** Compile a parsed file. */
    public CompilationResult compile(ScriptFile file) {
        Session session = new Session(file);
        session.run();
        List<Diagnostic> errors = session.filter(Severity.ERROR);
        List<Diagnostic> warnings = session.filter(Severity.WARNING);
        CompiledScript script = errors.isEmpty() ? session.build() : null;
        return new CompilationResult(script, errors, warnings, session.diagnostics, session.sourceHash);
    }

    /**
     * One compile run.
     *
     * <p>Held in a small object rather than passed around as a dozen parameters; it is
     * never shared between threads (one instance per file).</p>
     */
    private final class Session {

        private final ScriptFile file;
        private final DiagnosticCollector diagnostics = new DiagnosticCollector();
        private final String sourceHash;
        private final Map<String, Declaration.Function> functions = new LinkedHashMap<>();
        private final Set<String> dataKeys = new LinkedHashSet<>();
        private final List<CompiledRule> rules = new ArrayList<>();
        private final List<CompiledCommand> commands = new ArrayList<>();
        private final List<CompiledFunction> compiledFunctions = new ArrayList<>();
        private final List<Declaration.Data> data = new ArrayList<>();
        private final List<CompiledScript.RuleSummary> summaries = new ArrayList<>();
        private final Set<String> requiredFeatures = new LinkedHashSet<>();
        private final Set<String> warnedProperties = new LinkedHashSet<>();

        private Session(ScriptFile file) {
            this.file = file == null
                ? new ScriptFile("<empty>", "", List.of(), List.of(), false)
                : file;
            this.sourceHash = Hash.sha256(this.file.sourceText());
            this.diagnostics.setFile(this.file.name());
        }

        private void run() {
            List<Entry> entries = new ArrayList<>();
            flatten(file.declarations(), entries, false);

            // Pass 1: reusable actions and data declarations, so a rule may call a
            // function that is declared further down the file.
            for (Entry entry : entries) {
                if (entry.declaration() instanceof Declaration.Function function) {
                    if (functions.putIfAbsent(function.name().toLowerCase(Locale.ROOT), function) != null) {
                        error(function.span(), "Duplicate action '" + function.name() + "'",
                            "An action with this name is already declared in this script.",
                            List.of("Rename one of the two actions."));
                    }
                } else if (entry.declaration() instanceof Declaration.Data declared) {
                    if (!dataKeys.add(declared.key().toLowerCase(Locale.ROOT))) {
                        warn(declared.span(), "Duplicate data key '" + declared.key() + "'",
                            "The first declaration wins; this one is ignored.", List.of());
                    }
                }
            }

            // Pass 2: everything else.
            for (Entry entry : entries) {
                Declaration declaration = entry.declaration();
                if (declaration instanceof Declaration.Natural) {
                    continue; // its produced declarations are already part of this list
                }
                if (declaration instanceof Declaration.Event event) {
                    compileEvent(event, entry.natural());
                } else if (declaration instanceof Declaration.Timer timer) {
                    compileTimer(timer, entry.natural());
                } else if (declaration instanceof Declaration.Function function) {
                    compileFunction(function);
                } else if (declaration instanceof Declaration.Command command) {
                    compileCommand(command);
                } else if (declaration instanceof Declaration.Data declared) {
                    data.add(declared);
                    if (!declared.persistent()) {
                        warn(declared.span(), "Data key '" + declared.key() + "' is not persistent",
                            "Non-persistent data disappears when the script is reloaded.",
                            List.of("Add 'persistent' after the type, for example: data coins: number persistent = 0"));
                    }
                }
            }
        }

        // ------------------------------------------------------------------
        // declarations
        // ------------------------------------------------------------------

        private void compileEvent(Declaration.Event declaration, boolean natural) {
            EventDefinition definition = registries.events().get(declaration.triggerId());
            if (definition == null) {
                error(declaration.span(), "Unknown event trigger '" + declaration.triggerId() + "'",
                    "No registered trigger matches this phrase.",
                    registries.events().suggestions(declaration.triggerId()));
                return;
            }
            featureForTrigger(definition);

            List<CompiledCondition> filters = new ArrayList<>();
            for (Cond filter : declaration.filters()) {
                CompiledCondition compiled = condition(filter);
                if (compiled != null) filters.add(compiled);
            }
            List<CompiledStmt> body = block(declaration.body());
            String id = file.name() + ":" + declaration.span().line();
            rules.add(new CompiledRule(id, RuleKind.EVENT, definition.id(), definition.id(), filters, List.of(), body,
                definition.eventClass(), 0L, natural, describe(declaration.body()), declaration.span()));
            summaries.add(new CompiledScript.RuleSummary(RuleKind.EVENT, "when " + definition.id(),
                bodyDetails(declaration.body()), natural, declaration.span()));
        }

        private void compileTimer(Declaration.Timer declaration, boolean natural) {
            long period = declaration.periodTicks();
            if (period <= 0) {
                error(declaration.span(), "A timer needs a positive period",
                    "The duration '" + declaration.rawPeriod() + "' resolves to " + period + " ticks.",
                    List.of("Write a duration such as 'every 30 seconds' or 'every 10 minutes'."));
                return;
            }
            if (period < 20L) {
                warn(declaration.span(), "Very fast timer (" + period + " ticks)",
                    "Timers below one second are clamped to one tick and can lag the server.",
                    List.of("Use a longer duration."));
            }
            List<CompiledStmt> body = block(declaration.body());
            String id = file.name() + ":" + declaration.span().line();
            rules.add(new CompiledRule(id, RuleKind.TIMER, "timer", "every " + declaration.rawPeriod(),
                List.of(), List.of(), body, null, period, natural, describe(declaration.body()), declaration.span()));
            summaries.add(new CompiledScript.RuleSummary(RuleKind.TIMER, "every " + declaration.rawPeriod(),
                bodyDetails(declaration.body()), natural, declaration.span()));
        }

        private void compileFunction(Declaration.Function declaration) {
            List<CompiledStmt> body = block(declaration.body());
            compiledFunctions.add(new CompiledFunction(declaration.name(), declaration.parameters(), body,
                declaration.span()));
            summaries.add(new CompiledScript.RuleSummary(RuleKind.FUNCTION, "action " + declaration.name(),
                List.of(declaration.parameters().size() + " parameter(s)", body.size() + " statement(s)"),
                false, declaration.span()));
        }

        private void compileCommand(Declaration.Command declaration) {
            if (declaration.name() == null || declaration.name().isBlank()) {
                error(declaration.span(), "A command needs a name",
                    "The declaration has no usable command name.", List.of("Write 'command /heal:'."));
                return;
            }
            List<CompiledStmt> body = block(declaration.body());
            commands.add(new CompiledCommand(declaration.name().toLowerCase(Locale.ROOT), declaration.aliases(),
                declaration.permission(), null, declaration.playerOnly(), declaration.consoleAllowed(),
                declaration.description(), declaration.usage(), declaration.arguments(), declaration.cooldownTicks(),
                declaration.cooldownBypassPermission(), List.of(), body, declaration.span()));
            summaries.add(new CompiledScript.RuleSummary(RuleKind.COMMAND, "command /" + declaration.name(),
                bodyDetails(declaration.body()), false, declaration.span()));
        }

        // ------------------------------------------------------------------
        // statements
        // ------------------------------------------------------------------

        private List<CompiledStmt> block(Stmt.Block block) {
            List<CompiledStmt> out = new ArrayList<>();
            if (block == null) return out;
            for (Stmt statement : block.statements()) {
                CompiledStmt compiled = statement(statement);
                if (compiled != null) out.add(compiled);
            }
            return out;
        }

        private CompiledStmt statement(Stmt statement) {
            if (statement instanceof Stmt.Action action) {
                ActionDefinition definition = registries.actions().get(action.id());
                if (definition == null) {
                    error(action.span(), "Unknown action '" + action.id() + "'",
                        "'" + action.id() + "' is not a registered action.",
                        registries.actions().suggestions(action.id()));
                    return null;
                }
                checkArguments(definition.id(), definition.templates(), action.arguments(), action.span(), "action");
                featureForAction(definition.id());
                for (Expr argument : action.arguments().values()) expression(argument);
                return new CompiledStmt.Action(definition, action.arguments(), action.span());
            }
            if (statement instanceof Stmt.If branch) {
                List<CompiledCondition> conditions = conditions(branch.conditions());
                return new CompiledStmt.If(conditions, block(branch.then()), block(branch.otherwise()), branch.span());
            }
            if (statement instanceof Stmt.Repeat repeat) {
                expression(repeat.count());
                long limit = repeat.limit();
                if (policy != null && limit > policy.maxLoopIterations()) {
                    warn(repeat.span(), "Loop limit " + limit + " exceeds the configured maximum",
                        "security.yml caps loops at " + policy.maxLoopIterations() + " iterations.",
                        List.of("Lower the repeat count or raise max-loop-iterations in security.yml."));
                } else if (limit <= 0) {
                    warn(repeat.span(), "Loop count resolves to " + limit,
                        "The loop body will not run.", List.of("Use a positive repeat count."));
                }
                return new CompiledStmt.Repeat(repeat.count(), repeat.variable(), block(repeat.body()), repeat.span());
            }
            if (statement instanceof Stmt.Delay delay) {
                if (delay.ticks() <= 0) {
                    error(delay.span(), "A delay needs a positive duration",
                        "'" + delay.raw() + "' resolves to " + delay.ticks() + " ticks.",
                        List.of("Write 'wait 5 seconds' or 'wait 1 tick'."));
                    return null;
                }
                return new CompiledStmt.Delay(delay.ticks(), block(delay.body()), delay.span());
            }
            if (statement instanceof Stmt.Cancel cancel) {
                return new CompiledStmt.Cancel(cancel.scope() == null ? "event" : cancel.scope(), cancel.span());
            }
            if (statement instanceof Stmt.Call call) {
                Declaration.Function target = functions.get(call.function().toLowerCase(Locale.ROOT));
                if (target == null) {
                    error(call.span(), "Unknown action '" + call.function() + "'",
                        "No action with this name is declared in this script.",
                        io.astra.util.Strings.nearest(call.function(), functions.keySet(), 3));
                    return null;
                }
                for (Expr argument : call.arguments().values()) expression(argument);
                return new CompiledStmt.Call(reference(target), call.arguments(), call.span());
            }
            if (statement instanceof Stmt.Return returned) {
                if (returned.value() != null) expression(returned.value());
                return new CompiledStmt.Return(returned.value(), returned.span());
            }
            if (statement instanceof Stmt.Stop stop) {
                return new CompiledStmt.Stop(stop.reason() == null ? "stopped" : stop.reason(), stop.span());
            }
            return null;
        }

        /** The compiled function a call resolves to; built on demand from the pass-1 map. */
        private CompiledFunction reference(Declaration.Function function) {
            for (CompiledFunction compiled : compiledFunctions) {
                if (compiled.name().equalsIgnoreCase(function.name())) return compiled;
            }
            // Forward reference: produce an empty placeholder so the call compiles; the
            // function is filled in when its declaration is visited.
            return new CompiledFunction(function.name(), function.parameters(), List.of(), function.span());
        }

        // ------------------------------------------------------------------
        // conditions
        // ------------------------------------------------------------------

        private List<CompiledCondition> conditions(List<Cond> source) {
            List<CompiledCondition> out = new ArrayList<>();
            if (source == null) return out;
            for (Cond condition : source) {
                CompiledCondition compiled = condition(condition);
                if (compiled != null) out.add(compiled);
            }
            return out;
        }

        private CompiledCondition condition(Cond condition) {
            if (condition instanceof Cond.Test test) {
                ConditionDefinition definition = registries.conditions().get(test.id());
                if (definition == null) {
                    error(test.span(), "Unknown condition '" + test.id() + "'",
                        "'" + test.id() + "' is not a registered condition.",
                        registries.conditions().suggestions(test.id()));
                    return null;
                }
                checkArguments(definition.id(), definition.templates(), test.arguments(), test.span(), "condition");
                for (Expr argument : test.arguments().values()) expression(argument);
                return new CompiledCondition.Test(definition, test.arguments());
            }
            if (condition instanceof Cond.Compare compare) {
                expression(compare.left());
                expression(compare.right());
                return new CompiledCondition.Compare(compare.left(), compare.operator(), compare.right());
            }
            if (condition instanceof Cond.EventProperty property) {
                if (!EventPropertyResolver.owns(property.property()) && !Properties.isKnown(property.property())) {
                    error(property.span(), "Unknown event property '" + property.property() + "'",
                        "The event property resolver does not know this name.",
                        EventPropertyResolver.properties());
                    return null;
                }
                if (property.value() != null) expression(property.value());
                return CompiledCondition.EventProperty.equals(property.property(), property.value());
            }
            if (condition instanceof Cond.And and) {
                return new CompiledCondition.And(conditions(and.conditions()));
            }
            if (condition instanceof Cond.Or or) {
                return new CompiledCondition.Or(conditions(or.conditions()));
            }
            if (condition instanceof Cond.Not not) {
                CompiledCondition inner = condition(not.condition());
                return inner == null ? null : new CompiledCondition.Not(inner);
            }
            return null;
        }

        // ------------------------------------------------------------------
        // expressions and argument names
        // ------------------------------------------------------------------

        /** Statically check an expression tree: expression calls, properties and data keys. */
        private void expression(Expr expression) {
            if (expression == null) return;
            if (expression instanceof Expr.Call call) {
                if (!registries.expressions().has(call.name())) {
                    error(call.span(), "Unknown expression '" + call.name() + "'",
                        "'" + call.name() + "' is not a registered expression.",
                        registries.expressions().suggestions(call.name()));
                }
                for (Expr argument : call.arguments().values()) expression(argument);
                return;
            }
            if (expression instanceof Expr.Property property) {
                if (!Properties.isKnown(property.property())) {
                    warn(property.span(), "Unknown property '" + property.property() + "'",
                        "The runtime will resolve it as an event property, which may be empty.",
                        Properties.suggestions(property.property()));
                }
                expression(property.target());
                return;
            }
            if (expression instanceof Expr.Data dataExpr) {
                String key = dataExpr.key() == null ? "" : dataExpr.key().toLowerCase(Locale.ROOT);
                if (!dataKeys.contains(key) && warnedProperties.add("data:" + key)) {
                    warn(dataExpr.span(), "Data key '" + dataExpr.key() + "' is not declared in this script",
                        "It may be declared by another script or a module; otherwise it always reads as empty.",
                        List.of("Declare it with 'data " + dataExpr.key() + ": number = 0'."));
                }
                expression(dataExpr.target());
                return;
            }
            if (expression instanceof Expr.Binary binary) {
                expression(binary.left());
                expression(binary.right());
                return;
            }
            if (expression instanceof Expr.Unary unary) {
                expression(unary.operand());
                return;
            }
            if (expression instanceof Expr.Item item) {
                expression(item.material());
                expression(item.amount());
                return;
            }
            if (expression instanceof Expr.Position position) {
                expression(position.world());
                expression(position.x());
                expression(position.y());
                expression(position.z());
                return;
            }
            if (expression instanceof Expr.ListExpr list) {
                for (Expr item : list.items()) expression(item);
            }
        }

        /**
         * Report arguments no syntax form declares.
         *
         * <p>This catches the class of mistake where an author writes an argument an action
         * never reads (for example {@code tell player hello there: true}) while staying
         * silent for module-provided forms the compiler cannot see.</p>
         */
        private void checkArguments(String id, List<io.astra.language.parser.SyntaxTemplate> templates,
                                    Map<String, Expr> arguments, Span span, String kind) {
            if (arguments == null || arguments.isEmpty() || templates == null || templates.isEmpty()) return;
            Set<String> known = new LinkedHashSet<>();
            for (io.astra.language.parser.SyntaxTemplate template : templates) {
                for (io.astra.language.parser.SyntaxTemplate.Slot slot : template.slots()) {
                    known.add(slot.name().toLowerCase(Locale.ROOT));
                }
            }
            for (String name : arguments.keySet()) {
                if (!known.contains(name.toLowerCase(Locale.ROOT)) && warnedProperties.add(kind + ":" + id + ":" + name)) {
                    warn(span, "Argument '" + name + "' is not used by " + kind + " '" + id + "'",
                        "No syntax form of '" + id + "' declares a slot called '" + name + "'.",
                        List.of("Check the documented syntax for '" + id + "'."));
                }
            }
        }

        // ------------------------------------------------------------------
        // features, summaries and diagnostics
        // ------------------------------------------------------------------

        private void featureForAction(String id) {
            String feature = ACTION_FEATURES.get(id.toLowerCase(Locale.ROOT));
            if (feature != null) requiredFeatures.add(feature);
        }

        private void featureForTrigger(EventDefinition definition) {
            String id = definition.id().toLowerCase(Locale.ROOT);
            for (Map.Entry<String, String> entry : TRIGGER_FEATURES.entrySet()) {
                if (id.startsWith(entry.getKey())) requiredFeatures.add(entry.getValue());
            }
        }

        private String describe(Stmt.Block body) {
            if (body == null || body.isEmpty()) return "no statements";
            return body.statements().size() + " statement(s)";
        }

        private List<String> bodyDetails(Stmt.Block body) {
            List<String> details = new ArrayList<>();
            if (body == null) return details;
            for (Stmt statement : body.statements()) {
                details.add(describe(statement));
            }
            return details;
        }

        private String describe(Stmt statement) {
            if (statement instanceof Stmt.Action action) {
                StringBuilder text = new StringBuilder(action.id());
                if (!action.arguments().isEmpty()) {
                    text.append(' ');
                    text.append(String.join(", ", new LinkedHashMap<>(action.arguments()).keySet()));
                }
                return text.toString();
            }
            if (statement instanceof Stmt.If) return "if ...";
            if (statement instanceof Stmt.Repeat repeat) return "repeat " + repeat.limit() + " time(s)";
            if (statement instanceof Stmt.Delay) return "wait";
            if (statement instanceof Stmt.Cancel cancel) return "cancel " + cancel.scope();
            if (statement instanceof Stmt.Call call) return "call " + call.function();
            if (statement instanceof Stmt.Return) return "return";
            if (statement instanceof Stmt.Stop) return "stop";
            return statement.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        }

        private CompiledScript build() {
            return new CompiledScript(file.name(), sourceHash, rules, commands, compiledFunctions, data, file,
                summaries, List.copyOf(requiredFeatures), System.currentTimeMillis());
        }

        private List<Diagnostic> filter(Severity severity) {
            List<Diagnostic> out = new ArrayList<>();
            for (Diagnostic diagnostic : diagnostics.diagnostics()) {
                if (diagnostic.severity() == severity) out.add(diagnostic);
            }
            return out;
        }

        private void error(Span span, String message, String explanation, Collection<String> suggestions) {
            diagnostics.error(message, line(span), column(span), explanation, List.copyOf(suggestions));
        }

        private void warn(Span span, String message, String explanation, Collection<String> suggestions) {
            Diagnostic base = new Diagnostic(Severity.WARNING, "compiler", message, file.name(), line(span),
                column(span), 0, explanation, List.copyOf(suggestions), null);
            diagnostics.add(base);
        }

        private int line(Span span) {
            return span == null ? 0 : span.line();
        }

        private int column(Span span) {
            return span == null ? 0 : span.column();
        }
    }

    /** One declaration plus whether natural language produced it. */
    private record Entry(Declaration declaration, boolean natural) { }

    /** Flatten natural-language declarations back into the declarations they produced. */
    private static void flatten(List<Declaration> declarations, List<Entry> out, boolean natural) {
        if (declarations == null) return;
        for (Declaration declaration : declarations) {
            if (declaration instanceof Declaration.Natural wrapper) {
                List<Entry> produced = new ArrayList<>();
                flatten(wrapper.compiled(), produced, true);
                out.add(new Entry(declaration, true));
                out.addAll(produced);
            } else {
                out.add(new Entry(declaration, natural));
            }
        }
    }

    /** Convenience used by tests and tools: does this script compile against the built-ins? */
    public static boolean compiles(String source, String fileName) {
        DiagnosticCollector diagnostics = new DiagnosticCollector();
        ParseResult parsed = new io.astra.language.parser.AstraParser(diagnostics).parse(source, fileName);
        if (diagnostics.hasErrors()) return false;
        return new AstraCompiler().compile(parsed).success();
    }
}
