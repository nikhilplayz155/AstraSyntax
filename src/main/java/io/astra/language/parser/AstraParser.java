package io.astra.language.parser;

import io.astra.language.ast.Cond;
import io.astra.language.ast.Declaration;
import io.astra.language.ast.Expr;
import io.astra.language.ast.ScriptFile;
import io.astra.language.ast.Span;
import io.astra.language.ast.Stmt;
import io.astra.language.diagnostics.Diagnostic;
import io.astra.language.diagnostics.DiagnosticCollector;
import io.astra.language.diagnostics.Severity;
import io.astra.language.lexer.AstraLexer;
import io.astra.language.lexer.Token;
import io.astra.language.lexer.TokenType;
import io.astra.language.nl.NaturalLanguageCompiler;
import io.astra.runtime.Value;
import io.astra.runtime.ValueType;
import io.astra.runtime.expression.Properties;
import io.astra.util.Durations;
import io.astra.util.Strings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Astra parser and the natural-language compiler front end.
 *
 * <p>One parser handles both authoring modes because both produce the same AST: the
 * structured form is matched against the registered vocabulary, and a line that is not
 * structured is handed to the {@link NaturalLanguageCompiler}, which compiles the sentence
 * into ordinary declarations. Nothing about natural language survives into the runtime -
 * by the time a script is loaded, a sentence has become the same rules a structured script
 * would produce.</p>
 *
 * <p>The parser is data-driven: actions, conditions and triggers come from the
 * {@link Vocabulary}, so a module that registers an action makes it parseable immediately.
 * Unknown words are reported with suggestions (the project's
 * {@code dimonds -> diamonds} requirement lives here).</p>
 */
public final class AstraParser {

    private final DiagnosticCollector diagnostics;
    private final Vocabulary vocabulary;
    private final NaturalLanguageCompiler naturalLanguage;

    /** The parser that compiles natural-language action fragments (null for that parser itself). */
    private final AstraParser fragmentParser;

    private String source = "";
    private String file = "<script>";
    private List<String> rawLines = List.of();
    private List<Line> lines = List.of();
    private int cursor;

    /** Everything the current match attempt wants to complain about, reported at most once. */
    private Problem pendingProblem;
    private final Map<String, Expr> lastCaptures = new LinkedHashMap<>();

    private final Set<String> dataKeys = new LinkedHashSet<>();
    private final Set<String> functions = new LinkedHashSet<>();
    private final Set<String> variables = new LinkedHashSet<>();

    public AstraParser(DiagnosticCollector diagnostics) {
        this(diagnostics, VocabularyProvider.defaultVocabulary(), false);
    }

    public AstraParser(DiagnosticCollector diagnostics, Vocabulary vocabulary) {
        this(diagnostics, vocabulary, false);
    }

    /**
     * @param fragmentParser true when this instance exists only to compile the action
     *                       fragments of a natural-language sentence. Such an instance
     *                       must not create a second fragment parser, and it must own its
     *                       line buffer: the sentence compiler runs while the outer script
     *                       is mid-parse, so sharing {@code lines}/{@code cursor} would
     *                       corrupt the file being parsed.
     */
    private AstraParser(DiagnosticCollector diagnostics, Vocabulary vocabulary, boolean fragmentParser) {
        this.diagnostics = diagnostics == null ? new DiagnosticCollector() : diagnostics;
        this.vocabulary = vocabulary == null ? VocabularyProvider.defaultVocabulary() : vocabulary;
        this.naturalLanguage = new NaturalLanguageCompiler(this.vocabulary, this.diagnostics);
        if (fragmentParser) {
            this.fragmentParser = null;
        } else {
            this.fragmentParser = new AstraParser(this.diagnostics, this.vocabulary, true);
            this.naturalLanguage.setFragmentCompiler(this.fragmentParser);
        }
    }

    /** The vocabulary in use (module contributions included). */
    public Vocabulary vocabulary() {
        return vocabulary;
    }

    /**
     * Compile one statement written in the structured syntax.
     *
     * <p>The natural-language compiler uses this after it has translated a sentence
     * fragment into its structured equivalent, so both authoring modes end up going
     * through exactly one action matcher.</p>
     */
    public Stmt compileStatement(String text, Span span) {
        List<Token> tokens = new AstraLexer(diagnostics).lex(text == null ? "" : text, file);
        this.lines = toLines(tokens);
        this.cursor = 0;
        if (lines.isEmpty()) return null;
        Line line = lines.get(0);
        String head = line.head();
        Stmt statement = switch (head) {
            case "if" -> parseIf(line);
            case "repeat", "loop" -> parseRepeat(line);
            case "wait", "after", "delay", "pause" -> parseDelay(line);
            case "cancel" -> parseCancel(line);
            case "stop", "halt" -> new Stmt.Stop("", span);
            case "return" -> parseReturn(line);
            case "call", "invoke" -> parseCall(line);
            default -> parseActionStatement(line);
        };
        return statement;
    }

    /** Compile one condition written in the structured syntax. */
    public Cond compileCondition(String text, Span span) {
        List<Token> tokens = new AstraLexer(diagnostics).lex(text == null ? "" : text, file);
        List<Token> flat = new ArrayList<>();
        for (Token token : tokens) {
            if (token.type == TokenType.NEWLINE || token.type == TokenType.EOF
                || token.type == TokenType.INDENT || token.type == TokenType.DEDENT) {
                continue;
            }
            flat.add(token);
        }
        return parseConditions(flat, span);
    }

    // ------------------------------------------------------------------- entry

    /** Parse a script. */
    public ParseResult parse(String sourceText, String fileName) {
        this.source = sourceText == null ? "" : sourceText;
        this.file = Strings.isBlank(fileName) ? "<script>" : fileName;
        this.rawLines = Arrays.asList(this.source.split("\n", -1));
        this.cursor = 0;
        this.dataKeys.clear();
        this.functions.clear();
        this.variables.clear();
        this.diagnostics.setFile(this.file);
        // Sentence fragments are reported against the script they appear in.
        if (fragmentParser != null) fragmentParser.file = this.file;

        List<Token> tokens = new AstraLexer(diagnostics).lex(this.source, this.file);
        this.lines = toLines(tokens);

        List<Declaration> declarations = new ArrayList<>();
        List<ScriptFile.NaturalUnit> units = new ArrayList<>();
        boolean mixed = false;

        while (cursor < lines.size()) {
            Line line = lines.get(cursor);
            if (line.depth() != 0) {
                problem(Severity.ERROR, "syntax", "Unexpected indentation",
                    line.first(), "This line is indented, but no block is open here.",
                    List.of());
                cursor++;
                continue;
            }
            int before = cursor;
            Declaration declaration = parseDeclaration(line, units);
            if (declaration != null) {
                declarations.add(declaration);
                if (declaration instanceof Declaration.Natural) mixed = true;
            }
            if (cursor <= before) cursor = before + 1;
        }

        ScriptFile scriptFile = new ScriptFile(this.file, this.source, declarations, units, mixed);
        return new ParseResult(scriptFile, !diagnostics.hasErrors());
    }

    // ------------------------------------------------------------ declarations

    private Declaration parseDeclaration(Line line, List<ScriptFile.NaturalUnit> units) {
        String head = line.head();
        boolean colon = endsWithColon(line);
        switch (head) {
            case "on", "when", "whenever", "if":
                // A trailing colon means structured syntax: a header that does not match a
                // trigger is an error, never a natural-language sentence.
                if (colon) return parseEvent(line);
                return parseNatural(line, units);
            case "command":
                if (colon) return parseCommand(line);
                return parseNatural(line, units);
            case "every":
                if (colon) return parseTimer(line);
                return parseNatural(line, units);
            case "action", "function":
                if (colon) return parseFunction(line);
                return parseNatural(line, units);
            case "data", "declare":
                return parseData(line);
            case "item", "custom-item":
                if (colon) return parseCustomItem(line);
                return parseNatural(line, units);
            case "menu", "gui":
                if (colon) return parseMenu(line);
                return parseNatural(line, units);
            case "recipe":
                if (colon) return parseRecipe(line);
                return parseNatural(line, units);
            case "region":
                if (colon) return parseRegion(line);
                return parseNatural(line, units);
            case "import", "include", "require-file":
                problem(Severity.WARNING, "feature", "Imports are not supported yet",
                    line.first(), "AstraSyntax loads every .ar file in the scripts folder; "
                        + "packages will add file-to-file imports in a later release.", List.of());
                cursor++;
                skipBlock(line.depth());
                return null;
            default:
                return parseNatural(line, units);
        }
    }

