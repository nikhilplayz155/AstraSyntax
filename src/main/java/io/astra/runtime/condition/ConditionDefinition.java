package io.astra.runtime.condition;

import io.astra.language.docs.DocEntry;
import io.astra.language.docs.DocKind;
import io.astra.language.parser.SyntaxTemplate;
import io.astra.runtime.Arguments;
import io.astra.runtime.ExecContext;

import java.util.ArrayList;
import java.util.List;

/**
 * A registered condition: syntax plus a predicate.
 *
 * @param id        stable id
 * @param templates accepted syntax forms
 * @param tester    the predicate
 * @param doc       documentation metadata
 */
public record ConditionDefinition(String id, List<SyntaxTemplate> templates, ConditionTester tester, DocEntry doc) {

    public ConditionDefinition {
        templates = templates == null ? List.of() : List.copyOf(templates);
        if (doc == null) doc = DocEntry.of(id, DocKind.CONDITION, "condition " + id).build();
    }

    /** The predicate of a condition. */
    @FunctionalInterface
    public interface ConditionTester {
        boolean test(ExecContext context, Arguments arguments) throws Exception;
    }

    /** Fluent builder used by the built-in registration code. */
    public static Builder builder(String id, String description) {
        return new Builder(id, description);
    }

    /** Builds a {@link ConditionDefinition}. */
    public static final class Builder {
        private final String id;
        private final String description;
        private final List<String> syntax = new ArrayList<>();
        private final List<String> examples = new ArrayList<>();
        private ConditionTester tester = (context, arguments) -> true;

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

        public Builder tests(ConditionTester tester) {
            this.tester = tester;
            return this;
        }

        public ConditionDefinition build() {
            List<SyntaxTemplate> templates = new ArrayList<>();
            for (String form : syntax) templates.add(SyntaxTemplate.compile(form));
            DocEntry.Builder doc = DocEntry.of(id, DocKind.CONDITION, description).returns("boolean");
            for (String form : syntax) doc.syntax(form);
            for (String example : examples) doc.example(example);
            return new ConditionDefinition(id, templates, tester, doc.build());
        }
    }
}
