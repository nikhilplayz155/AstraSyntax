package io.astra.language.ast;

/** Anything in the AST that knows where it came from. */
public interface Node {

    /** The source location of this node. */
    Span span();
}
