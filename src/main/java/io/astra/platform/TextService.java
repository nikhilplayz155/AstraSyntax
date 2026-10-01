package io.astra.platform;

import io.astra.util.Strings;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * MiniMessage-flavoured text rendering that works on every supported platform.
 *
 * <p>AstraSyntax is compiled against the shared Bukkit API, so it cannot depend on
 * Adventure classes being present. Instead, the tag syntax used by the shipped
 * {@code language.yml} prefix ({@code <gradient:#8A5CFF:#41C7FF>Astra</gradient>})
 * is rendered here into legacy section-sign codes, which every server - Spigot
 * included - renders correctly.</p>
 *
 * <p>Supported: named colours, {@code <#RRGGBB>}, {@code <color:#RRGGBB>},
 * bold/italic/underline/strikethrough/obfuscated with matching close tags,
 * {@code <reset>} and {@code <gradient:from:to>...</gradient>}.
 * Unknown tags are preserved verbatim so a future tag never turns into an error.</p>
 */
public final class TextService {

    private static final Map<String, String> NAMED = Map.ofEntries(
        Map.entry("black", "\u00a70"), Map.entry("dark_blue", "\u00a71"), Map.entry("dark_green", "\u00a72"),
        Map.entry("dark_aqua", "\u00a73"), Map.entry("dark_red", "\u00a74"), Map.entry("dark_purple", "\u00a75"),
        Map.entry("gold", "\u00a76"), Map.entry("gray", "\u00a77"), Map.entry("grey", "\u00a77"),
        Map.entry("dark_gray", "\u00a78"), Map.entry("dark_grey", "\u00a78"), Map.entry("blue", "\u00a79"),
        Map.entry("green", "\u00a7a"), Map.entry("aqua", "\u00a7b"), Map.entry("red", "\u00a7c"),
        Map.entry("light_purple", "\u00a7d"), Map.entry("yellow", "\u00a7e"), Map.entry("white", "\u00a7f"),
        Map.entry("bold", "\u00a7l"), Map.entry("b", "\u00a7l"), Map.entry("italic", "\u00a7o"),
        Map.entry("i", "\u00a7o"), Map.entry("em", "\u00a7o"), Map.entry("underlined", "\u00a7n"),
        Map.entry("u", "\u00a7n"), Map.entry("underline", "\u00a7n"), Map.entry("strikethrough", "\u00a7m"),
        Map.entry("st", "\u00a7m"), Map.entry("obfuscated", "\u00a7k"), Map.entry("obf", "\u00a7k"));

    private final boolean logUnknownTags;

    public TextService(boolean logUnknownTags) {
        this.logUnknownTags = logUnknownTags;
    }

    /** Render a template without placeholder substitution. */
    public String render(String template) {
        return render(template, UnaryOperator.identity());
    }

    /**
     * Render a template, first expanding placeholders through {@code resolver}
     * (Astra placeholders such as {@code {player}} live there, not here).
     */
    public String render(String template, UnaryOperator<String> resolver) {
        if (template == null || template.isEmpty()) return "";
        String expanded = resolver == null ? template : resolver.apply(template);
        return legacy(expanded);
    }

    /** Public entry point used by tests: tag string to legacy string. */
    public String legacy(String input) {
        StringBuilder out = new StringBuilder(input.length() + 16);
        Deque<String> activeStyles = new ArrayDeque<>();
        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);
            if (c != '<') {
                out.append(c);
                i++;
                continue;
            }
            int close = input.indexOf('>', i);
            if (close < 0) {
                out.append(input.substring(i));
                break;
            }
            String tag = input.substring(i + 1, close).trim();
            String lower = tag.toLowerCase(Locale.ROOT);

            if (lower.startsWith("gradient:")) {
                int end = findClosing(input, close + 1, "gradient");
                String inner = end < 0 ? input.substring(close + 1) : input.substring(close + 1, end);
                String[] colors = tag.substring("gradient:".length()).split(":");
                out.append(gradient(inner, colors));
                i = end < 0 ? input.length() : input.indexOf('>', end) + 1;
                continue;
            }
            if (lower.equals("reset")) {
                activeStyles.clear();
                out.append("\u00a7r");
                i = close + 1;
                continue;
            }
            if (lower.startsWith("/")) {
                String name = lower.substring(1);
                if (NAMED.containsKey(name) || name.startsWith("#") || name.startsWith("color:")) {
                    activeStyles.removeIf(style -> style.equals(name));
                    out.append("\u00a7r");
                    for (String style : activeStyles) out.append(section(style));
                } else {
                    out.append(input, i, close + 1);
                }
                i = close + 1;
                continue;
            }
            String section = section(lower);
            if (section != null) {
                boolean isStyle = isStyleTag(lower);
                if (isStyle) activeStyles.push(lower);
                out.append(section);
                i = close + 1;
                continue;
            }
            if (logUnknownTags) {
                // Unknown tags are preserved so scripts are never broken by a typo here.
            }
            out.append(input, i, close + 1);
            i = close + 1;
        }
        return out.toString();
    }

    private static boolean isStyleTag(String lower) {
        return NAMED.containsKey(lower) && "\u00a7l\u00a7o\u00a7n\u00a7m\u00a7k".contains(NAMED.get(lower));
    }

    private static String section(String tag) {
        if (tag.startsWith("#")) return hexToSection(tag);
        if (tag.startsWith("color:")) return hexToSection(tag.substring("color:".length()));
        String named = NAMED.get(tag);
        if (named != null) return named;
        if (tag.length() == 7 && tag.charAt(0) == '#') return hexToSection(tag);
        return null;
    }

    private static String hexToSection(String hex) {
        String value = hex.startsWith("#") ? hex.substring(1) : hex;
        if (value.length() != 6) return null;
        try {
            Integer.parseInt(value, 16);
        } catch (NumberFormatException e) {
            return null;
        }
        return "\u00a7x"
            + "\u00a7" + value.charAt(0) + "\u00a7" + value.charAt(1)
            + "\u00a7" + value.charAt(2) + "\u00a7" + value.charAt(3)
            + "\u00a7" + value.charAt(4) + "\u00a7" + value.charAt(5);
    }

    private static int findClosing(String input, int from, String name) {
        int idx = input.indexOf("</" + name + ">", from);
        return idx;
    }

    /** Interpolate two hex colours across the visible characters of a segment. */
    private String gradient(String text, String[] colors) {
        if (colors.length < 2) return legacy(text);
        int from = parseHex(colors[0]);
        int to = parseHex(colors[colors.length - 1]);
        if (from < 0 || to < 0) return legacy(text);
        String plain = text;
        int visible = Math.max(1, Strings.visibleLength(plain));
        StringBuilder out = new StringBuilder();
        int index = 0;
        for (int i = 0; i < plain.length(); i++) {
            char c = plain.charAt(i);
            out.append(c);
            if (visibleLengthChar(c)) {
                double ratio = visible == 1 ? 0 : (double) index / (visible - 1);
                out.append(sectionForHex(interpolate(from, to, ratio)));
                index++;
            }
        }
        return out.toString();
    }

    private static boolean visibleLengthChar(char c) {
        return c != '\u00a7';
    }

    private static int parseHex(String value) {
        String hex = value.startsWith("#") ? value.substring(1) : value;
        if (hex.length() != 6) return -1;
        try {
            return Integer.parseInt(hex, 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int interpolate(int from, int to, double ratio) {
        int r = (int) Math.round(((from >> 16) & 0xFF) + ((((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * ratio));
        int g = (int) Math.round(((from >> 8) & 0xFF) + ((((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * ratio));
        int b = (int) Math.round((from & 0xFF) + (((to & 0xFF) - (from & 0xFF)) * ratio));
        return (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static String sectionForHex(int rgb) {
        String hex = String.format("%06x", rgb);
        return hexToSection(hex);
    }

    /** Plain text rendering used for console output, logs and comparisons. */
    public String plain(String rendered) {
        return Strings.stripColors(rendered);
    }
}
