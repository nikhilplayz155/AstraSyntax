package io.astra.language.docs;

/**
 * Documentation for a single argument.
 *
 * @param name        argument name as used in syntax templates
 * @param type        slot type ({@code target}, {@code item}, ...)
 * @param description what the argument accepts
 * @param optional    true when the argument may be omitted
 */
public record DocArgument(String name, String type, String description, boolean optional) { }
