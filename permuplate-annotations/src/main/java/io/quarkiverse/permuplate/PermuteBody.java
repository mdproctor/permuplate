package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
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
 * Applied after {@code @PermuteStatements} in the transform pipeline.
 */
@Target({ ElementType.METHOD, ElementType.CONSTRUCTOR })
@Retention(RetentionPolicy.SOURCE)
public @interface PermuteBody {
    /** JEXL template for the complete method body including braces. */
    String body();
}
