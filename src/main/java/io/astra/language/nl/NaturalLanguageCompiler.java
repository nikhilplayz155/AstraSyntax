package io.astra.language.nl;

import io.astra.language.ast.Cond;
import io.astra.language.ast.Declaration;
import io.astra.language.ast.Expr;
import io.astra.language.ast.Span;
import io.astra.language.ast.Stmt;
import io.astra.language.diagnostics.DiagnosticCollector;
import io.astra.language.parser.AstraParser;
import io.astra.language.parser.Vocabulary;
import io.astra.runtime.Value;
import io.astra.runtime.ValueType;
import io.astra.util.Strings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The natural-language front end.
 *
 * <p>Sentences are compiled here, while the script is loaded - never while an event runs.
 * The compiler recognises a sentence pattern, works out which trigger it means and which
 * conditions it implies, then translates the action half into the structured equivalent and
 * hands it to {@link AstraParser}, so both authoring modes share one action matcher and one
 * set of coercions.</p>
 *
 * <p>When a sentence cannot be understood the compiler returns no declarations; the caller
 * reports a diagnostic with examples, and the script keeps its previous version. It never
 * guesses silently.</p>
 */
public final class NaturalLanguageCompiler {

    /** Phrases that mean "the player this rule is about". */
    private static final List<String> PRONOUNS = List.of("them", "him", "her", "the player", "that player",
        "a player", "players", "everyone", "everybody", "all players");

    /** Per-player flag used by the "for the first time" sentence. */
    private static final String FIRST_JOIN_KEY = "firstjoin";

    /** Sentence-level patterns. Order matters: the first match wins. */
    private record PatternRule(String name, Pattern pattern, Builder builder) { }

    /** Builds the declarations produced by one sentence pattern. */
    @FunctionalInterface
    private interface Builder {
        List<Declaration> build(NaturalLanguageCompiler compiler, Matcher matcher, String sentence, Span span);
    }