    /** A trigger declaration: {@code on player join: ...}. */
    private Declaration parseEvent(Line header) {
        cursor++; // consume the header before the body
        List<Token> phrase = new ArrayList<>(header.tokens().subList(1, header.tokens().size() - 1));
        if (phrase.isEmpty()) {
            problem(Severity.ERROR, "trigger", "This trigger has no event",
                header.first(), "Write the event after the keyword, for example 'on player join:'.", List.of());
            skipBlock(header.depth());
            return null;
        }
        TriggerMatch match = matchTrigger(phrase);
        Stmt.Block body = parseBody(header.depth());
        if (match == null) {
            List<String> suggestions = vocabulary.eventSuggestions(joinText(phrase));
            problem(Severity.ERROR, "trigger", "Unknown event: " + joinText(phrase), phrase.get(0),
                "AstraSyntax does not have a trigger for this phrase.",
                suggestions.isEmpty() ? vocabulary.eventSuggestions("player join") : suggestions);
            return null;
        }
        return new Declaration.Event(match.triggerId(), match.filters(), body, 0, span(header));
    }

    /** A command declaration: {@code command /heal: ...}. */
    private Declaration parseCommand(Line header) {
        cursor++; // consume the header before the body
        List<Token> tokens = header.tokens();
        int index = 1;
        String name = null;
        if (index < tokens.size() && tokens.get(index).type == TokenType.SLASH) index++;
        if (index < tokens.size() && (tokens.get(index).type == TokenType.WORD
            || tokens.get(index).type == TokenType.STRING)) {
            name = tokens.get(index).text;
            index++;
        }
        List<Declaration.CommandArgument> arguments = parseCommandArguments(tokens, index);
        if (name == null || name.isBlank()) {
            problem(Severity.ERROR, "command", "This command has no name",
                header.first(), "Write the command as 'command /name:'.", List.of());
            skipBlock(header.depth());
            return null;
        }

        List<String> aliases = new ArrayList<>();
        String permission = null;
        boolean playerOnly = false;
        boolean consoleAllowed = true;
        String description = "";
        String usage = "";
        long cooldownTicks = 0L;
        String cooldownBypass = null;

        List<Stmt> body = new ArrayList<>();
        Token headerToken = header.first();
        while (cursor < lines.size() && lines.get(cursor).depth() > header.depth()) {
            Line bodyLine = lines.get(cursor);
            String attribute = bodyLine.head();
            switch (attribute) {
                case "require", "permission" -> {
                    int offset = attribute.equals("require") ? 1 : 0;
                    List<Token> rest = bodyLine.tokens().subList(offset, bodyLine.tokens().size());
                    if (rest.isEmpty()) {
                        cursor++;
                        continue;
                    }
                    String what = rest.get(0).text.toLowerCase(Locale.ROOT);
                    if (what.equals("permission")) {
                        permission = joinText(rest.subList(1, rest.size()));
                    } else if (what.equals("player") || what.equals("players")) {
                        playerOnly = true;
                        consoleAllowed = false;
                    } else if (what.equals("console")) {
                        consoleAllowed = true;
                    } else {
                        permission = joinText(rest);
                    }
                    cursor++;
                    continue;
                }
                case "aliases", "alias" -> {
                    aliases.addAll(splitList(joinText(bodyLine.tokens().subList(1, bodyLine.tokens().size()))));
                    cursor++;
                    continue;
                }
                case "description", "desc" -> {
                    description = joinText(bodyLine.tokens().subList(1, bodyLine.tokens().size()));
                    cursor++;
                    continue;
                }
                case "usage" -> {
                    usage = joinText(bodyLine.tokens().subList(1, bodyLine.tokens().size()));
                    cursor++;
                    continue;
                }
                case "cooldown" -> {
                    String raw = joinText(bodyLine.tokens().subList(1, bodyLine.tokens().size()));
                    cooldownTicks = parseDuration(raw, bodyLine.first(), "cooldown");
                    cursor++;
                    continue;
                }
                case "cooldown-bypass" -> {
                    cooldownBypass = joinText(bodyLine.tokens().subList(1, bodyLine.tokens().size()));
                    cursor++;
                    continue;
                }
                case "player-only", "playeronly" -> {
                    playerOnly = true;
                    consoleAllowed = false;
                    cursor++;
                    continue;
                }
                case "allow-console", "console-allowed" -> {
                    consoleAllowed = true;
                    cursor++;
                    continue;
                }
                default -> {
                    Stmt statement = parseStatement(bodyLine);
                    if (statement != null) body.add(statement);
                }
            }
        }
        if (usage.isEmpty()) {
            StringBuilder usageBuilder = new StringBuilder("/").append(name);
            for (Declaration.CommandArgument argument : arguments) {
                usageBuilder.append(argument.optional() ? " [" : " <").append(argument.name()).append('>');
                if (argument.optional()) usageBuilder.append(']');
            }
            usage = usageBuilder.toString();
        }
        Stmt.Block block = new Stmt.Block(body, span(headerToken));
        return new Declaration.Command(name, aliases, permission, playerOnly, consoleAllowed, description, usage,
            arguments, cooldownTicks, cooldownBypass, block, span(header));
    }

    private List<Declaration.CommandArgument> parseCommandArguments(List<Token> tokens, int from) {
        List<Declaration.CommandArgument> arguments = new ArrayList<>();
        int index = from;
        while (index < tokens.size()) {
            if (tokens.get(index).type != TokenType.LT) {
                index++;
                continue;
            }
            int end = index + 1;
            StringBuilder name = new StringBuilder();
            String type = "text";
            boolean optional = false;
            boolean greedy = false;
            boolean inType = false;
            while (end < tokens.size() && tokens.get(end).type != TokenType.GT) {
                Token token = tokens.get(end);
                if (token.type == TokenType.COLON) {
                    inType = true;
                } else if (token.type == TokenType.QUESTION) {
                    optional = true;
                } else if (token.type == TokenType.WORD) {
                    if (inType && (token.text.equals("text") || token.text.equals("rest"))) {
                        greedy = true;
                    }
                    if (inType) type = token.text;
                    else name.append(token.text);
                } else if (token.type == TokenType.NUMBER || token.type == TokenType.STRING) {
                    if (inType) type = token.text;
                    else name.append(token.text);
                }
                end++;
            }
            if (name.length() > 0) {
                arguments.add(new Declaration.CommandArgument(name.toString(), type, optional, greedy));
            }
            index = end + 1;
        }
        return arguments;
    }

    /** A timer declaration: {@code every 10 minutes: ...}. */
    private Declaration parseTimer(Line header) {
        cursor++; // consume the header before the body
        List<Token> tokens = header.tokens();
        List<Token> periodTokens = tokens.subList(1, tokens.size() - 1);
        String raw = joinText(periodTokens);
        long ticks = parseDuration(raw, header.first(), "timer");
        Stmt.Block body = parseBody(header.depth());
        return new Declaration.Timer(ticks, raw, body, span(header));
    }

    /** A reusable action: {@code action welcome(player): ...}. */
    private Declaration parseFunction(Line header) {
        cursor++; // consume the header before the body
        List<Token> tokens = header.tokens();
        int index = 1;
        if (index >= tokens.size() || tokens.get(index).type != TokenType.WORD) {
            problem(Severity.ERROR, "action", "This reusable action has no name",
                header.first(), "Write it as 'action name(parameters):'.", List.of());
            skipBlock(header.depth());
            return null;
        }
        String name = tokens.get(index).text;
        index++;
        List<Declaration.Parameter> parameters = new ArrayList<>();
        if (index < tokens.size() && tokens.get(index).type == TokenType.LPAREN) {
            index++;
            StringBuilder parameterName = new StringBuilder();
            String type = null;
            boolean optional = false;
            Object defaultValue = null;
            boolean expectDefault = false;
            while (index < tokens.size() && tokens.get(index).type != TokenType.RPAREN) {
                Token token = tokens.get(index);
                switch (token.type) {
                    case COLON -> type = "";
                    case QUESTION -> optional = true;
                    case EQ -> expectDefault = true;
                    case COMMA -> {
                        if (parameterName.length() > 0) {
                            parameters.add(parameter(parameterName.toString(), type, optional, defaultValue));
                        }
                        parameterName.setLength(0);
                        type = null;
                        optional = false;
                        defaultValue = null;
                        expectDefault = false;
                    }
                    case NUMBER -> {
                        if (expectDefault) {
                            defaultValue = Value.num(parseLong(token.text));
                        } else if (type != null) {
                            type = type.isEmpty() ? "number" : type;
                        } else {
                            parameterName.append(token.text);
                        }
                    }
                    case DECIMAL -> {
                        if (expectDefault) {
                            defaultValue = Value.dec(parseDouble(token.text));
                        } else {
                            type = type == null || type.isEmpty() ? "decimal" : type;
                        }
                    }
                    case STRING -> {
                        if (expectDefault) {
                            defaultValue = Value.str(token.text);
                        } else if (type != null) {
                            type = type.isEmpty() ? "text" : type;
                        } else {
                            parameterName.append(token.text);
                        }
                    }
                    default -> {
                        if (type != null) {
                            type = type.isEmpty() ? token.text : type;
                        } else {
                            parameterName.append(token.text);
                        }
                    }
                }
                index++;
            }
            if (parameterName.length() > 0) {
                parameters.add(parameter(parameterName.toString(), type, optional, defaultValue));
            }
        }
        functions.add(name.toLowerCase(Locale.ROOT));
        Stmt.Block body = parseBody(header.depth());
        return new Declaration.Function(name, parameters, body, span(header));
    }

