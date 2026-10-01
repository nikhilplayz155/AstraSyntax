package io.astra.language.parser;

import java.util.Locale;

/**
 * The kinds of value a syntax slot can capture.
 *
 * <p>Slots are what make the language data-driven: an action or condition declares its
 * own syntax with slots, so adding an action automatically teaches the parser how to
 * read it - no parser changes, which is also what lets modules extend the vocabulary.</p>
 */
public enum SlotType {

    /** A player/entity reference ({@code player}, {@code everyone}, {@code attacker}). */
    TARGET,
    /** Free text to the end of the statement (quoted or bare). */
    TEXT,
    /** A general expression to the end of the statement. */
    EXPR,
    /** One word or quoted string. */
    STRING,
    /** A whole number. */
    NUMBER,
    /** A whole or decimal number. */
    DECIMAL,
    /** A Minecraft material name, optionally preceded by an amount. */
    ITEM,
    /** A Minecraft material name only. */
    MATERIAL,
    /** An entity type name. */
    ENTITY,
    /** A world name. */
    WORLD,
    /** A single keyword (sound, effect, property, key names). */
    WORD,
    /** A duration such as {@code 10 minutes} or {@code 30s}. */
    DURATION,
    /** A location: an expression, a reference or {@code world x y z}. */
    LOCATION,
    /** The name of a declared data key. */
    DATA_KEY,
    /** A permission node, quoted or bare. */
    PERMISSION,
    /** true/false. */
    BOOLEAN,
    /** A comma separated list of expressions. */
    LIST;

    /** Parse a slot type from a template string, defaulting to {@link #STRING}. */
    public static SlotType parse(String raw) {
        if (raw == null) return STRING;
        String text = raw.trim().toLowerCase(Locale.ROOT);
        return switch (text) {
            case "target", "player", "entity-target" -> TARGET;
            case "text", "message" -> TEXT;
            case "expr", "expression", "value" -> EXPR;
            case "number", "int", "integer" -> NUMBER;
            case "decimal", "double", "float" -> DECIMAL;
            case "item", "itemstack" -> ITEM;
            case "material", "block", "item-type" -> MATERIAL;
            case "entity", "entitytype", "mob" -> ENTITY;
            case "world" -> WORLD;
            case "duration", "time" -> DURATION;
            case "location", "loc", "position" -> LOCATION;
            case "data-key", "data", "key", "variable" -> DATA_KEY;
            case "permission", "perm" -> PERMISSION;
            case "boolean", "bool" -> BOOLEAN;
            case "list", "array" -> LIST;
            default -> WORD;
        };
    }
}
