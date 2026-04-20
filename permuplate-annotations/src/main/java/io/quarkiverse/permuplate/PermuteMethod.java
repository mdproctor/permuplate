package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generates multiple overloads of a sentinel method, one per inner loop value.
 *
 * <p>
 * For outer permutation value {@code i} and inner value {@code j} (from {@code from}
 * to {@code to} inclusive), one method overload is generated. When {@code from > to},
 * no overloads are generated — this is the leaf-node mechanism (e.g., {@code Join5Second}
 * has no {@code join()} methods when i=max).
 *
 * <p>
 * <b>{@code to} inference:</b> when omitted, inferred as {@code @Permute.to - i}.
 * Works in both APT and Maven plugin modes for same-module class families.
 * For cross-module APT dependencies, set {@code to} explicitly with
 * {@code strings={"max=N"}} on the enclosing {@code @Permute}.
 *
 * <p>
 * In Maven plugin inline mode with {@code T${j}} naming, {@link PermuteReturn} and
 * {@link PermuteDeclr} on parameters are inferred automatically — only
 * {@code @PermuteMethod} is required. In APT mode, use explicit annotations.
 *
 * <p>
 * Example (inline mode, minimal — {@code to} inferred):
 *
 * <pre>{@code
 * &#64;PermuteMethod(varName="j")
 * public Join2First<T1, T2> join(Join1First<T2> fromJ) { ... }
 * }</pre>
 *
 * <p>
 * Example (explicit, APT mode or alpha naming):
 *
 * <pre>
 * {@code
 * &#64;PermuteMethod(varName="j", from="1", to="${max - i}")
 * &#64;PermuteReturn(className="Join${i+j}First", ...)
 * public Object join(@PermuteDeclr(type="Join${j}First<...>") Object fromJ) { ... }
 * }
 * </pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface PermuteMethod {
    /** Inner loop variable name (e.g., {@code "j"}). */
    String varName();

    /** Inner loop lower bound — literal or expression. Defaults to {@code "1"}. */
    String from() default "1";

    /**
     * Inner loop upper bound. When empty (default), inferred as {@code @Permute.to - i}.
     * Set explicitly for non-linear bounds or cross-module APT dependencies.
     */
    String to() default "";

    /**
     * Optional method name template (e.g., {@code "path${k}"}). When set, each generated
     * overload gets a distinct name. When empty (default), all overloads share the
     * sentinel method's name — the standard pattern for {@code join()} overloads.
     */
    String name() default "";

    /**
     * String values to iterate over instead of an integer range.
     * Mutually exclusive with {@link #from()} and {@link #to()}.
     * When non-empty, each value is bound as a String to {@link #varName()},
     * and one method overload is generated per value.
     *
     * <p>
     * Example:
     *
     * <pre>{@code
     * &#64;PermuteMethod(varName="T", values={"Sync","Async"}, name="${capitalize(T)}Execute")
     * public Object executeTemplate() { ... }
     * // Generates: SyncExecute() and AsyncExecute()
     * }</pre>
     */
    String[] values() default {};
}
