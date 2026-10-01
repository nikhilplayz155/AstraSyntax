package io.astra.util;

import java.util.Locale;

/**
 * Duration parsing shared by the language ("every 10 minutes"), the configuration
 * files ("interval: 5m") and the storage autosave timer.
 *
 * <p>Accepted forms: bare numbers (ticks), {@code 10s}, {@code 5m}, {@code 1h30m},
 * {@code "10 seconds"}, {@code "1.5 hours"}.</p>
 */
public final class Durations {

    /** Ticks in one second (Minecraft runs at a fixed 20 tps). */
    public static final long TICKS_PER_SECOND = 20L;
    public static final long TICKS_PER_MINUTE = TICKS_PER_SECOND * 60L;
    public static final long TICKS_PER_HOUR = TICKS_PER_MINUTE * 60L;
    public static final long TICKS_PER_DAY = TICKS_PER_HOUR * 24L;

    private Durations() {}

    /** Thrown when a duration string cannot be understood. */
    public static final class DurationFormatException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public DurationFormatException(String message) {
            super(message);
        }
    }

    /**
     * Parse a duration into ticks.
     *
     * @throws DurationFormatException when the text is not a duration
     */
    public static long parseTicks(String text) {
        if (Strings.isBlank(text)) throw new DurationFormatException("Empty duration");
        String input = text.trim().toLowerCase(Locale.ROOT);
        long total = 0L;
        int i = 0;
        boolean matched = false;
        while (i < input.length()) {
            char c = input.charAt(i);
            if (Character.isWhitespace(c) || c == ',') { i++; continue; }
            int numberStart = i;
            while (i < input.length() && (Character.isDigit(input.charAt(i)) || input.charAt(i) == '.')) i++;
            if (numberStart == i) throw new DurationFormatException("Expected a number at '" + input.substring(i) + "'");
            double value;
            try {
                value = Double.parseDouble(input.substring(numberStart, i));
            } catch (NumberFormatException e) {
                throw new DurationFormatException("'" + input.substring(numberStart, i) + "' is not a number");
            }
            int unitStart = i;
            while (i < input.length() && !Character.isDigit(input.charAt(i))) i++;
            String unit = input.substring(unitStart, i).trim();
            total += Math.round(value * unitToTicks(unit));
            matched = true;
        }
        if (!matched) throw new DurationFormatException("'" + text + "' is not a duration");
        return total;
    }

    /** Ticks for a single unit token ("s", "second", "minute", "hours", ...). */
    public static double unitToTicks(String unit) {
        String u = unit == null ? "" : unit.trim().toLowerCase(Locale.ROOT);
        if (u.endsWith("s") && u.length() > 1 && !u.equals("ms")) u = u.substring(0, u.length() - 1);
        switch (u) {
            case "": case "tick": case "t": return 1.0;
            case "second": case "sec": case "s": return TICKS_PER_SECOND;
            case "minute": case "min": case "m": return TICKS_PER_MINUTE;
            case "hour": case "hr": case "h": return TICKS_PER_HOUR;
            case "day": case "d": return TICKS_PER_DAY;
            case "week": case "w": return TICKS_PER_DAY * 7L;
            case "millisecond": case "milli": case "ms": return TICKS_PER_SECOND / 1000.0;
            default: throw new DurationFormatException("Unknown time unit '" + unit + "'");
        }
    }

    /** True when the text can be parsed as a duration. */
    public static boolean isDuration(String text) {
        try {
            parseTicks(text);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Parse a duration, returning {@code fallbackTicks} when unparseable. */
    public static long parseTicksOrDefault(String text, long fallbackTicks) {
        try {
            return parseTicks(text);
        } catch (RuntimeException e) {
            return fallbackTicks;
        }
    }

    /** Human readable rendering of a tick count ("1m 30s"). */
    public static String format(long ticks) {
        if (ticks < 0) ticks = 0;
        if (ticks < TICKS_PER_SECOND) return ticks + "t";
        long seconds = ticks / TICKS_PER_SECOND;
        long days = seconds / 86400;
        seconds %= 86400;
        long hours = seconds / 3600;
        seconds %= 3600;
        long minutes = seconds / 60;
        seconds %= 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append('d').append(' ');
        if (hours > 0) sb.append(hours).append('h').append(' ');
        if (minutes > 0) sb.append(minutes).append('m').append(' ');
        if (seconds > 0 || sb.length() == 0) sb.append(seconds).append('s');
        return sb.toString().trim();
    }
}
