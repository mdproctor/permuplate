package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Replaces the right-hand side of an assignment or the initializer of a field /
 * local variable with a JEXL-evaluated expression per permutation.
 *
 * <p>
 * On a <b>field or local variable declaration</b>: replaces the initializer.
 * No {@code index} needed — the declaration itself is the target. This is a
 * superset of {@link PermuteConst}.
 *
 * <p>
 * On a <b>method</b>: replaces the RHS of the assignment statement at position
 * {@code index} in the original template method body (0-based, before any
 * {@link PermuteStatements} insertions).
 *
 * <p>
 * Example — on a field:
 *
 * <pre>{@code
 * @PermuteValue("${i}")
 * int ARITY = 2;
 * // Generated at i=4: int ARITY = 4;
 * }</pre>
 *
 * <p>
 * Example — on a method, targeting statement 1:
 *
 * <pre>{@code
 * @PermuteValue(index = 1, value = "${i}")
 * public void init() {
 *     this.name = "x"; // statement 0 — untouched
 *     this.size = 1; // statement 1 — RHS "1" becomes ${i}
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ ElementType.FIELD, ElementType.LOCAL_VARIABLE, ElementType.METHOD })
public @interface PermuteValue {
    /**
     * JEXL expression for the replacement value (e.g. {@code "${i}"},
     * {@code "${i * 2}"}). Integer results become {@code IntegerLiteralExpr};
     * all others become {@code StringLiteralExpr}.
     */
    String value() default "";

    /**
     * 0-based index of the statement in the method body whose RHS to replace.
     * Only used when the annotation is placed on a method. Ignored on field
     * and local variable declarations.
     */
    int index() default -1;
}
