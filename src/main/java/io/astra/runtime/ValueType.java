package io.astra.runtime;

import java.util.Locale;

/**
 * The types a script value can have.
 *
 * <p>The set is deliberately small: everything in the language is a string, a number, a
 * boolean, a list, or a reference to a Minecraft object. Keeping the model tight is what
 * makes {@code /astra info}, storage coercion and natural-language compilation share one
 * type vocabulary.</p>
 */
public enum ValueType {

    /** Text. */
    STRING("text"),
    /** Whole number. */
    INT("whole number"),
    /** Decimal number. */
    DECIMAL("decimal number"),
    /** true/false. */
    BOOLEAN("boolean"),
    /** Ordered list of values. */
    LIST("list"),
    /** A player or entity uuid. */
    UUID("uuid"),
    /** A world position. */
    LOCATION("location"),
    /** A material name. */
    MATERIAL("item"),
    /** A live entity reference (never stored). */
    ENTITY("entity"),
    /** A live player reference (never stored). */
    PLAYER("player"),
    /** A world reference. */
    WORLD("world"),
    /** No value. */
    NULL("nothing");

    private final String label;

    ValueType(String label) {
        this.label = label;
    }

    /** Human readable name used by diagnostics and the docs. */
    public String label() {
        return label;
    }

    /** True for INT and DECIMAL. */
    public boolean isNumber() {
        return this == INT || this == DECIMAL;
    }

    /** True when the value can be written to storage. */
    public boolean isPersistable() {
        return this != ENTITY && this != PLAYER && this != WORLD;
    }

    /** Parse a type written in a script ("number", "text", "bool", ...). */
    public static ValueType parse(String text, ValueType fallback) {
        if (text == null) return fallback;
        return switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "string", "text", "message", "name" -> STRING;
            case "int", "integer", "whole", "whole number", "count" -> INT;
            case "number", "decimal", "double", "float", "amount" -> DECIMAL;
            case "bool", "boolean", "true", "false" -> BOOLEAN;
            case "list", "array" -> LIST;
            case "uuid", "id" -> UUID;
            case "location", "loc", "position", "pos" -> LOCATION;
            case "material", "item", "itemstack", "block" -> MATERIAL;
            case "entity", "mob" -> ENTITY;
            case "player", "target" -> PLAYER;
            case "world" -> WORLD;
            case "nothing", "null", "none" -> NULL;
            default -> fallback;
        };
    }
}