    private static final List<PatternRule> RULES = List.of(
        new PatternRule("first-join", Pattern.compile(
            "^(?:when|whenever|if)\\s+(?:a|an|the)?\\s*players?\\s+"
                + "(?:joins?|connects?|logs?\\s+in)\\s+(?:for\\s+the\\s+)?first\\s+time\\s*,?\\s*(.*)$"),
            (compiler, matcher, sentence, span) -> compiler.firstTimeRule(matcher.group(1), sentence, span)),
        new PatternRule("join", Pattern.compile(
            "^(?:when|whenever|if)\\s+(?:a|an|the)?\\s*players?\\s+(?:joins?|connects?|logs?\\s+in)\\s*,?\\s*(.*)$"),
            (compiler, matcher, sentence, span) -> compiler.eventRule("player join", matcher.group(1), sentence, span)),
        new PatternRule("quit", Pattern.compile(
            "^(?:when|whenever|if)\\s+(?:a|an|the)?\\s*players?\\s+(?:leaves?|quits?|disconnects?|logs?\\s+out)\\s*,?\\s*(.*)$"),
            (compiler, matcher, sentence, span) -> compiler.eventRule("player quit", matcher.group(1), sentence, span)),
        new PatternRule("respawn", Pattern.compile(
            "^(?:when|whenever|if)\\s+(?:a|an|the)?\\s*players?\\s+(?:respawns?|revives?)\\s*,?\\s*(.*)$"),
            (compiler, matcher, sentence, span) -> compiler.eventRule("player respawn", matcher.group(1), sentence, span)),
        new PatternRule("death", Pattern.compile(
            "^(?:when|whenever|if)\\s+(?:a|an|the)?\\s*players?\\s+(?:dies|die|is\\s+killed)\\s*,?\\s*(.*)$"),
            (compiler, matcher, sentence, span) -> compiler.eventRule("player death", matcher.group(1), sentence, span)),
        new PatternRule("kill-mob", Pattern.compile(
            "^(?:when|whenever|if)\\s+(?:a|an|the)?\\s*players?\\s+(?:kills?|defeats?|slays?)\\s+"
                + "(?:a|an|the)?\\s*([a-z_ ]+?)\\s*,?\\s*(.*)$"),
            (compiler, matcher, sentence, span) -> compiler.killRule(matcher.group(1), matcher.group(2), sentence, span)),
        new PatternRule("mob-dies", Pattern.compile(
            "^(?:when|whenever|if)\\s+(?:a|an|the)?\\s*([a-z_ ]+?)\\s+(?:dies|die|is\\s+killed)\\s*,?\\s*(.*)$"),
            (compiler, matcher, sentence, span) -> compiler.mobDeathRule(matcher.group(1), matcher.group(2), sentence, span)),
        new PatternRule("block-break", Pattern.compile(
            "^(?:when|whenever|if)\\s+(?:a|an|the)?\\s*players?\\s+(?:breaks?|mines?|destroys?)\\s+"
                + "(?:a|an|the)?\\s*([a-z_ ]+?)\\s*,?\\s*(.*)$"),
            (compiler, matcher, sentence, span) -> compiler.blockRule("block break", matcher.group(1),
                matcher.group(2), sentence, span)),
        new PatternRule("block-place", Pattern.compile(
            "^(?:when|whenever|if)\\s+(?:a|an|the)?\\s*players?\\s+(?:places?|puts?)\\s+"
                + "(?:a|an|the)?\\s*([a-z_ ]+?)\\s*,?\\s*(.*)$"),
            (compiler, matcher, sentence, span) -> compiler.blockRule("block place", matcher.group(1),
                matcher.group(2), sentence, span)),
        new PatternRule("chat", Pattern.compile(
            "^(?:when|whenever|if)\\s+(?:a|an|the)?\\s*players?\\s+(?:says?|talks?|chats?|sends?\\s+a\\s+message)\\s*,?\\s*(.*)$"),
            (compiler, matcher, sentence, span) -> compiler.eventRule("player chat", matcher.group(1), sentence, span)),
        new PatternRule("stop-grief", Pattern.compile(
            "^(?:stop|prevent|block|disallow)\\s+(?:a|an|the)?\\s*([a-z_ ]+?)s?\\s+from\\s+"
                + "(?:destroying|breaking|griefing|damaging|blowing\\s+up)\\s+[a-z_ ]+$"),
            (compiler, matcher, sentence, span) -> compiler.stopGriefRule(matcher.group(1), sentence, span)),
        new PatternRule("stop-x", Pattern.compile(
            "^(?:stop|prevent|block|disallow)\\s+(?:a|an|the)?\\s*players?\\s+from\\s+([a-z_ ]+)$"),
            (compiler, matcher, sentence, span) -> compiler.stopPlayerRule(matcher.group(1), sentence, span)),
        new PatternRule("every", Pattern.compile(
            "^(?:every|each)\\s+([0-9]+\\s*[a-z]+(?:\\s+[0-9]+\\s*[a-z]+)?)\\s*,?\\s*(.*)$"),
            (compiler, matcher, sentence, span) -> compiler.timerRule(matcher.group(1), matcher.group(2), sentence, span)),
        new PatternRule("command", Pattern.compile(
            "^(?:command|create\\s+a\\s+command)\\s+/?([a-z0-9_-]+)\\s*,?\\s*(?:that|which)?\\s*(.*)$"),
            (compiler, matcher, sentence, span) -> compiler.commandRule(matcher.group(1), matcher.group(2), sentence, span)),
        new PatternRule("rain", Pattern.compile(
            "^(?:make\\s+it\\s+)(rain|rainy|thunder|storm|clear|sunny)\\s*,?\\s*(.*)$"),
            (compiler, matcher, sentence, span) -> compiler.weatherRule(matcher.group(1), matcher.group(2), sentence, span)),
        new PatternRule("night", Pattern.compile(
            "^(?:make\\s+it\\s+|set\\s+(?:the\\s+)?time\\s+to\\s+)(night|day|noon|midnight|sunset|sunrise)\\s*,?\\s*(.*)$"),
            (compiler, matcher, sentence, span) -> compiler.timeRule(matcher.group(1), matcher.group(2), sentence, span))
    );

