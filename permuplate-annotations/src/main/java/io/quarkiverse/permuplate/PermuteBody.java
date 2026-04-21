package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Replaces the annotated method or constructor body entirely with the evaluated {@code body}
 * template for each permutation.
 *
 * <p>
 * The {@code body} must be a valid Java block statement including surrounding braces,
 * e.g. {@code "{ return ${i}; }"}. JEXL placeholders ({@code ${...}}) are evaluated
 * before reparsing.
 *
 * <p>
 * When multiple {@code @PermuteBody} annotations are present (repeatable), the first one
 * whose {@code when=} condition evaluates to {@code true} is applied. The {@code when=}
 * attribute is a JEXL boolean expression (without surrounding {@code ${...}}).
 *
 * <p>
 * Applied after {@code @PermuteStatements} in the transform pipeline.
 */
@Repeatable(PermuteBodies.class)
@Target({ ElementType.METHOD, ElementType.CONSTRUCTOR })
@Retention(RetentionPolicy.SOURCE)
public @interface PermuteBody {
    /** JEXL template for the complete method body including braces. */
    String body();

    /**
     * Optional JEXL guard expression (without surrounding {@code ${...}}).
     * When empty (default), the body always applies. When non-empty, the body
     * is only applied when this expression evaluates to {@code true}.
     * With multiple {@code @PermuteBody} annotations, the first matching one wins.
     */
    String when() default "";
}
