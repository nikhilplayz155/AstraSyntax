package io.astra.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Small string helpers used across the language, runtime and diagnostics.
 *
 * <p>Deliberately dependency-free: no third-party string libs, no reflection.</p>
 */
public final class Strings {

    private Strings() {}

    /** True when the string is null or empty after trimming. */
    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** True when the string is neither null nor blank. */
    public static boolean isNotBlank(String s) {
        return !isBlank(s);
    }

    /** Null-safe trim that returns the empty string instead of null. */
    public static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    /** Capitalize the first character; empty/blank input is returned as-is. */
    public static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Lower-case the first character; empty/blank input is returned as-is. */
    public static String decapitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    /** Repeat a string n times. */
    public static String repeat(String s, int n) {
        if (n <= 0 || s == null) return "";
        StringBuilder sb = new StringBuilder(n * s.length());
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    /** Pad a string on the left to the given width. */
    public static String padLeft(String s, int width) {
        if (s == null) s = "";
        if (s.length() >= width) return s;
        return repeat(" ", width - s.length()) + s;
    }

    /** Pad a string on the right to the given width. */
    public static String padRight(String s, int width) {
        if (s == null) s = "";
        if (s.length() >= width) return s;
        return s + repeat(" ", width - s.length());
    }

    /** Join a collection with a delimiter. */
    public static String join(Collection<?> parts, String delimiter) {
        if (parts == null || parts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Object p : parts) {
            if (!first) sb.append(delimiter);
            sb.append(p);
            first = false;
        }
        return sb.toString();
    }

    /**
     * Levenshtein distance between two strings. Used for typo suggestions
     * (e.g. "dimonds" -&gt; "diamonds").
     */
    public static int levenshtein(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[b.length()];
    }

    /** Return the first candidate from {@code options} closest to {@code target}. */
    public static String closest(String target, Iterable<String> options) {
        return closest(target, options, Integer.MAX_VALUE);
    }

    /**
     * Closest candidate within an adaptive tolerance; returns {@code null} when
     * nothing is close enough (prevents absurd suggestions).
     */
    public static String closest(String target, Iterable<String> options, int maxDistance) {
        if (target == null) return null;
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        String lower = target.toLowerCase(Locale.ROOT);
        for (String o : options) {
            if (o == null) continue;
            int d = levenshtein(lower, o.toLowerCase(Locale.ROOT));
            if (d < bestDist) {
                bestDist = d;
                best = o;
            }
        }
        int tolerance = Math.min(maxDistance, Math.max(1, lower.length() / 3 + 1));
        return bestDist <= tolerance ? best : null;
    }

    /** Up to {@code limit} candidates ordered by similarity to {@code target}. */
    public static List<String> nearest(String target, Iterable<String> options, int limit) {
        List<String> pool = new ArrayList<>();
        for (String o : options) if (o != null) pool.add(o);
        String lower = target == null ? "" : target.toLowerCase(Locale.ROOT);
        pool.sort((a, b) -> Integer.compare(
            levenshtein(lower, a.toLowerCase(Locale.ROOT)),
            levenshtein(lower, b.toLowerCase(Locale.ROOT))));
        return pool.size() > limit ? new ArrayList<>(pool.subList(0, limit)) : pool;
    }

    /** Split a string on a delimiter, preserving empty tokens. */
    public static List<String> split(String s, char delim) {
        List<String> out = new ArrayList<>();
        if (s == null) {
            out.add("");
            return out;
        }
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == delim) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }

    /** Strip surrounding double or single quotes if present. */
    public static String unquote(String s) {
        if (s == null) return null;
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    /** Legacy Minecraft color codes (section sign and ampersand variants). */
    public static String stripColors(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c == '\u00a7' || c == '&') && i + 1 < s.length()
                && "0123456789abcdefklmnorxABCDEFKLMNORX".indexOf(s.charAt(i + 1)) >= 0) {
                i++;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** True when the text contains a legacy color or formatting code. */
    public static boolean hasColors(String s) {
        return s != null && !s.equals(stripColors(s));
    }

    /** Number of visible (color-stripped) characters. */
    public static int visibleLength(String s) {
        String stripped = stripColors(s);
        return stripped == null ? 0 : stripped.length();
    }

    /** True when {@code s} parses as an integer. */
    public static boolean isInteger(String s) {
        if (isBlank(s)) return false;
        try {
            Long.parseLong(s.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** True when {@code s} parses as a decimal number. */
    public static boolean isDecimal(String s) {
        if (isBlank(s)) return false;
        try {
            Double.parseDouble(s.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** A quoted, escaped single-line rendering of a string for diagnostics. */
    public static String quote(String s) {
        if (s == null) return "\"\"";
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"")
                       .replace("\n", "\\n").replace("\t", "\\t") + '"';
    }

    /** Truncate to {@code max} characters, appending an ellipsis when cut. */
    public static String truncate(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        if (max <= 3) return s.substring(0, Math.max(0, max));
        return s.substring(0, max - 3) + "...";
    }
}
