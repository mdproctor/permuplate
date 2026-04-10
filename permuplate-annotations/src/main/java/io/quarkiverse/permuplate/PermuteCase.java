package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Expands a switch statement in the annotated method by inserting new cases
 * for each value of an inner loop variable, producing a fully-inlined dispatch
 * without inheritance delegation.
 *
 * <p>
 * Placed on a method that contains exactly one {@code switch} statement. For each
 * generated class at arity {@code i}, the transformer inserts cases for
 * {@code k = from..to} (evaluated in the outer permutation context) before the
 * {@code default} case. The seed case (in the template source) and the
 * {@code default} case are preserved unchanged in all generated classes.
 *
 * <p>
 * All attributes are JEXL expression strings. The inner variable {@code varName}
 * is bound to each value in {@code [from, to]} when evaluating {@code index} and
 * {@code body}. The outer permutation variable (e.g. {@code i}) is also available.
 *
 * <p>
 * Example — accumulate field access cases in a {@code Tuple1} template:
 *
 * <pre>{@code
 * @PermuteCase(varName = "k", from = "1", to = "${i-1}", index = "${k}", body = "return (T) ${lower(k+1)};")
 * public <T> T get(int index) {
 *     switch (index) {
 *         case 0:
 *             return (T) a; // seed case — unchanged in all generated classes
 *         default:
 *             throw new IndexOutOfBoundsException(index);
 *     }
 * }
 * // Generated Tuple3 (i=3): cases 0, 1, 2 — no super() call, no extra stack frame
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface PermuteCase {
    /** Inner loop variable name (e.g. {@code "k"}). */
    String varName();

    /** Inclusive lower bound for the inner loop (JEXL expression, e.g. {@code "1"}). */
    String from();

    /**
     * Inclusive upper bound for the inner loop (JEXL expression, e.g. {@code "${i-1}"}).
     * When {@code from > to} after evaluation, no cases are inserted (empty range).
     */
    String to();

    /**
     * JEXL expression for the integer case label, evaluated at each {@code varName} value
     * (e.g. {@code "${k}"}). Must evaluate to a non-negative integer.
     */
    String index();

    /**
     * JEXL template for the case body statements, evaluated at each {@code varName} value
     * (e.g. {@code "return (T) ${lower(k+1)};"}). Multiple statements are allowed;
     * they are parsed as a block. Refer to {@code alpha(n)}, {@code lower(n)}, and
     * all variables from the outer permutation context.
     */
    String body();
}
