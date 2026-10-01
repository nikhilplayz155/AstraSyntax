package io.astra.language.docs;

import java.util.List;

/**
 * Machine readable documentation for a language element.
 *
 * <p>The same metadata feeds {@code /astra explain}, the generated documentation and -
 * later - the Astra Studio editor (hover docs, completion, signature help). It is
 * deliberately declarative so a VS Code extension can consume it without executing any
 * game code.</p>
 *
 * @param id          stable identifier ({@code player-join}, {@code give-item})
 * @param kind        what kind of element this is
 * @param name        human readable name
 * @param description what it does
 * @param syntax      accepted syntax forms
 * @param arguments   argument documentation
 * @param returnType  result type for expressions and conditions
 * @param examples    short examples
 * @param since       version the element was introduced in
 * @param securitySensitive true when the element is gated by {@code security.yml}
 */
public record DocEntry(String id, DocKind kind, String name, String description, List<String> syntax,
                       List<DocArgument> arguments, String returnType, List<String> examples, String since,
                       boolean securitySensitive) {

    public DocEntry {
        syntax = syntax == null ? List.of() : List.copyOf(syntax);
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
        examples = examples == null ? List.of() : List.copyOf(examples);
    }

    /** Fluent builder used by the registries. */
    public static Builder of(String id, DocKind kind, String description) {
        return new Builder(id, kind, description);
    }

    /** Mutable builder; produced entries are immutable. */
    public static final class Builder {
        private final String id;
        private final DocKind kind;
        private final String description;
        private String name;
        private final java.util.List<String> syntax = new java.util.ArrayList<>();
        private final java.util.List<DocArgument> arguments = new java.util.ArrayList<>();
        private String returnType = "void";
        private final java.util.List<String> examples = new java.util.ArrayList<>();
        private String since = "1.0";
        private boolean securitySensitive;

        private Builder(String id, DocKind kind, String description) {
            this.id = id;
            this.kind = kind;
            this.description = description;
            this.name = id;
        }

        public Builder name(String value) {
            this.name = value;
            return this;
        }

        public Builder syntax(String value) {
            this.syntax.add(value);
            return this;
        }

        public Builder argument(String argumentName, String type, String argumentDescription) {
            this.arguments.add(new DocArgument(argumentName, type, argumentDescription, false));
            return this;
        }

        public Builder optionalArgument(String argumentName, String type, String argumentDescription) {
            this.arguments.add(new DocArgument(argumentName, type, argumentDescription, true));
            return this;
        }

        public Builder returns(String type) {
            this.returnType = type;
            return this;
        }

        public Builder example(String value) {
            this.examples.add(value);
            return this;
        }

        public Builder since(String version) {
            this.since = version;
            return this;
        }

        public Builder security() {
            this.securitySensitive = true;
            return this;
        }

        public DocEntry build() {
            return new DocEntry(id, kind, name, description, syntax, arguments, returnType, examples, since,
                securitySensitive);
        }
    }
}