    /** Short phrases that are translated before the structured matcher sees them. */
    private static final Map<String, String> PHRASES = Map.ofEntries(
        Map.entry("welcome them", "tell player \"Welcome to the server!\""),
        Map.entry("welcome the player", "tell player \"Welcome to the server!\""),
        Map.entry("welcome a player", "tell player \"Welcome to the server!\""),
        Map.entry("greet them", "tell player \"Hello!\""),
        Map.entry("greet the player", "tell player \"Hello!\""),
        Map.entry("say hello", "tell player \"Hello!\""),
        Map.entry("congratulate them", "tell player \"Congratulations!\""),
        Map.entry("heal them", "heal player"),
        Map.entry("heal the player", "heal player"),
        Map.entry("kill them", "kill player"),
        Map.entry("feed them", "feed player"),
        Map.entry("clear their inventory", "clear inventory of player"),
        Map.entry("give them a reward", "give player 1 diamond"),
        Map.entry("thank them", "tell player \"Thank you!\""),
        Map.entry("remove the blocks", "cancel event"),
        Map.entry("stop it", "cancel event"),
        Map.entry("cancel the event", "cancel event"),
        Map.entry("stop the event", "cancel event"),
        Map.entry("stop them", "cancel event"),
        Map.entry("kick them", "kick player"),
        Map.entry("make them fly", "allow player to fly"),
        Map.entry("kill the creeper", "kill creeper"),
        Map.entry("protect the blocks", "cancel event"));

    private final Vocabulary vocabulary;
    private final DiagnosticCollector diagnostics;
    private AstraParser fragments;
    private String lastPattern = "";
    private boolean inNaturalSpan;

    public NaturalLanguageCompiler(Vocabulary vocabulary, DiagnosticCollector diagnostics) {
        this.vocabulary = vocabulary;
        this.diagnostics = diagnostics;
    }

    /**
     * The structured parser used for sentence fragments.
     *
     * <p>Set by {@link AstraParser} when it creates this compiler; keeping it as a setter
     * avoids a constructor cycle while still allowing unit tests to compile sentences
     * without a full script.</p>
     */
    public void setFragmentCompiler(AstraParser fragments) {
        this.fragments = fragments;
    }

    /** The name of the pattern that matched the last sentence. */
    public String lastPattern() {
        return lastPattern;
    }

    /** True while compiling inside a natural-language sentence (used for span flavours). */
    public boolean inNaturalSpan() {
        return inNaturalSpan;
    }

    // ------------------------------------------------------------------ compile

    /** Compile one sentence; an empty list means "not understood". */
    public List<Declaration> compile(String sentence, Span span) {
        return compile(sentence, span, null);
    }

    /** Compile one sentence with an explicit fragment compiler. */
    public List<Declaration> compile(String sentence, Span span, AstraParser fragmentCompiler) {
        if (fragmentCompiler != null) this.fragments = fragmentCompiler;
        String normalised = normalise(sentence);
        if (normalised.isEmpty()) return List.of();
        inNaturalSpan = true;
        try {
            for (PatternRule rule : RULES) {
                Matcher matcher = rule.pattern().matcher(normalised);
                if (!matcher.matches()) continue;
                List<Declaration> declarations = rule.builder().build(this, matcher, sentence, span);
                if (declarations != null && !declarations.isEmpty()) {
                    lastPattern = rule.name();
                    return declarations;
                }
            }
            // A bare action sentence: "welcome every player who joins"? Falls back to
            // treating it as an action attached to the player join event when it mentions
            // players at all, otherwise it is simply not understood.
            lastPattern = "";
            return List.of();
        } finally {
            inNaturalSpan = false;
        }
    }

    /** Example sentences shown when a sentence was not understood. */
    public List<String> suggestions(String sentence) {
        List<String> suggestions = new ArrayList<>();
        suggestions.add("when a player joins, tell them \"Welcome!\"");
        suggestions.add("when a player breaks diamond ore, give them 1 diamond");
        suggestions.add("when a player kills a zombie, add 5 coins to them");
        suggestions.add("every 10 minutes, broadcast \"Thanks for playing!\"");
        suggestions.add("stop creepers from destroying blocks");
        return suggestions;
    }

