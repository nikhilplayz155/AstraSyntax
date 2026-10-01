package io.astra.language.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * A parsed {@code .ar} file.
 *
 * <p>This is the hand-off between the language layer and the compiler: the parser fills it
 * and the compiler turns it into the runtime IR. Keeping the source text alongside the
 * declarations is what allows diagnostics to show an excerpt of the offending line.</p>
 */
public record ScriptFile(String name, String sourceText, List<Declaration> declarations,
                         List<NaturalUnit> naturalUnits, boolean mixedMode) {

    /** One natural-language sentence and what it produced. */
    public record NaturalUnit(String sentence, List<Declaration> produced, String pattern, Span span) {
        public NaturalUnit {
            produced = produced == null ? List.of() : List.copyOf(produced);
        }
    }

    public ScriptFile {
        declarations = declarations == null ? List.of() : List.copyOf(declarations);
        naturalUnits = naturalUnits == null ? List.of() : List.copyOf(naturalUnits);
        sourceText = sourceText == null ? "" : sourceText;
    }

    /** All event triggers, including the ones compiled from natural language. */
    public List<Declaration.Event> events() {
        return ofType(Declaration.Event.class);
    }

    public List<Declaration.Command> commands() {
        return ofType(Declaration.Command.class);
    }

    public List<Declaration.Function> functions() {
        return ofType(Declaration.Function.class);
    }

    public List<Declaration.Timer> timers() {
        return ofType(Declaration.Timer.class);
    }

    public List<Declaration.Data> data() {
        return ofType(Declaration.Data.class);
    }

    /** True when at least one declaration came from natural language. */
    public boolean hasNaturalLanguage() {
        return !naturalUnits.isEmpty();
    }

    /** The number of rules the file declares, for the load summary. */
    public int ruleCount() {
        return events().size() + commands().size() + timers().size() + functions().size();
    }

    public boolean isEmpty() {
        return declarations.isEmpty();
    }

    /** The file with extra declarations appended (used when natural language expands). */
    public ScriptFile withDeclarations(List<Declaration> extra) {
        List<Declaration> combined = new ArrayList<>(declarations);
        combined.addAll(extra);
        return new ScriptFile(name, sourceText, combined, naturalUnits, mixedMode);
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> ofType(Class<T> type) {
        List<T> out = new ArrayList<>();
        for (Declaration declaration : declarations) {
            if (type.isInstance(declaration)) {
                out.add((T) declaration);
            } else if (declaration instanceof Declaration.Natural natural) {
                // Natural-language declarations count as their compiled equivalents so
                // the rest of the pipeline never needs to know which mode was used.
                for (Declaration produced : natural.compiled()) {
                    if (type.isInstance(produced)) out.add((T) produced);
                }
            }
        }
        return out;
    }
}
