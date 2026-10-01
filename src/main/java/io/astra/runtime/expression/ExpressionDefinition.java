package io.astra.runtime.expression;

import io.astra.language.docs.DocEntry;
import io.astra.language.docs.DocKind;
import io.astra.language.parser.SyntaxTemplate;
import io.astra.runtime.Arguments;
import io.astra.runtime.ExecContext;
import io.astra.runtime.Value;

import java.util.ArrayList;
import java.util.List;

/**
 * A registered expression provider.
 *
 * <p>Expressions such as {@code random 1 to 5}, {@code config "starter-coins"} or
 * {@code count of players} are registered like actions and conditions, which keeps the
 * expression vocabulary extensible for modules.</p>
 */
public record ExpressionDefinition(String id, List<SyntaxTemplate> templates, Evaluator evaluator, DocEntry doc) {

    public ExpressionDefinition {
        templates = templates == null ? List.of() : List.copyOf(templates);
        if (doc == null) doc = DocEntry.of(id, DocKind.EXPRESSION, "expression " + id).build();
    }

    /** Evaluates the expression. */
    @FunctionalInterface
    public interface Evaluator {
        Value evaluate(ExecContext context, Arguments arguments) throws Exception;
    }

    /** Fluent builder used by the built-in registration code. */
    public static Builder builder(String id, String description) {
        return new Builder(id, description);
    }

    /** Builds an {@link ExpressionDefinition}. */
    public static final class Builder {
        private final String id;
        private final String description;
        private final List<String> syntax = new ArrayList<>();
        private final List<String> examples = new ArrayList<>();
        private String returnType = "value";
        private Evaluator evaluator = (context, arguments) -> Value.NULL;

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

        public Builder returns(String type) {
            this.returnType = type;
            return this;
        }

        public Builder evaluates(Evaluator evaluator) {
            this.evaluator = evaluator;
            return this;
        }

        public ExpressionDefinition build() {
            List<SyntaxTemplate> templates = new ArrayList<>();
            for (String form : syntax) templates.add(SyntaxTemplate.compile(form));
            DocEntry.Builder doc = DocEntry.of(id, DocKind.EXPRESSION, description).returns(returnType);
            for (String form : syntax) doc.syntax(form);
            for (String example : examples) doc.example(example);
            return new ExpressionDefinition(id, templates, evaluator, doc.build());
        }
    }
}
