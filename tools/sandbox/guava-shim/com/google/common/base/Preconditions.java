package com.google.common.base;

/**
 * Sandbox-only stand-in for Guava's {@code Preconditions}.
 *
 * <p>This file is <b>not</b> part of AstraSyntax and is never compiled into the plugin: it
 * exists so the offline sandbox can load Bukkit API classes (which call Guava during class
 * initialisation) without Maven Central access. Real builds resolve Guava from the
 * repository declared in {@code pom.xml} / {@code build.gradle}.</p>
 */
public final class Preconditions {

    private Preconditions() { }

    public static void checkArgument(boolean expression) {
        if (!expression) throw new IllegalArgumentException();
    }

    public static void checkArgument(boolean expression, Object errorMessage) {
        if (!expression) throw new IllegalArgumentException(String.valueOf(errorMessage));
    }

    public static void checkArgument(boolean expression, String errorMessageTemplate, Object... args) {
        if (!expression) throw new IllegalArgumentException(format(errorMessageTemplate, args));
    }

    public static <T> T checkNotNull(T reference) {
        if (reference == null) throw new NullPointerException();
        return reference;
    }

    public static <T> T checkNotNull(T reference, Object errorMessage) {
        if (reference == null) throw new NullPointerException(String.valueOf(errorMessage));
        return reference;
    }

    public static <T> T checkNotNull(T reference, String errorMessageTemplate, Object... args) {
        if (reference == null) throw new NullPointerException(format(errorMessageTemplate, args));
        return reference;
    }

    public static void checkState(boolean expression) {
        if (!expression) throw new IllegalStateException();
    }

    public static void checkState(boolean expression, Object errorMessage) {
        if (!expression) throw new IllegalStateException(String.valueOf(errorMessage));
    }

    public static void checkState(boolean expression, String errorMessageTemplate, Object... args) {
        if (!expression) throw new IllegalStateException(format(errorMessageTemplate, args));
    }

    public static int checkElementIndex(int index, int size) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index: " + index + ", size: " + size);
        }
        return index;
    }

    public static int checkPositionIndex(int index, int size) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException("index: " + index + ", size: " + size);
        }
        return index;
    }


    // --- fast-path overloads Guava declares explicitly (primitive + object templates) ---

    public static void checkArgument(boolean expression, String template, Object arg) {
        checkArgument(expression, template, new Object[] {arg});
    }

    public static void checkArgument(boolean expression, String template, char arg) {
        checkArgument(expression, template, new Object[] {arg});
    }

    public static void checkArgument(boolean expression, String template, int arg) {
        checkArgument(expression, template, new Object[] {arg});
    }

    public static void checkArgument(boolean expression, String template, long arg) {
        checkArgument(expression, template, new Object[] {arg});
    }

    public static void checkArgument(boolean expression, String template, Object arg1, Object arg2) {
        checkArgument(expression, template, new Object[] {arg1, arg2});
    }

    public static void checkArgument(boolean expression, String template, char arg1, char arg2) {
        checkArgument(expression, template, new Object[] {arg1, arg2});
    }

    public static void checkArgument(boolean expression, String template, int arg1, int arg2) {
        checkArgument(expression, template, new Object[] {arg1, arg2});
    }

    public static void checkArgument(boolean expression, String template, char arg1, int arg2) {
        checkArgument(expression, template, new Object[] {arg1, arg2});
    }

    public static void checkArgument(boolean expression, String template, char arg1, long arg2) {
        checkArgument(expression, template, new Object[] {arg1, arg2});
    }

    public static void checkArgument(boolean expression, String template, int arg1, char arg2) {
        checkArgument(expression, template, new Object[] {arg1, arg2});
    }

    public static void checkArgument(boolean expression, String template, int arg1, long arg2) {
        checkArgument(expression, template, new Object[] {arg1, arg2});
    }

    public static void checkArgument(boolean expression, String template, long arg1, char arg2) {
        checkArgument(expression, template, new Object[] {arg1, arg2});
    }

    public static void checkArgument(boolean expression, String template, long arg1, int arg2) {
        checkArgument(expression, template, new Object[] {arg1, arg2});
    }

    public static void checkArgument(boolean expression, String template, long arg1, long arg2) {
        checkArgument(expression, template, new Object[] {arg1, arg2});
    }

    public static void checkArgument(boolean expression, String template, Object arg1, Object arg2, Object arg3) {
        checkArgument(expression, template, new Object[] {arg1, arg2, arg3});
    }

    public static void checkArgument(boolean expression, String template, Object arg1, Object arg2, Object arg3,
                                     Object arg4) {
        checkArgument(expression, template, new Object[] {arg1, arg2, arg3, arg4});
    }

    public static <T> T checkNotNull(T reference, String template, Object arg) {
        if (reference == null) throw new NullPointerException(format(template, new Object[] {arg}));
        return reference;
    }

    public static <T> T checkNotNull(T reference, String template, Object arg1, Object arg2) {
        if (reference == null) throw new NullPointerException(format(template, new Object[] {arg1, arg2}));
        return reference;
    }

    public static void checkState(boolean expression, String template, Object arg) {
        if (!expression) throw new IllegalStateException(format(template, new Object[] {arg}));
    }

    public static void checkState(boolean expression, String template, int arg) {
        if (!expression) throw new IllegalStateException(format(template, new Object[] {arg}));
    }

    public static void checkState(boolean expression, String template, Object arg1, Object arg2) {
        if (!expression) throw new IllegalStateException(format(template, new Object[] {arg1, arg2}));
    }

    private static String format(String template, Object... args) {
        if (args == null || args.length == 0) return String.valueOf(template);
        String text = String.valueOf(template);
        for (Object arg : args) {
            text = text.replaceFirst("%s", java.util.regex.Matcher.quoteReplacement(String.valueOf(arg)));
        }
        return text;
    }
}