    private Declaration.Parameter parameter(String name, String type, boolean optional, Object defaultValue) {
        ValueType valueType = ValueType.parse(type == null ? "text" : type, ValueType.STRING);
        Value value = defaultValue == null ? null : Value.of(defaultValue);
        return new Declaration.Parameter(name, valueType, optional, value);
    }

    /** A data declaration: {@code data coins: number per player = 0}. */
    private Declaration parseData(Line header) {
        List<Token> tokens = header.tokens();
        cursor++;
        String key = null;
        ValueType type = ValueType.DECIMAL;
        boolean persistent = true;
        boolean perPlayer = true;
        Object defaultValue = null;

        int index = 1;
        if (index < tokens.size() && (tokens.get(index).type == TokenType.WORD
            || tokens.get(index).type == TokenType.STRING)) {
            key = tokens.get(index).text;
            index++;
        }
        boolean afterColon = false;
        while (index < tokens.size()) {
            Token token = tokens.get(index);
            if (token.type == TokenType.COLON) {
                afterColon = true;
                index++;
                continue;
            }
            if (token.type == TokenType.EQ) {
                index++;
                if (index < tokens.size()) {
                    defaultValue = literal(tokens.get(index));
                    index++;
                }
                continue;
            }
            if (token.type == TokenType.WORD) {
                String word = token.text.toLowerCase(Locale.ROOT);
                switch (word) {
                    case "per" -> {
                        if (index + 1 < tokens.size() && tokens.get(index + 1).text.equalsIgnoreCase("player")) {
                            perPlayer = true;
                            index += 2;
                            continue;
                        }
                    }
                    case "global", "shared", "server" -> {
                        perPlayer = false;
                        index++;
                        continue;
                    }
                    case "persistent", "saved", "stored" -> {
                        persistent = true;
                        index++;
                        continue;
                    }
                    case "session", "volatile", "temporary" -> {
                        persistent = false;
                        index++;
                        continue;
                    }
                    case "if", "when", "at" -> {
                        // "= 0 if absent" style qualifier: consume the rest.
                        index = tokens.size();
                        continue;
                    }
                    default -> { }
                }
                ValueType parsed = ValueType.parse(word, null);
                if (parsed != null && afterColon) type = parsed;
                if (parsed != null) type = parsed;
            }
            index++;
        }
        if (key == null || key.isBlank()) {
            problem(Severity.ERROR, "data", "This data declaration has no key",
                header.first(), "Write it as 'data <name>: <type>'.", List.of());
            return null;
        }
        dataKeys.add(key.toLowerCase(Locale.ROOT));
        if (defaultValue == null) {
            defaultValue = switch (type) {
                case INT -> 0L;
                case DECIMAL -> 0d;
                case BOOLEAN -> false;
                case LIST -> List.of();
                default -> "";
            };
        }
        return new Declaration.Data(key, type, Value.of(defaultValue), persistent, perPlayer, span(header));
    }

    // --------------------------------------------------------- gameplay blocks

    /**
     * A custom item: {@code item legendary_sword:} followed by its properties.
     *
     * <p>Properties are keyword lines rather than free text, so a typo is an error with the
     * list of accepted keys instead of a silently ignored line.</p>
     */
    private Declaration parseCustomItem(Line header) {
        cursor++;
        String name = tokenText(header, 1);
        if (Strings.isBlank(name)) {
            problem(Severity.ERROR, "item", "This item has no name",
                header.first(), "Write the declaration as 'item <name>:'.",
                List.of("item legendary_sword:"));
            skipBlock(header.depth());
            return null;
        }
        String material = null;
        Token materialToken = header.first();
        String displayName = null;
        int amount = 1;
        boolean unbreakable = false;
        List<String> lore = new ArrayList<>();
        List<String> flags = new ArrayList<>();
        List<Declaration.ItemEnchant> enchants = new ArrayList<>();

        while (cursor < lines.size() && lines.get(cursor).depth() > header.depth()) {
            Line line = lines.get(cursor);
            int before = cursor;
            switch (line.head()) {
                case "material", "type", "of" -> {
                    material = tokenText(line, 1);
                    materialToken = line.tokens().size() > 1 ? line.tokens().get(1) : line.first();
                    cursor++;
                }
                case "name", "display", "display-name", "displayname" -> {
                    displayName = lineText(line, 1);
                    cursor++;
                }
                case "lore", "description" -> {
                    lore.add(lineText(line, 1));
                    cursor++;
                }
                case "amount", "count" -> {
                    amount = lineInt(line, 1, 1);
                    cursor++;
                }
                case "enchant", "enchantment", "enchanted" -> {
                    enchants.add(new Declaration.ItemEnchant(tokenText(line, 1), lineInt(line, 2, 1)));
                    cursor++;
                }
                case "unbreakable" -> {
                    unbreakable = lineBool(line, 1, true);
                    cursor++;
                }
                case "flag", "flags", "hide" -> {
                    flags.addAll(lineWords(line, 1));
                    cursor++;
                }
                default -> {
                    unknownProperty(line, "item",
                        List.of("material", "name", "lore", "amount", "enchant", "unbreakable", "flags"));
                    cursor++;
                }
            }
            // A case that consumed nothing must still make progress.
            if (cursor <= before) cursor = before + 1;
        }
        if (Strings.isBlank(material)) {
            problem(Severity.ERROR, "item", "Item '" + name + "' has no material",
                header.first(), "Every custom item needs the material it is made of.",
                List.of("material diamond_sword"));
            return null;
        }
        if (!vocabulary.isMaterial(material)) {
            problem(Severity.ERROR, "material", "Unknown item: " + material, materialToken,
                "The material is not a Minecraft material name or a known alias.",
                vocabulary.materialSuggestions(material));
            return null;
        }
        return new Declaration.CustomItem(name, material.toLowerCase(Locale.ROOT), Math.max(1, amount), displayName,
            lore, enchants, unbreakable, flags, span(header));
    }

    /** A chest menu: {@code menu shop: title ... size ... slot <index>: ... }. */
    private Declaration parseMenu(Line header) {
        cursor++;
        String name = tokenText(header, 1);
        if (Strings.isBlank(name)) {
            problem(Severity.ERROR, "gui", "This menu has no name",
                header.first(), "Write the declaration as 'menu <name>:'.",
                List.of("menu shop:"));
            skipBlock(header.depth());
            return null;
        }
        String title = name;
        int size = 27;
        List<Declaration.MenuSlot> slots = new ArrayList<>();

        while (cursor < lines.size() && lines.get(cursor).depth() > header.depth()) {
            Line line = lines.get(cursor);
            int before = cursor;
            switch (line.head()) {
                case "title", "name" -> {
                    title = lineText(line, 1);
                    cursor++;
                }
                case "size" -> {
                    size = lineInt(line, 1, 27);
                    cursor++;
                }
                case "rows" -> {
                    size = lineInt(line, 1, 3) * 9;
                    cursor++;
                }
                case "slot", "button" -> {
                    Declaration.MenuSlot slot = parseMenuSlot(line);
                    if (slot != null) slots.add(slot);
                }
                default -> {
                    unknownProperty(line, "menu", List.of("title", "size", "rows", "slot"));
                    cursor++;
                }
            }
            if (cursor <= before) cursor = before + 1;
        }
        if (slots.isEmpty()) {
            problem(Severity.WARNING, "gui", "Menu '" + name + "' has no buttons",
                header.first(), "A menu without slots opens an empty inventory.", List.of());
        }
        return new Declaration.Menu(name, title, size, slots, span(header));
    }

