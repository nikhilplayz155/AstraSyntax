package io.astra.language.parser;

import io.astra.util.Strings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A parsed syntax declaration such as
 * {@code give <who:target> <amount:number?> <item:item>}.
 *
 * <p>Template grammar:</p>
 * <ul>
 *   <li>{@code word} - a literal keyword; alternatives are separated with {@code |}</li>
 *   <li>{@code <name:type>} - a required slot</li>
 *   <li>{@code <name:type?>} - an optional slot (may be omitted at the end)</li>
 *   <li>{@code <name:type...>} - a greedy slot that consumes the rest of the statement</li>
 * </ul>
 *
 * <p>The template is compiled once, when the action is registered, so matching at
 * parse time is a simple walk over the token list with backtracking.</p>
 */
public record SyntaxTemplate(String source, List<Segment> segments) {

    /** One element of a template. */
    public sealed interface Segment permits Literal, Slot { }

    /** A literal keyword (with optional alternatives). */
    public record Literal(List<String> alternatives) implements Segment {
        public boolean matches(String word) {
            for (String alternative : alternatives) {
                if (alternative.equalsIgnoreCase(word)) return true;
            }
            return false;
        }

        public String display() {
            return String.join("|", alternatives);
        }
    }

    /** A named, typed slot. */
    public record Slot(String name, SlotType type, boolean optional, boolean greedy) implements Segment {
        public String display() {
            return "<" + name + ":" + type.name().toLowerCase(Locale.ROOT) + (optional ? "?" : "") + ">";
        }
    }

    /** Compile a template string. */
    public static SyntaxTemplate compile(String source) {
        List<Segment> segments = new ArrayList<>();
        if (Strings.isBlank(source)) return new SyntaxTemplate("", segments);
        int i = 0;
        String text = source.trim();
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '<') {
                int close = text.indexOf('>', i);
                if (close < 0) {
                    segments.add(new Literal(List.of(text.substring(i))));
                    break;
                }
                String body = text.substring(i + 1, close).trim();
                boolean optional = body.endsWith("?");
                boolean greedy = body.endsWith("...");
                if (optional) body = body.substring(0, body.length() - 1).trim();
                if (greedy) body = body.substring(0, body.length() - 3).trim();
                String name = body;
                SlotType type = SlotType.STRING;
                int colon = body.indexOf(':');
                if (colon >= 0) {
                    name = body.substring(0, colon).trim();
                    type = SlotType.parse(body.substring(colon + 1));
                }
                segments.add(new Slot(name, type, optional, greedy));
                i = close + 1;
                continue;
            }
            int end = i;
            while (end < text.length() && !Character.isWhitespace(text.charAt(end))) end++;
            String word = text.substring(i, end);
            List<String> alternatives = new ArrayList<>();
            for (String alternative : word.split("\\|")) {
                if (!alternative.isBlank()) alternatives.add(alternative);
            }
            segments.add(new Literal(alternatives));
            i = end;
        }
        return new SyntaxTemplate(text, segments);
    }

    /** Slots in declaration order. */
    public List<Slot> slots() {
        List<Slot> out = new ArrayList<>();
        for (Segment segment : segments) {
            if (segment instanceof Slot slot) out.add(slot);
        }
        return out;
    }

    /** True when the template has no slot that consumes the rest of the statement. */
    /** True when a slot with this name appears in the template. */
    public boolean hasSlot(String name) {
        for (Segment segment : segments) {
            if (segment instanceof Slot slot && slot.name().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    public boolean isClosed() {
        for (Segment segment : segments) {
            if (segment instanceof Slot slot && slot.greedy()) return false;
        }
        return true;
    }

    /** Number of required (non optional) slots. */
    public int requiredSlots() {
        int count = 0;
        for (Segment segment : segments) {
            if (segment instanceof Slot slot && !slot.optional()) count++;
        }
        return count;
    }

    @Override
    public String toString() {
        return source;
    }
}