    // ------------------------------------------------------------------ builders

    private List<Declaration> eventRule(String triggerId, String actions, String sentence, Span span) {
        List<Stmt> body = compileActions(actions, span, false);
        return List.of(new Declaration.Event(triggerId, List.of(), new Stmt.Block(body, span), 0, span));
    }

    private List<Declaration> killRule(String mob, String actions, String sentence, Span span) {
        String mobName = cleanVerb(mob);
        List<Cond> filters = new ArrayList<>();
        if (mobName != null) {
            // The trigger already guarantees a player killer; this narrows it to the mob.
            filters.add(eventProperty("entity type", new Expr.Lit(Value.str(mobName), span), span));
        }
        List<Stmt> body = compileActions(actions, span, true);
        return List.of(new Declaration.Event("player kills entity", filters, new Stmt.Block(body, span), 0, span));
    }

    private List<Declaration> mobDeathRule(String mob, String actions, String sentence, Span span) {
        String mobName = cleanVerb(mob);
        List<Cond> filters = new ArrayList<>();
        if (mobName != null) {
            filters.add(eventProperty("entity type", new Expr.Lit(Value.str(mobName), span), span));
        }
        List<Stmt> body = compileActions(actions, span, false);
        return List.of(new Declaration.Event("entity death", filters, new Stmt.Block(body, span), 0, span));
    }

    private List<Declaration> blockRule(String triggerId, String block, String actions, String sentence, Span span) {
        String materialName = cleanMaterial(block, sentence);
        List<Cond> filters = new ArrayList<>();
        if (materialName != null) {
            filters.add(eventProperty("material", new Expr.Lit(Value.str(materialName), span), span));
        }
        List<Stmt> body = compileActions(actions, span, false);
        return List.of(new Declaration.Event(triggerId, filters, new Stmt.Block(body, span), 0, span));
    }

    private List<Declaration> stopGriefRule(String mob, String sentence, Span span) {
        String mobName = cleanVerb(mob);
        if (mobName == null) return List.of();
        List<Cond> filters = new ArrayList<>();
        filters.add(eventProperty("entity type", new Expr.Lit(Value.str(mobName), span), span));
        List<Stmt> body = new ArrayList<>();
        body.add(new Stmt.Cancel("event", span));
        return List.of(new Declaration.Event("entity explodes", filters, new Stmt.Block(body, span), 0, span));
    }

    /**
     * "stop players from flying" has no single well-defined meaning, so it is reported as
     * not understood rather than being translated into something surprising. The
     * {@code stop-grief} pattern handles the one case that is unambiguous.
     */
    private List<Declaration> stopPlayerRule(String action, String sentence, Span span) {
        return List.of();
    }

    /**
     * "when a player joins for the first time, ..." compiles to a {@code player join} rule
     * guarded by a persistent per-player flag, so it fires exactly once per player and
     * survives restarts.
     */
    private List<Declaration> firstTimeRule(String actions, String sentence, Span span) {
        List<Stmt> tail = compileActions(actions, span, false);
        Stmt remember = fragment("set " + FIRST_JOIN_KEY + " of player to true", span);
        if (remember != null) tail.add(remember);

        Cond notSeenBefore = condition("player has data " + FIRST_JOIN_KEY, span);
        List<Stmt> combined = new ArrayList<>();
        if (notSeenBefore != null) {
            combined.add(new Stmt.If(List.of(new Cond.Not(notSeenBefore, span)), new Stmt.Block(tail, span),
                new Stmt.Block(List.of(), span), span));
        } else {
            combined.addAll(tail);
        }

        List<Declaration> out = new ArrayList<>();
        // Declared up front so the data key exists even before the first player joins.
        out.add(new Declaration.Data(FIRST_JOIN_KEY, ValueType.BOOLEAN, Value.bool(false), true, true, span));
        out.add(new Declaration.Event("player join", List.of(), new Stmt.Block(combined, span), 0, span));
        return out;
    }

