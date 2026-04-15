package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Skips generation of a permutation when the JEXL expression evaluates to {@code false}.
 *
 * <p>
 * Placed on a {@code @Permute}-annotated class or method. Evaluated once per
 * permutation combination (after cross-product expansion); the combination is skipped
 * if any filter returns {@code false}.
 *
 * <p>
 * Repeatable — multiple {@code @PermuteFilter} conditions are ANDed: a combination
 * must pass all filters to be generated.
 *
 * <p>
 * Example — skip arity 1 because it is hand-written elsewhere:
 *
 * <pre>{@code
 * &#64;Permute(varName="i", from="1", to="6", className="Tuple${i}")
 * &#64;PermuteFilter("${i} != 1")
 * public class Tuple2 { ... }
 * }</pre>
 *
 * <p>
 * The APT processor reports a compile error if all values in the range are filtered out.
 */
@Repeatable(PermuteFilters.class)
@Retention(RetentionPolicy.SOURCE)
@Target({ ElementType.TYPE, ElementType.METHOD })
public @interface PermuteFilter {
    /**
     * JEXL boolean expression. The combination is skipped when this evaluates to {@code false}.
     * May reference all loop variables (e.g. {@code "${i} != 1"}, {@code "${i} > 2 && ${j} != i"}).
     */
    String value();
}
