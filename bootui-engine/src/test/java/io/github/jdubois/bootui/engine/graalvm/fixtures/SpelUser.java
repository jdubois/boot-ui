package io.github.jdubois.bootui.engine.graalvm.fixtures;

import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;

/** Triggers GRAAL-SPEL-001 by parsing a SpEL expression at run time. */
public class SpelUser {

    private final ExpressionParser parser = new SpelExpressionParser();

    public Object evaluate(String expression, Object root) {
        return parser.parseExpression(expression).getValue(root);
    }
}