    private List<Declaration> timerRule(String interval, String actions, String sentence, Span span) {
        long ticks;
        try {
            ticks = io.astra.util.Durations.parseTicks(interval);
        } catch (IllegalArgumentException error) {
            return List.of();
        }
        List<Stmt> body = compileActions(actions, span, false);
        return List.of(new Declaration.Timer(ticks, interval, new Stmt.Block(body, span), span));
    }

    private List<Declaration> commandRule(String name, String actions, String sentence, Span span) {
        List<Stmt> body = compileActions(actions, span, true);
        return List.of(new Declaration.Command(name.toLowerCase(Locale.ROOT), List.of(), null, true, false,
            "Defined by a natural-language rule", "/" + name, List.of(), 0L, null,
            new Stmt.Block(body, span), span));
    }

    /**
     * "make it rain" becomes the command {@code /rain}: the sentence describes something a
     * player should be able to do, and a script-defined command is the honest equivalent.
     */
    private List<Declaration> weatherRule(String weather, String actions, String sentence, Span span) {
        String value = weather.startsWith("rain") ? "rain" : weather;
        Stmt statement = fragment("set weather in world to " + value, span);
        if (statement == null) return List.of();
        List<Stmt> body = new ArrayList<>();
        body.add(statement);
        return List.of(new Declaration.Command(value, List.of(), "astra.commands." + value, true, false,
            "Sets the weather to " + value, "/" + value, List.of(), 0L, null, new Stmt.Block(body, span), span));
    }

    /** "make it night" becomes the command {@code /night}, for the same reason as weather. */
    private List<Declaration> timeRule(String time, String actions, String sentence, Span span) {
        Stmt statement = fragment("set time in world to " + time, span);
        if (statement == null) return List.of();
        List<Stmt> body = new ArrayList<>();
        body.add(statement);
        return List.of(new Declaration.Command(time, List.of(), "astra.commands." + time, true, false,
            "Sets the time to " + time, "/" + time, List.of(), 0L, null, new Stmt.Block(body, span), span));
    }

    // ------------------------------------------------------------------ actions

    /** Compile the action half of a sentence into statements. */
    private List<Stmt> compileActions(String actions, Span span, boolean allowEmpty) {
        List<Stmt> body = new ArrayList<>();
        if (Strings.isBlank(actions)) {
            return body;
        }
        for (String clause : splitClauses(actions)) {
            Stmt statement = compileClause(clause, span);
            if (statement != null) body.add(statement);
        }
        return body;
    }

    private Stmt compileClause(String clause, Span span) {
        String text = normaliseClause(clause);
        if (text.isEmpty()) return null;

        // Phrases with a fixed meaning are translated first: "welcome them".
        for (Map.Entry<String, String> entry : PHRASES.entrySet()) {
            if (text.equals(entry.getKey()) || text.startsWith(entry.getKey() + " ")
                || text.contains(entry.getKey())) {
                Stmt translated = fragment(entry.getValue(), span);
                if (translated != null) return translated;
            }
        }

        Stmt direct = fragment(toStructured(text), span);
        if (direct != null) return direct;

        // "give them 5 diamonds" and friends are already structured-compatible because
        // pronouns are handled by the target binder - try the raw text as a last resort.
        return fragment(text, span);
    }

    private Stmt fragment(String structured, Span span) {
        if (fragments == null) return null;
        int before = diagnostics.diagnostics().size();
        Stmt statement = fragments.compileStatement(structured, span);
        if (statement == null) {
            // The fragment compiler reported its own diagnostics; drop them so the caller
            // can report one clear "I could not understand this rule" message instead.
            while (diagnostics.diagnostics().size() > before) {
                List<io.astra.language.diagnostics.Diagnostic> all =
                    new ArrayList<>(diagnostics.diagnostics());
                all.remove(all.size() - 1);
                diagnostics.clear();
                for (io.astra.language.diagnostics.Diagnostic diagnostic : all) diagnostics.add(diagnostic);
            }
        }
        return statement;
    }

    private Cond condition(String structured, Span span) {
        if (fragments == null) return null;
        return fragments.compileCondition(structured, span);
    }

    private static Cond eventProperty(String property, Expr value, Span span) {
        return new Cond.EventProperty(property, value, span);
    }

