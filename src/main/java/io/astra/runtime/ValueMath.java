package io.astra.runtime;

import java.util.Locale;

/**
 * Arithmetic and comparison for script values.
 *
 * <p>All numeric coercion lives here so two rules can never disagree about what
 * {@code "5" + 1} means. The rules are:</p>
 * <ul>
 *   <li>{@code +} concatenates as soon as either side is text, otherwise adds numbers</li>
 *   <li>division and modulo by zero yield {@code 0} instead of throwing</li>
 *   <li>text times a number repeats the text (a small convenience used by separators)</li>
 *   <li>equality is numeric when both sides look numeric, otherwise case-insensitive text</li>
 * </ul>
 */
public final class ValueMath {

    private ValueMath() {
    }

    public static Value add(Value left, Value right) {
        if (left == null || left.isNull()) return right == null ? Value.NULL : right;
        if (right == null || right.isNull()) return left;
        if (left instanceof Value.ListV list && right instanceof Value.ListV other) {
            java.util.List<Value> combined = new java.util.ArrayList<>(list.values());
            combined.addAll(other.values());
            return Value.list(combined);
        }
        if (!left.isNumber() || !right.isNumber()) {
            return Value.str(left.asString() + right.asString());
        }
        if (left.type() == ValueType.INT && right.type() == ValueType.INT) {
            return Value.num(left.asLong() + right.asLong());
        }
        return Value.dec(left.asDouble() + right.asDouble());
    }

    public static Value subtract(Value left, Value right) {
        double a = number(left);
        double b = number(right);
        if (isInt(left) && isInt(right)) return Value.num((long) a - (long) b);
        return Value.dec(a - b);
    }

    public static Value multiply(Value left, Value right) {
        if (left != null && right != null) {
            if (left.type() == ValueType.STRING && right.isNumber()) {
                return Value.str(left.asString().repeat((int) Math.max(0, Math.min(1000, right.asLong()))));
            }
            if (right.type() == ValueType.STRING && left.isNumber()) {
                return Value.str(right.asString().repeat((int) Math.max(0, Math.min(1000, left.asLong()))));
            }
        }
        double a = number(left);
        double b = number(right);
        if (isInt(left) && isInt(right)) return Value.num((long) a * (long) b);
        return Value.dec(a * b);
    }

    public static Value divide(Value left, Value right) {
        double a = number(left);
        double b = number(right);
        if (b == 0d) return isInt(left) && isInt(right) ? Value.num(0L) : Value.dec(0d);
        if (isInt(left) && isInt(right) && a % b == 0d) return Value.num((long) (a / b));
        return Value.dec(a / b);
    }

    public static Value modulo(Value left, Value right) {
        double a = number(left);
        double b = number(right);
        if (b == 0d) return Value.num(0L);
        if (isInt(left) && isInt(right)) return Value.num((long) a % (long) b);
        return Value.dec(a % b);
    }

    public static Value negate(Value value) {
        if (value != null && value.type() == ValueType.INT) return Value.num(-value.asLong());
        return Value.dec(-number(value));
    }

    /** A three-way comparison: negative, zero or positive. */
    public static int compare(Value left, Value right) {
        if (left == null || right == null || left.isNull() || right.isNull()) {
            if (left == null || left.isNull()) return right == null || right.isNull() ? 0 : -1;
            return 1;
        }
        if (left.isNumber() && right.isNumber()) return Double.compare(left.asDouble(), right.asDouble());
        if (left.type() == ValueType.BOOLEAN || right.type() == ValueType.BOOLEAN) {
            return Boolean.compare(left.asBoolean(), right.asBoolean());
        }
        return left.asString().compareToIgnoreCase(right.asString());
    }

    /** Equality that treats {@code 5} and {@code "5"} as equal and ignores case in text. */
    public static boolean equal(Value left, Value right) {
        if (left == null || right == null || left.isNull() || right.isNull()) {
            return (left == null || left.isNull()) && (right == null || right.isNull());
        }
        if (left.isNumber() && right.isNumber()) return left.asDouble() == right.asDouble();
        String a = left.asString().trim();
        String b = right.asString().trim();
        if (looksNumeric(a) && looksNumeric(b)) {
            try {
                return Double.parseDouble(a) == Double.parseDouble(b);
            } catch (NumberFormatException ignored) {
                // fall through to text comparison
            }
        }
        return a.equalsIgnoreCase(b);
    }

    /** True when the value is text that should behave as a number. */
    public static boolean isNumeric(Value value) {
        return value != null && (value.isNumber() || looksNumeric(value.asString()));
    }

    public static double number(Value value) {
        return value == null ? 0d : value.asDouble();
    }

    private static boolean isInt(Value value) {
        if (value == null) return true;
        if (value.type() == ValueType.INT) return true;
        if (value.type() == ValueType.DECIMAL) return false;
        if (value.type() == ValueType.STRING) {
            String text = value.asString().trim().toLowerCase(Locale.ROOT);
            return !text.contains(".");
        }
        return false;
    }

    private static boolean looksNumeric(String text) {
        if (text == null || text.isEmpty()) return false;
        char first = text.charAt(0);
        if (first != '-' && first != '+' && (first < '0' || first > '9')) return false;
        try {
            Double.parseDouble(text);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