    /** One clickable slot inside a menu: {@code slot 13: item diamond ... actions }. */
    private Declaration.MenuSlot parseMenuSlot(Line header) {
        cursor++;
        int index = lineInt(header, 1, -1);
        if (index < 0) {
            problem(Severity.ERROR, "gui", "This slot has no index",
                header.first(), "Write the slot as 'slot <0-53>:'.",
                List.of("slot 13:"));
            skipBlock(header.depth());
            return null;
        }
        String material = "stone";
        String displayName = null;
        List<String> lore = new ArrayList<>();
        List<Stmt> body = new ArrayList<>();

        while (cursor < lines.size() && lines.get(cursor).depth() > header.depth()) {
            Line line = lines.get(cursor);
            int before = cursor;
            switch (line.head()) {
                case "item", "material" -> {
                    material = tokenText(line, 1);
                    cursor++;
                }
                case "name", "display-name" -> {
                    displayName = lineText(line, 1);
                    cursor++;
                }
                case "lore", "description" -> {
                    lore.add(lineText(line, 1));
                    cursor++;
                }
                default -> {
                    // Anything else is an ordinary action statement.
                    Stmt statement = parseStatement(line);
                    if (statement != null) body.add(statement);
                }
            }
            if (cursor <= before) cursor = before + 1;
        }
        return new Declaration.MenuSlot(index, material == null ? "stone" : material.toLowerCase(Locale.ROOT),
            displayName, lore, new Stmt.Block(body, span(header)), span(header));
    }

    /** A crafting recipe: {@code recipe planks: result 4 stick / shape "P" / key P oak_planks }. */
    private Declaration parseRecipe(Line header) {
        cursor++;
        String name = tokenText(header, 1);
        if (Strings.isBlank(name)) {
            problem(Severity.ERROR, "recipe", "This recipe has no name",
                header.first(), "Write the declaration as 'recipe <name>:'.",
                List.of("recipe planks:"));
            skipBlock(header.depth());
            return null;
        }
        boolean shaped = false;
        List<String> shape = new ArrayList<>();
        Map<String, String> ingredients = new LinkedHashMap<>();
        String resultMaterial = null;
        int resultAmount = 1;

        while (cursor < lines.size() && lines.get(cursor).depth() > header.depth()) {
            Line line = lines.get(cursor);
            int before = cursor;
            switch (line.head()) {
                case "result" -> {
                    resultAmount = lineInt(line, 1, 1);
                    resultMaterial = tokenText(line, 2);
                    cursor++;
                }
                case "shape", "pattern" -> {
                    // Shape letters are case-insensitive but must keep their case: "D" and
                    // "d" are the same key once normalised, and lowercase would break the
                    // 'key <letter>' lookup for a script that wrote capital letters.
                    for (String word : lineWords(line, 1)) shape.add(word.toUpperCase(Locale.ROOT));
                    shaped = true;
                    cursor++;
                }
                case "key", "ingredient" -> {
                    String key = tokenText(line, 1);
                    String material = tokenText(line, 2);
                    if (!Strings.isBlank(key) && !Strings.isBlank(material)) {
                        ingredients.put(key.toUpperCase(Locale.ROOT), material.toLowerCase(Locale.ROOT));
                    }
                    cursor++;
                }
                case "shapeless" -> {
                    shaped = false;
                    cursor++;
                }
                default -> {
                    unknownProperty(line, "recipe", List.of("result", "shape", "key", "shapeless"));
                    cursor++;
                }
            }
            if (cursor <= before) cursor = before + 1;
        }
        if (Strings.isBlank(resultMaterial)) {
            problem(Severity.ERROR, "recipe", "Recipe '" + name + "' has no result",
                header.first(), "Every recipe needs a result, for example 'result 4 stick'.",
                List.of("result 4 stick"));
            return null;
        }
        if (ingredients.isEmpty()) {
            problem(Severity.ERROR, "recipe", "Recipe '" + name + "' has no ingredients",
                header.first(), "Add at least one 'key <letter> <material>' line.",
                List.of("key P oak_planks"));
            return null;
        }
        for (String candidate : new ArrayList<>(ingredients.values())) {
            if (!vocabulary.isMaterial(candidate)) {
                problem(Severity.ERROR, "material", "Unknown item: " + candidate, header.first(),
                    "The ingredient is not a Minecraft material name or a known alias.",
                    vocabulary.materialSuggestions(candidate));
                return null;
            }
        }
        if (!vocabulary.isMaterial(resultMaterial)) {
            problem(Severity.ERROR, "material", "Unknown item: " + resultMaterial, header.first(),
                "The result is not a Minecraft material name or a known alias.",
                vocabulary.materialSuggestions(resultMaterial));
            return null;
        }
        return new Declaration.Recipe(name, shaped, shape, ingredients, resultMaterial.toLowerCase(Locale.ROOT),
            Math.max(1, resultAmount), span(header));
    }

    /** A named cuboid region: {@code region spawn_area: world world from 0 0 0 to 100 100 100 }. */
    private Declaration parseRegion(Line header) {
        cursor++;
        String name = tokenText(header, 1);
        if (Strings.isBlank(name)) {
            problem(Severity.ERROR, "region", "This region has no name",
                header.first(), "Write the declaration as 'region <name>:'.",
                List.of("region spawn_area:"));
            skipBlock(header.depth());
            return null;
        }
        String world = null;
        Double[] first = null;
        Double[] second = null;

        while (cursor < lines.size() && lines.get(cursor).depth() > header.depth()) {
            Line line = lines.get(cursor);
            int before = cursor;
            switch (line.head()) {
                case "world" -> {
                    world = tokenText(line, 1);
                    cursor++;
                }
                case "from", "corner1", "min" -> {
                    first = new Double[] {lineDouble(line, 1, 0), lineDouble(line, 2, 0), lineDouble(line, 3, 0)};
                    cursor++;
                }
                case "to", "corner2", "max" -> {
                    second = new Double[] {lineDouble(line, 1, 0), lineDouble(line, 2, 0), lineDouble(line, 3, 0)};
                    cursor++;
                }
                case "bounds", "corners" -> {
                    first = new Double[] {lineDouble(line, 1, 0), lineDouble(line, 2, 0), lineDouble(line, 3, 0)};
                    second = new Double[] {lineDouble(line, 4, 0), lineDouble(line, 5, 0), lineDouble(line, 6, 0)};
                    cursor++;
                }
                default -> {
                    unknownProperty(line, "region", List.of("world", "from", "to", "bounds"));
                    cursor++;
                }
            }
            if (cursor <= before) cursor = before + 1;
        }
        if (first == null || second == null) {
            problem(Severity.ERROR, "region", "Region '" + name + "' needs two corners",
                header.first(), "Declare the corners with 'from <x> <y> <z>' and 'to <x> <y> <z>'.",
                List.of("from 0 0 0", "to 100 100 100"));
            return null;
        }
        return new Declaration.Region(name, world, first[0], first[1], first[2], second[0], second[1], second[2],
            span(header));
    }

    /** Reports an unknown keyword inside a gameplay block, naming the accepted ones. */
    private void unknownProperty(Line line, String category, List<String> accepted) {
        List<String> suggestions = new ArrayList<>();
        String head = line.head();
        for (String candidate : accepted) {
            if (candidate.startsWith(head.substring(0, Math.min(head.length(), 3)))) suggestions.add(candidate);
        }
        problem(Severity.WARNING, category, "Unknown setting '" + head + "'",
            line.first(), "A " + category + " block accepts: " + String.join(", ", accepted) + ".",
            suggestions.isEmpty() ? accepted : suggestions);
    }

    // --------------------------------------------------------- value helpers

    private static String tokenText(Line line, int index) {
        List<Token> tokens = line.tokens();
        return index >= 0 && index < tokens.size() ? tokens.get(index).text : null;
    }

    private static String lineText(Line line, int from) {
        List<Token> tokens = line.tokens();
        if (from >= tokens.size()) return "";
        return joinText(tokens.subList(Math.max(0, from), tokens.size()));
    }