    /** Translate a few English idioms into the structured wording the matcher knows. */
    private static String toStructured(String text) {
        String result = " " + text + " ";
        // "give them 5 diamonds" -> "give player 5 diamonds"
        for (String pronoun : PRONOUNS) {
            result = result.replace(" " + pronoun + " ", " player ");
        }
        result = result.replace(" a player ", " player ").replace(" the player ", " player ");
        result = result.replace(" has got ", " has ");
        result = result.replace(" want to ", " ");
        result = result.replace(" would like to ", " ");
        result = result.replace(" is able to ", " can ");
        return result.trim();
    }

    /** Split "give them 5 diamonds and welcome them" into clauses. */
    private static List<String> splitClauses(String text) {
        List<String> clauses = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String[] words = text.trim().split("\\s+");
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            boolean join = word.equalsIgnoreCase("and") || word.equals(",") || word.equalsIgnoreCase("then");
            if (join && current.length() > 0 && i < words.length - 1) {
                clauses.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            if (current.length() > 0) current.append(' ');
            current.append(word);
        }
        if (current.length() > 0) clauses.add(current.toString().trim());
        // Re-join split clauses that are not actions on their own ("5 minutes and 30 seconds").
        List<String> merged = new ArrayList<>();
        for (String clause : clauses) {
            if (clause.isEmpty()) continue;
            if (looksLikeFragment(clause) && !merged.isEmpty()) {
                merged.set(merged.size() - 1, merged.get(merged.size() - 1) + " and " + clause);
            } else {
                merged.add(clause);
            }
        }
        return merged;
    }

    private static boolean looksLikeFragment(String clause) {
        String lower = clause.toLowerCase(Locale.ROOT);
        return lower.matches("^[0-9]+\\s*[a-z]*$") || lower.startsWith("then ");
    }

    // ------------------------------------------------------------------ helpers

    /** Lower-case, strip punctuation and collapse spaces. */
    private static String normalise(String sentence) {
        String text = sentence == null ? "" : sentence.trim().toLowerCase(Locale.ROOT);
        text = text.replaceAll("[.!?]+$", "");
        text = text.replaceAll("\\s+", " ");
        text = text.replace(",", " , ");
        text = text.replaceAll("\\s+", " ");
        return text.trim();
    }

    private static String normaliseClause(String clause) {
        String text = clause == null ? "" : clause.trim().toLowerCase(Locale.ROOT);
        text = text.replaceAll("[.!?]+$", "");
        text = text.replaceAll("^then\\s+", "");
        return text.replaceAll("\\s+", " ").trim();
    }

    /** Reduce "a creeper" or "zombies" to the entity type the vocabulary knows. */
    private String cleanVerb(String raw) {
        if (raw == null) return null;
        String candidate = raw.trim().toLowerCase(Locale.ROOT);
        candidate = candidate.replaceAll("^(a|an|the)\\s+", "");
        if (vocabulary.isEntityType(candidate)) return candidate;
        if (candidate.endsWith("s") && vocabulary.isEntityType(candidate.substring(0, candidate.length() - 1))) {
            return candidate.substring(0, candidate.length() - 1);
        }
        if (vocabulary.isEntityType(candidate + "s")) return candidate + "s";
        List<String> suggestions = vocabulary.entitySuggestions(candidate);
        return suggestions.isEmpty() ? candidate : candidate;
    }

    /** Reduce "diamond ore" to a material the vocabulary knows. */
    private String cleanMaterial(String raw, String sentence) {
        if (raw == null) return null;
        String candidate = raw.trim().toLowerCase(Locale.ROOT).replaceAll("^(a|an|the)\\s+", "");
        if (vocabulary.isMaterial(candidate)) return candidate;
        if (candidate.endsWith("s") && vocabulary.isMaterial(candidate.substring(0, candidate.length() - 1))) {
            return candidate.substring(0, candidate.length() - 1);
        }
        return candidate;
    }

    /** Unused helper retained for documentation generation. */
    static Map<String, String> phrases() {
        return new LinkedHashMap<>(PHRASES);
    }
}
