package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Inserts one or more statements into the annotated method body per permutation.
 *
 * <p>
 * When {@code varName}, {@code from}, and {@code to} are provided, a statement is
 * inserted for each value of the inner variable in [{@code from}, {@code to}] —
 * the same inner-loop pattern as {@link PermuteCase}. When they are omitted, a
 * single statement is inserted using only the outer permutation context.
 *
 * <p>
 * Example — accumulate field assignments at the start of a constructor:
 *
 * <pre>{@code
 * @PermuteStatements(varName = "k", from = "1", to = "${i-1}", position = "first", body = "this.${lower(k)} = ${lower(k)};")
 * public Tuple1(A a) {
 *     this.a = a;
 *     this.size = 1;
 * }
 * // Tuple3: this.a=a; this.b=b; [template body: this.c=c; this.size=3;]
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface PermuteStatements {
    /** Inner loop variable name (e.g. {@code "k"}). Omit for single-statement insertion. */
    String varName() default "";

    /** Inclusive lower bound for the inner loop (JEXL expression). */
    String from() default "";

    /** Inclusive upper bound for the inner loop (JEXL expression). */
    String to() default "";

    /**
     * Where to insert: {@code "first"} inserts before all existing statements;
     * {@code "last"} appends after all existing statements.
     */
    String position();

    /**
     * JEXL template for the statement(s) to insert. Multiple statements allowed
     * (parsed as a block). Evaluated per inner-loop value or once if no loop.
     */
    String body();
}
