package io.astra.runtime.action;

import io.astra.language.docs.DocEntry;
import io.astra.language.parser.SyntaxTemplate;
import io.astra.runtime.ExecContext;
import io.astra.runtime.Arguments;

import java.util.ArrayList;
import java.util.List;

/**
 * A registered action: its syntax, its documentation and its behaviour.
 *
 * @param id            stable id used by diagnostics and the compile cache
 * @param templates     accepted syntax forms
 * @param executor      the behaviour
 * @param doc           documentation metadata
 * @param securitySensitive true when {@code security.yml} gates the action
 */
public record ActionDefinition(String id, List<SyntaxTemplate> templates, ActionExecutor executor, DocEntry doc,
                               boolean securitySensitive) {

    public ActionDefinition {
        templates = templates == null ? List.of() : List.copyOf(templates);
        if (doc == null) {
            doc = DocEntry.of(id, io.astra.language.docs.DocKind.ACTION, "action " + id).build();
        }
    }

    /** The behaviour of an action. */
    @FunctionalInterface
    public interface ActionExecutor {
        void execute(ExecContext context, Arguments arguments) throws Exception;
    }

    /** Fluent builder used by the built-in registration code. */
    public static Builder builder(String id, String description) {
        return new Builder(id, description);
    }

    /** Builds an {@link ActionDefinition}. */
    public static final class Builder {
        private final String id;
        private final String description;
        private final List<String> syntax = new ArrayList<>();
        private final List<String> examples = new ArrayList<>();
        private final List<DocEntry.Builder> arguments = new ArrayList<>();
        private ActionExecutor executor = (context, arguments) -> { };
        private boolean securitySensitive;

        private Builder(String id, String description) {
            this.id = id;
            this.description = description;
        }

        public Builder syntax(String template) {
            this.syntax.add(template);
            return this;
        }

        public Builder example(String example) {
            this.examples.add(example);
            return this;
        }

        public Builder executes(ActionExecutor executor) {
            this.executor = executor;
            return this;
        }

        public Builder security() {
            this.securitySensitive = true;
            return this;
        }

        public ActionDefinition build() {
            List<SyntaxTemplate> templates = new ArrayList<>();
            for (String form : syntax) templates.add(SyntaxTemplate.compile(form));
            DocEntry.Builder doc = DocEntry.of(id, io.astra.language.docs.DocKind.ACTION, description);
            for (String form : syntax) doc.syntax(form);
            for (String example : examples) doc.example(example);
            if (securitySensitive) doc.security();
            return new ActionDefinition(id, templates, executor, doc.build(), securitySensitive);
        }
    }
}