    private static List<String> lineWords(Line line, int from) {
        List<String> words = new ArrayList<>();
        List<Token> tokens = line.tokens();
        for (int index = Math.max(0, from); index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.type == TokenType.COMMA) continue;
            words.add(token.text.toLowerCase(Locale.ROOT));
        }
        return words;
    }

    private static int lineInt(Line line, int index, int fallback) {
        String text = tokenText(line, index);
        if (text == null) return fallback;
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static double lineDouble(Line line, int index, double fallback) {
        String text = tokenText(line, index);
        if (text == null) return fallback;
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static boolean lineBool(Line line, int index, boolean fallback) {
        String text = tokenText(line, index);
        if (text == null) return fallback;
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "true", "yes", "on", "enabled" -> true;
            case "false", "no", "off", "disabled" -> false;
            default -> fallback;
        };
    }

    // ------------------------------------------------------------- natural mode

    private Declaration parseNatural(Line line, List<ScriptFile.NaturalUnit> units) {
        String sentence = line.raw();
        cursor++;
        skipBlock(line.depth());
        if (!vocabulary.naturalLanguageEnabled()) {
            problem(Severity.ERROR, "natural-language",
                "Natural language is disabled in config.yml", line.first(),
                "Enable natural-language.enabled or rewrite this rule in the structured syntax.", List.of());
            return null;
        }
        List<Declaration> produced = naturalLanguage.compile(sentence, span(line));
        if (produced == null || produced.isEmpty()) {
            problem(Severity.ERROR, "natural-language", "I could not understand this rule",
                line.first(),
                "AstraSyntax compiles English sentences such as \"when a player joins, welcome them\" "
                    + "into rules. Try rephrasing, or write it in the structured syntax.",
                naturalLanguage.suggestions(sentence));
            return null;
        }
        String pattern = naturalLanguage.lastPattern();
        units.add(new ScriptFile.NaturalUnit(sentence.trim(), produced, pattern, span(line)));
        return new Declaration.Natural(sentence.trim(), produced, pattern, span(line));
    }

    // -------------------------------------------------------------- statements

    private Stmt.Block parseBody(int headerDepth) {
        List<Stmt> statements = new ArrayList<>();
        Set<String> savedVariables = new LinkedHashSet<>(variables);
        while (cursor < lines.size() && lines.get(cursor).depth() > headerDepth) {
            Line line = lines.get(cursor);
            Stmt statement = parseStatement(line);
            if (statement != null) statements.add(statement);
        }
        Span span = cursor > 0 ? span(lines.get(Math.max(0, cursor - 1))) : Span.unknown(file);
        variables.clear();
        variables.addAll(savedVariables);
        return new Stmt.Block(statements, span);
    }

    private Stmt parseStatement(Line line) {
        String head = line.head();
        return switch (head) {
            case "if" -> parseIf(line);
            case "repeat", "loop" -> parseRepeat(line);
            case "wait", "after", "delay", "pause" -> parseDelay(line);
            case "cancel" -> parseCancel(line);
            case "stop", "halt" -> {
                cursor++;
                yield new Stmt.Stop("", span(line));
            }
            case "return" -> parseReturn(line);
            case "call", "invoke" -> parseCall(line);
            case "let", "set" -> parseSetOrAction(line);
            case "require" -> {
                problem(Severity.WARNING, "syntax", "'require' is only valid inside a command",
                    line.first(), "Move this line into a command block or remove it.", List.of());
                cursor++;
                skipBlock(line.depth());
                yield null;
            }
            default -> parseActionStatement(line);
        };
    }

    private Stmt parseIf(Line line) {
        List<Token> tokens = line.tokens();
        List<Token> conditionTokens = tokens.subList(1, tokens.size() - (endsWithColon(line) ? 1 : 0));
        Span conditionSpan = span(line);
        // The header is consumed before the body is read; without this the enclosing body
        // loop would see the same line again and parse it forever.
        cursor++;
        Cond condition = parseConditions(conditionTokens, conditionSpan);
        Stmt.Block then = parseBody(line.depth());
        Stmt.Block otherwise = new Stmt.Block(List.of(), conditionSpan);
        if (cursor < lines.size() && lines.get(cursor).depth() == line.depth()) {
            Line next = lines.get(cursor);
            if (next.head().equals("else")) {
                if (next.tokens().size() > 1 && next.tokens().get(1).is("if")) {
                    // "else if": parse it as a nested if whose block belongs to this chain.
                    Line synthetic = new Line(next.depth(), next.tokens().subList(1, next.tokens().size()),
                        next.line(), next.column(), next.raw());
                    Stmt nested = parseIf(synthetic);
                    otherwise = new Stmt.Block(nested == null ? List.of() : List.of(nested), span(next));
                } else {
                    cursor++;
                    otherwise = parseBody(line.depth());
                }
            }
        }
        return new Stmt.If(condition == null ? List.of() : List.of(condition), then, otherwise, span(line));
    }

    private Stmt parseRepeat(Line line) {
        List<Token> tokens = line.tokens();
        int timesIndex = -1;
        for (int i = 1; i < tokens.size(); i++) {
            if (tokens.get(i).type == TokenType.WORD && (tokens.get(i).text.equalsIgnoreCase("times")
                || tokens.get(i).text.equalsIgnoreCase("time"))) {
                timesIndex = i;
                break;
            }
        }
        List<Token> countTokens = timesIndex > 0
            ? tokens.subList(1, timesIndex)
            : tokens.subList(1, Math.max(1, tokens.size() - (endsWithColon(line) ? 1 : 0)));
        Expr count = parseExpression(countTokens);
        if (count == null) {
            problem(Severity.ERROR, "syntax", "This repeat needs a number",
                line.first(), "Write it as 'repeat 5 times:' or 'repeat <expression> times:'.", List.of());
            count = new Expr.Lit(Value.num(1), span(line));
        }
        String variable = "iteration";
        if (timesIndex > 0 && timesIndex + 1 < tokens.size()) {
            Token next = tokens.get(timesIndex + 1);
            if (next.is("as") || next.is("with")) {
                if (timesIndex + 2 < tokens.size()) {
                    variable = tokens.get(timesIndex + 2).text;
                }
            } else if (next.type == TokenType.WORD && !next.text.equalsIgnoreCase("as")) {
                // "repeat 5 times i" is accepted as shorthand.
                variable = next.text;
            }
        }
        variables.add(variable.toLowerCase(Locale.ROOT));
        // The header is consumed before the body is read, so the enclosing loop advances.
        cursor++;
        Stmt.Block body = parseBody(line.depth());
        long limit = 10000L;
        return new Stmt.Repeat(count, variable, body, limit, span(line));
    }

    private Stmt parseDelay(Line line) {
        List<Token> tokens = line.tokens();
        List<Token> durationTokens = new ArrayList<>();
        for (int i = 1; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.type == TokenType.COLON) break;
            durationTokens.add(token);
        }
        String raw = joinText(durationTokens);
        long ticks = parseDuration(raw, line.first(), "delay");
        Stmt.Block body = endsWithColon(line) ? parseBody(line.depth()) : new Stmt.Block(List.of(), span(line));
        if (!endsWithColon(line)) cursor++;
        return new Stmt.Delay(ticks, raw, body, span(line));
    }

    private Stmt parseCancel(Line line) {
        cursor++;
        String scope = "event";
        for (int i = 1; i < line.tokens().size(); i++) {
            String word = line.tokens().get(i).text.toLowerCase(Locale.ROOT);
            if (word.startsWith("task")) scope = "task";
        }
        return new Stmt.Cancel(scope, span(line));
    }

    private Stmt parseReturn(Line line) {
        List<Token> tokens = line.tokens();
        cursor++;
        if (tokens.size() <= 1) return new Stmt.Return(null, span(line));
        Expr value = parseExpression(tokens.subList(1, tokens.size()));
        return new Stmt.Return(value, span(line));
    }

    private Stmt parseCall(Line line) {
        List<Token> tokens = line.tokens();
        cursor++;
        if (tokens.size() < 2) {
            problem(Severity.ERROR, "action", "This call needs a name",
                line.first(), "Write it as 'call <name>(arguments)'.", List.of());
            return null;
        }
        String name = tokens.get(1).text;
        Map<String, Expr> arguments = new LinkedHashMap<>();
        int index = 2;
        if (index < tokens.size() && tokens.get(index).type == TokenType.LPAREN) {
            index++;
            List<Token> current = new ArrayList<>();
            int position = 0;
            String named = null;
            while (index <= tokens.size()) {
                Token token = index < tokens.size() ? tokens.get(index) : null;
                if (token == null || token.type == TokenType.RPAREN || token.type == TokenType.COMMA) {
                    if (!current.isEmpty()) {
                        Expr value = parseExpression(current);
                        if (value != null) {
                            arguments.put(named != null ? named : "arg" + position, value);
                        }
                        position++;
                    }
                    current.clear();
                    named = null;
                    if (token == null || token.type == TokenType.RPAREN) break;
                    index++;
                    continue;
                }
                if (token.type == TokenType.COLON || token.type == TokenType.EQ) {
                    named = joinText(current);
                    current.clear();
                    index++;
                    continue;
                }
                current.add(token);
                index++;
            }
        }
        return new Stmt.Call(name, arguments, span(line));
    }

    /** {@code set <data> = <value>} style statements are compiled to the set-data action. */
    private Stmt parseSetOrAction(Line line) {
        return parseActionStatement(line);
    }

    private Stmt parseActionStatement(Line line) {
        pendingProblem = null;
        lastCaptures.clear();
        ActionMatch match = matchAction(line.tokens());
        if (match != null) {
            cursor++;
            return new Stmt.Action(match.id(), match.arguments(), span(line));
        }
        Problem problem = pendingProblem;
        pendingProblem = null;
        if (problem != null) {
            report(problem);
        } else {
            String word = line.head();
            List<String> suggestions = new ArrayList<>(vocabulary.actionSuggestions(word));
            suggestions.addAll(vocabulary.conditionSuggestions(word));
            problem(Severity.ERROR, "action", "Unknown statement: " + joinText(line.tokens()),
                line.first(),
                "AstraSyntax does not have an action for this line.",
                suggestions);
        }
        cursor++;
        skipBlock(line.depth());
        return null;
    }

    // -------------------------------------------------------------- conditions

    private Cond parseConditions(List<Token> tokens, Span span) {
        if (tokens == null || tokens.isEmpty()) return null;
        List<Token> trimmed = trimParens(tokens);
        int index = indexOfTopLevelWord(trimmed, "or");
        if (index > 0) {
            Cond left = parseConditions(trimmed.subList(0, index), span);
            Cond right = parseConditions(trimmed.subList(index + 1, trimmed.size()), span);
            if (left == null) return right;
            if (right == null) return left;
            return new Cond.Or(List.of(left, right), span);
        }
        index = indexOfTopLevelWord(trimmed, "and");
        if (index > 0) {
            Cond left = parseConditions(trimmed.subList(0, index), span);
            Cond right = parseConditions(trimmed.subList(index + 1, trimmed.size()), span);
            if (left == null) return right;
            if (right == null) return left;
            return new Cond.And(List.of(left, right), span);
        }
        if (!trimmed.isEmpty() && (trimmed.get(0).is("not") || trimmed.get(0).type == TokenType.BANG)) {
            Cond inner = parseConditions(trimmed.subList(1, trimmed.size()), span);
            return inner == null ? null : new Cond.Not(inner, span);
        }
        // A comparison written with a symbol: "level > 5".
        int symbol = indexOfComparisonSymbol(trimmed);
        if (symbol > 0) {
            Expr left = parseExpression(trimmed.subList(0, symbol));
            Expr right = parseExpression(trimmed.subList(symbol + 1, trimmed.size()));
            Cond.Operator operator = operatorFor(trimmed.get(symbol));
            if (left != null && right != null) return new Cond.Compare(left, operator, right, span);
        }
        return parseConditionAtom(trimmed, span);
    }

    private Cond parseConditionAtom(List<Token> tokens, Span span) {
        pendingProblem = null;
        // 1. A registered condition ("player has permission astra.vip").
        ConditionMatch match = matchCondition(tokens);
        if (match != null) return new Cond.Test(match.id(), match.arguments(), span);

        // 2. A comparison ("message contains \"hello\"").
        Cond.Operator operator = null;
        int operatorIndex = -1;
        int operatorLength = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Cond.Operator candidate = operatorAt(tokens, i);
            if (candidate != null) {
                operator = candidate;
                operatorIndex = i;
                operatorLength = operatorLengthAt(tokens, i, candidate);
                break;
            }
        }
        if (operator != null && operatorIndex > 0 && operatorIndex + operatorLength <= tokens.size()) {
            Expr left = parseExpression(tokens.subList(0, operatorIndex));
            Expr right = operatorIndex + operatorLength < tokens.size()
                ? parseExpression(tokens.subList(operatorIndex + operatorLength, tokens.size()))
                : null;
            if (left != null && right != null) {
                return new Cond.Compare(left, operator, right, span);
            }
        }

        // 3. A bare expression: its truthiness decides.
        Expr expression = parseExpression(tokens);
        if (expression != null) {
            return new Cond.Compare(expression, Cond.Operator.EQUALS,
                new Expr.Lit(Value.bool(true), span), span);
        }
        Problem problem = pendingProblem;
        pendingProblem = null;
        if (problem != null) report(problem);
        else {
            problem(Severity.ERROR, "syntax", "This condition could not be read", 
                tokens.isEmpty() ? null : tokens.get(0),
                "Write a condition such as 'has permission astra.vip' or 'coins of player is above 100'.",
                vocabulary.conditionSuggestions(joinText(tokens)));
        }
        return null;
    }

    private Cond.Operator operatorAt(List<Token> tokens, int index) {
        Token token = tokens.get(index);
        if (token.type == TokenType.GT) return Cond.Operator.ABOVE;
        if (token.type == TokenType.LT) return Cond.Operator.BELOW;
        if (token.type == TokenType.GT_EQ) return Cond.Operator.AT_LEAST;
        if (token.type == TokenType.LT_EQ) return Cond.Operator.AT_MOST;
        if (token.type == TokenType.EQ_EQ) return Cond.Operator.EQUALS;
        if (token.type == TokenType.BANG_EQ) return Cond.Operator.NOT_EQUALS;
        if (token.type != TokenType.WORD) return null;
        String phrase = phraseAt(tokens, index).toLowerCase(Locale.ROOT);
        for (Cond.Operator operator : Cond.Operator.values()) {
            for (String candidate : Cond.Operator.phrases(operator)) {
                if (phrase.startsWith(candidate + " ") || phrase.equals(candidate)) return operator;
            }
        }
        return null;
    }

    private int operatorLengthAt(List<Token> tokens, int index, Cond.Operator operator) {
        for (String phrase : Cond.Operator.phrases(operator)) {
            String[] words = phrase.split(" ");
            if (index + words.length > tokens.size()) continue;
            boolean matches = true;
            for (int i = 0; i < words.length; i++) {
                if (!tokens.get(index + i).text.equalsIgnoreCase(words[i])) {
                    matches = false;
                    break;
                }
            }
            if (matches) return words.length;
        }
        return 1;
    }

    private static String phraseAt(List<Token> tokens, int index) {
        StringBuilder out = new StringBuilder();
        for (int i = index; i < Math.min(tokens.size(), index + 3); i++) {
            if (i > index) out.append(' ');
            out.append(tokens.get(i).text);
        }
        return out.toString();
    }

    private static Cond.Operator operatorFor(Token token) {
        return switch (token.type) {
            case LT -> Cond.Operator.BELOW;
            case GT -> Cond.Operator.ABOVE;
            case LT_EQ -> Cond.Operator.AT_MOST;
            case GT_EQ -> Cond.Operator.AT_LEAST;
            case BANG_EQ -> Cond.Operator.NOT_EQUALS;
            default -> Cond.Operator.EQUALS;
        };
    }

    private static int indexOfComparisonSymbol(List<Token> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            TokenType type = tokens.get(i).type;
            if (type == TokenType.GT || type == TokenType.LT || type == TokenType.GT_EQ
                || type == TokenType.LT_EQ || type == TokenType.EQ_EQ || type == TokenType.BANG_EQ) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfTopLevelWord(List<Token> tokens, String word) {
        int depth = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.type == TokenType.LPAREN) depth++;
            else if (token.type == TokenType.RPAREN) depth--;
            else if (depth == 0 && token.type == TokenType.WORD && token.text.equalsIgnoreCase(word)
                && i > 0 && i < tokens.size() - 1) {
                return i;
            }
        }
        return -1;
    }

    private static List<Token> trimParens(List<Token> tokens) {
        if (tokens.size() < 2) return tokens;
        if (tokens.get(0).type != TokenType.LPAREN || tokens.get(tokens.size() - 1).type != TokenType.RPAREN) {
            return tokens;
        }
        int depth = 0;
        for (int i = 0; i < tokens.size(); i++) {
            TokenType type = tokens.get(i).type;
            if (type == TokenType.LPAREN) depth++;
            if (type == TokenType.RPAREN) {
                depth--;
                if (depth == 0 && i != tokens.size() - 1) return tokens;
            }
        }
        return tokens.subList(1, tokens.size() - 1);
    }

    // ------------------------------------------------------------------ matching

    private record ActionMatch(String id, Map<String, Expr> arguments) { }

    private record ConditionMatch(String id, Map<String, Expr> arguments) { }

    private ActionMatch matchAction(List<Token> tokens) {
        ActionMatch best = null;
        int bestScore = -1;
        Problem bestProblem = null;
        for (Vocabulary.Entry entry : vocabulary.actions()) {
            pendingProblem = null;
            TemplateMatcher.Match match = TemplateMatcher.match(entry.template(), tokens, binder());
            bestProblem = keepProblem(bestProblem, pendingProblem);
            if (match == null) continue;
            if (match.score() > bestScore) {
                bestScore = match.score();
                best = new ActionMatch(entry.id(), match.arguments());
            }
        }
        pendingProblem = bestProblem;
        return best;
    }

    private ConditionMatch matchCondition(List<Token> tokens) {
        ConditionMatch best = null;
        int bestScore = -1;
        Problem bestProblem = null;
        for (Vocabulary.Entry entry : vocabulary.conditions()) {
            pendingProblem = null;
            TemplateMatcher.Match match = TemplateMatcher.match(entry.template(), tokens, binder());
            bestProblem = keepProblem(bestProblem, pendingProblem);
            if (match == null) continue;
            if (match.score() > bestScore) {
                bestScore = match.score();
                best = new ConditionMatch(entry.id(), match.arguments());
            }
        }
        pendingProblem = bestProblem;
        return best;
    }

    /**
     * Keep the most useful problem seen while trying the templates.
     *
     * <p>Every attempt resets {@link #pendingProblem}, so a template that failed on
     * {@code "give player 5 dimonds"} would otherwise lose its "unknown item: dimonds
     * (did you mean diamond?)" message to the next template that failed for a duller
     * reason. Value-level problems (unknown item, unknown mob, bad number) win over the
     * generic "does not match" fallback because they name the offending word.</p>
     */
    private static Problem keepProblem(Problem current, Problem candidate) {
        if (candidate == null) return current;
        if (current == null) return candidate;
        return specificity(candidate) > specificity(current) ? candidate : current;
    }

    private static int specificity(Problem problem) {
        return switch (problem.category()) {
            case "material", "entity" -> 3;
            case "number", "duration" -> 2;
            default -> 1;
        };
    }

    private record TriggerMatch(String triggerId, List<Cond> filters) { }

    private TriggerMatch matchTrigger(List<Token> tokens) {
        TriggerMatch best = null;
        int bestScore = -1;
        for (Vocabulary.EventPattern pattern : vocabulary.eventPatterns()) {
            pendingProblem = null;
            Map<String, Expr> captures = new LinkedHashMap<>();
            TemplateMatcher.Match match = TemplateMatcher.match(pattern.template(), tokens,
                (slot, slice, greedy) -> {
                    Expr bound = binder().bind(slot, slice, greedy);
                    if (bound != null) captures.put(slot.name(), bound);
                    return bound;
                });
            if (match == null) continue;
            if (match.score() <= bestScore) continue;
            List<Cond> filters = new ArrayList<>();
            for (String spec : pattern.filterSpecs()) {
                Cond filter = filterFrom(spec, match.arguments());
                if (filter != null) filters.add(filter);
            }
            bestScore = match.score();
            best = new TriggerMatch(pattern.triggerId(), filters);
        }
        return best;
    }

    /**
     * Turn a filter specification into a prefilled event property condition.
     *
     * <p>Forms: {@code property:slot} compares the event property with the slot value,
     * {@code property:true} and {@code property:false} test its truthiness, and a bare
     * {@code property} requires it to be present.</p>
     */
    private Cond filterFrom(String spec, Map<String, Expr> captures) {
        if (spec == null || spec.isBlank()) return null;
        String property = spec;
        String target = null;
        int colon = spec.indexOf(':');
        if (colon > 0) {
            property = spec.substring(0, colon);
            target = spec.substring(colon + 1);
        }
        Span span = new Span(file, 0, 0, 0, Span.Flavor.GENERATED);
        if (target == null) {
            // A bare property is a truthiness test: "on entity death:" filtering on
            // "killer is a player" needs no explicit value.
            return new Cond.EventProperty(property, new Expr.Lit(Value.bool(true), span), span);
        }
        if (target.equalsIgnoreCase("true") || target.equalsIgnoreCase("false")) {
            return new Cond.EventProperty(property, new Expr.Lit(Value.bool(Boolean.parseBoolean(target)), span), span);
        }
        Expr value = captures.get(target);
        if (value == null) return null;
        return new Cond.EventProperty(property, value, span);
    }

    // ------------------------------------------------------------------- binder

    private TemplateMatcher.SlotBinder binder() {
        return this::bindSlot;
    }

    private Expr bindSlot(SyntaxTemplate.Slot slot, List<Token> slice, boolean greedy) {
        if (slice.isEmpty()) return null;
        return switch (slot.type()) {
            case TEXT -> textExpression(slice);
            case STRING, WORD -> singleString(slice);
            case PERMISSION -> permission(slice);
            case NUMBER -> number(slice, false);
            case DECIMAL -> number(slice, true);
            case BOOLEAN -> bool(slice);
            case DURATION -> duration(slice);
            case MATERIAL -> material(slice, false);
            case ITEM -> material(slice, true);
            case ENTITY -> entity(slice);
            case WORLD -> singleString(slice);
            case DATA_KEY -> dataKey(slice);
            case TARGET -> target(slice);
            case EXPR -> parseExpression(slice);
            case LOCATION -> location(slice);
            case LIST -> parseExpression(slice);
        };
    }

    private Expr textExpression(List<Token> slice) {
        String text = joinText(slice);
        if (text.indexOf('{') >= 0 || text.indexOf('%') >= 0) {
            return new Expr.Interpolated(text, span(slice));
        }
        return new Expr.Lit(Value.str(text), span(slice));
    }

    private Expr singleString(List<Token> slice) {
        if (slice.size() != 1) {
            // Multi-word values are accepted only when quoted.
            if (slice.stream().allMatch(token -> token.type == TokenType.WORD)
                && slice.size() <= 3 /* words such as "diamond sword" */) {
                return new Expr.Lit(Value.str(joinText(slice)), span(slice));
            }
            return null;
        }
        Token token = slice.get(0);
        if (token.type == TokenType.STRING) return new Expr.Lit(Value.str(token.text), span(slice));
        if (token.type == TokenType.WORD || token.type == TokenType.NUMBER || token.type == TokenType.DECIMAL) {
            return new Expr.Lit(Value.str(token.text), span(slice));
        }
        return null;
    }

    private Expr permission(List<Token> slice) {
        String text = joinText(slice);
        if (text.isBlank()) return null;
        return new Expr.Lit(Value.str(text.replace(" ", "")), span(slice));
    }

    private Expr number(List<Token> slice, boolean decimal) {
        if (slice.size() != 1) {
            // A computed amount ("5 + level of player") is allowed for number slots.
            Expr expression = parseExpression(slice);
            if (expression != null) return expression;
            return null;
        }
        Token token = slice.get(0);
        if (token.type == TokenType.NUMBER) {
            return decimal ? new Expr.Lit(Value.dec(parseDouble(token.text)), span(slice))
                : new Expr.Lit(Value.num(parseLong(token.text)), span(slice));
        }
        if (token.type == TokenType.DECIMAL && decimal) {
            return new Expr.Lit(Value.dec(parseDouble(token.text)), span(slice));
        }
        Expr expression = parseExpression(slice);
        if (expression != null) return expression;
        Token first = slice.get(0);
        pendingProblem = new Problem(Severity.ERROR, "number",
            "'" + token.text + "' is not a number", first,
            "Write a whole number, or an expression that produces one.", List.of());
        return null;
    }

    private Expr bool(List<Token> slice) {
        if (slice.size() != 1) return null;
        String word = slice.get(0).text.toLowerCase(Locale.ROOT);
        return switch (word) {
            case "true", "yes", "on", "enabled" -> new Expr.Lit(Value.bool(true), span(slice));
            case "false", "no", "off", "disabled" -> new Expr.Lit(Value.bool(false), span(slice));
            default -> null;
        };
    }

    private Expr duration(List<Token> slice) {
        String raw = joinText(slice);
        try {
            return new Expr.Lit(Value.num(Durations.parseTicks(raw)), span(slice));
        } catch (IllegalArgumentException error) {
            Token first = slice.get(0);
            pendingProblem = new Problem(Severity.ERROR, "duration",
                "'" + raw + "' is not a duration", first,
                "Write a duration such as '30 seconds', '5 minutes' or '1 hour'.", List.of());
            return null;
        }
    }

    private Expr material(List<Token> slice, boolean withAmount) {
        int index = 0;
        long amount = 1;
        if (withAmount && slice.get(0).type == TokenType.NUMBER) {
            amount = parseLong(slice.get(0).text);
            index = 1;
        }
        if (index >= slice.size()) return null;
        for (Token token : slice.subList(index, slice.size())) {
            if (token.type != TokenType.WORD && token.type != TokenType.STRING) {
                if (!withAmount) return null;
            }
        }
        String name = joinText(slice.subList(index, slice.size()));
        if (name.isBlank()) return null;
        if (!vocabulary.isMaterial(name)) {
            Token first = slice.get(index);
            pendingProblem = new Problem(Severity.ERROR, "material", "Unknown item: " + name, first,
                "That name is not a Minecraft item.", () -> vocabulary.materialSuggestions(name));
            return null;
        }
        Expr material = new Expr.Lit(Value.str(name), span(slice));
        Expr amountExpression = new Expr.Lit(Value.num(Math.max(1L, amount)), span(slice));
        return new Expr.Item(material, amountExpression, span(slice));
    }

    private Expr entity(List<Token> slice) {
        String name = joinText(slice);
        if (!vocabulary.isEntityType(name)) {
            List<String> suggestions = vocabulary.entitySuggestions(name);
            pendingProblem = new Problem(Severity.ERROR, "entity",
                "Unknown mob: " + name, slice.get(0), "That is not a Minecraft entity type.", suggestions);
            return null;
        }
        return new Expr.Lit(Value.str(name), span(slice));
    }

    private Expr dataKey(List<Token> slice) {
        String key = joinText(slice);
        if (key.isBlank()) return null;
        dataKeys.add(key.toLowerCase(Locale.ROOT));
        return new Expr.Lit(Value.str(key), span(slice));
    }

    private Expr target(List<Token> slice) {
        Expr expression = parseExpression(slice);
        if (expression != null) return expression;
        return singleString(slice);
    }

    private Expr location(List<Token> slice) {
        // "world 10 64 20" or three plain numbers.
        if (slice.size() >= 3) {
            List<Token> tail = slice.subList(slice.size() - 3, slice.size());
            if (tail.stream().allMatch(token -> token.type == TokenType.NUMBER || token.type == TokenType.DECIMAL)) {
                Expr world = slice.size() > 3 ? singleString(slice.subList(0, slice.size() - 3)) : null;
                return new Expr.Position(world,
                    new Expr.Lit(Value.dec(parseDouble(tail.get(0).text)), span(slice)),
                    new Expr.Lit(Value.dec(parseDouble(tail.get(1).text)), span(slice)),
                    new Expr.Lit(Value.dec(parseDouble(tail.get(2).text)), span(slice)),
                    null, null, span(slice));
            }
        }
        return parseExpression(slice);
    }

    private Expr parseExpression(List<Token> slice) {
        return ExpressionParser.parseStrict(slice, new ExpressionParser.Resolver() {
            @Override public Vocabulary vocabulary() {
                return vocabulary;
            }

            @Override public boolean isDataKey(String name) {
                return dataKeys.contains(name.toLowerCase(Locale.ROOT));
            }

            @Override public boolean isVariable(String name) {
                return variables.contains(name.toLowerCase(Locale.ROOT));
            }

            @Override public String file() {
                return file;
            }

            @Override public Span span(List<Token> tokens) {
                return AstraParser.this.span(tokens);
            }

            @Override public void report(String category, String message, Token token, String explanation,
                                         List<String> suggestions) {
                pendingProblem = new Problem(Severity.ERROR, category, message, token, explanation, suggestions);
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    /** A problem that was noticed while matching but is only reported if the match fails. */
    /**
     * A diagnostic found while matching, kept until it is known to be the best one.
     *
     * <p>Suggestions are computed on demand: template matching probes a dozen alternatives
     * for one line, and ranking a misspelling against the whole material catalogue on every
     * probe is the difference between a snappy parse and a visible stall. The result is
     * memoised, so asking twice is free.</p>
     */
    private static final class Problem {
        private final Severity severity;
        private final String category;
        private final String message;
        private final Token token;
        private final String explanation;
        private final java.util.function.Supplier<List<String>> suggestionSource;
        private List<String> suggestions;

        Problem(Severity severity, String category, String message, Token token, String explanation,
                List<String> suggestions) {
            this(severity, category, message, token, explanation, () -> suggestions);
        }

        Problem(Severity severity, String category, String message, Token token, String explanation,
                java.util.function.Supplier<List<String>> suggestionSource) {
            this.severity = severity;
            this.category = category;
            this.message = message;
            this.token = token;
            this.explanation = explanation;
            this.suggestionSource = suggestionSource;
        }

        Severity severity() {
            return severity;
        }

        String category() {
            return category;
        }

        String message() {
            return message;
        }

        Token token() {
            return token;
        }

        String explanation() {
            return explanation;
        }

        List<String> suggestions() {
            if (suggestions == null) {
                List<String> computed = suggestionSource == null ? List.of() : suggestionSource.get();
                suggestions = computed == null ? List.of() : List.copyOf(computed);
            }
            return suggestions;
        }
    }

    private record Line(int depth, List<Token> tokens, int line, int column, String raw) {
        Token first() {
            return tokens.isEmpty() ? new Token(TokenType.WORD, "", line, column, 0, 0) : tokens.get(0);
        }

        String head() {
            return tokens.isEmpty() ? "" : tokens.get(0).text.toLowerCase(Locale.ROOT);
        }
    }

    private List<Line> toLines(List<Token> tokens) {
        List<Line> result = new ArrayList<>();
        int depth = 0;
        List<Token> current = new ArrayList<>();
        int line = 0;
        int column = 0;
        for (Token token : tokens) {
            switch (token.type) {
                case NEWLINE, EOF -> {
                    if (!current.isEmpty()) {
                        result.add(new Line(depth, List.copyOf(current), line, column, rawLine(line)));
                        current.clear();
                    }
                }
                case INDENT -> depth++;
                case DEDENT -> depth = Math.max(0, depth - 1);
                default -> {
                    if (current.isEmpty()) {
                        line = token.line;
                        column = token.column;
                    }
                    current.add(token);
                }
            }
        }
        if (!current.isEmpty()) {
            result.add(new Line(depth, List.copyOf(current), line, column, rawLine(line)));
        }
        return result;
    }

    private String rawLine(int lineNumber) {
        if (lineNumber <= 0 || lineNumber > rawLines.size()) return "";
        return rawLines.get(lineNumber - 1);
    }

    private void skipBlock(int headerDepth) {
        while (cursor < lines.size() && lines.get(cursor).depth() > headerDepth) cursor++;
    }

    private boolean endsWithColon(Line line) {
        List<Token> tokens = line.tokens();
        return !tokens.isEmpty() && tokens.get(tokens.size() - 1).type == TokenType.COLON;
    }

    private Span span(Line line) {
        return new Span(file, line.line(), line.column(), 0);
    }

    private Span span(List<Token> tokens) {
        if (tokens == null || tokens.isEmpty()) return Span.unknown(file);
        return span(tokens.get(0));
    }

    private Span span(Token token) {
        return new Span(file, token.line, token.column, token.length());
    }

    private long parseDuration(String raw, Token token, String what) {
        try {
            return Durations.parseTicks(raw);
        } catch (IllegalArgumentException error) {
            problem(Severity.ERROR, "duration", "'" + raw + "' is not a valid " + what + " interval",
                token, "Write it as a number followed by a unit, for example '30 seconds' or '10 minutes'.",
                List.of());
            return 20L;
        }
    }

    private Object literal(Token token) {
        return switch (token.type) {
            case NUMBER -> parseLong(token.text);
            case DECIMAL -> parseDouble(token.text);
            case STRING -> token.text;
            case WORD -> switch (token.text.toLowerCase(Locale.ROOT)) {
                case "true", "yes", "on" -> true;
                case "false", "no", "off" -> false;
                default -> token.text;
            };
            default -> token.text;
        };
    }

    private static List<String> splitList(String text) {
        List<String> out = new ArrayList<>();
        for (String part : text.split(",")) {
            if (!part.isBlank()) out.add(part.trim());
        }
        return out;
    }

    private static String joinText(List<Token> tokens) {
        StringBuilder out = new StringBuilder();
        for (Token token : tokens) {
            if (out.length() > 0) {
                if (token.type == TokenType.COMMA) {
                    out.append(',');
                    continue;
                }
                if (token.type == TokenType.DOT) {
                    out.append('.');
                    continue;
                }
                out.append(' ');
            }
            out.append(token.text);
        }
        return out.toString().trim();
    }

    private static long parseLong(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static double parseDouble(String text) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return 0d;
        }
    }

    private void report(Problem problem) {
        // Suggestions are resolved here, once, for the diagnostic that is actually kept.
        // The "did you mean" clause is appended now so the message stays complete while the
        // ranking only happens for the winner.
        List<String> suggestions = problem.suggestions();
        String message = problem.message();
        if (!suggestions.isEmpty() && !message.contains("did you mean")) {
            message = message + " (did you mean " + String.join(", ", suggestions) + "?)";
        }
        problem(problem.severity(), problem.category(), message, problem.token(),
            problem.explanation(), suggestions);
    }

    private void problem(Severity severity, String category, String message, Token token, String explanation,
                         List<String> suggestions) {
        int line = token == null ? 0 : token.line;
        int column = token == null ? 0 : token.column;
        int length = token == null ? 0 : token.length();
        diagnostics.add(new Diagnostic(severity, category, message, file, line, column, length, explanation,
            suggestions == null ? List.of() : suggestions, ""));
    }
}
