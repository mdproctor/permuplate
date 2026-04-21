package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Renames the constructor type in a {@code new X<>(...)} expression per permutation.
 * Applied as a TYPE_USE annotation directly on the constructed type.
 *
 * <p>
 * This is the explicit alternative to constructor-coherence inference (which fires
 * automatically when the family of the constructor type matches {@code @PermuteReturn}).
 * Use {@code @PermuteNew} when a method body contains multiple constructed types from
 * different generated families, or when explicit control over the rename is needed.
 *
 * <p>
 * {@code className} is a JEXL expression evaluated with the same loop variables in
 * scope as {@code @PermuteReturn}: {@code i}, macros, strings, etc.
 *
 * <p>
 * Example:
 *
 * <pre>{@code
 * &#64;PermuteReturn(className = "Join${i+1}First")
 * public Object join(...) {
 *     return cast(new @PermuteNew(className = "Join${i+1}First") Join1First<>(end(), rd));
 * }
 * }</pre>
 *
 * <p>
 * <b>Limitation:</b> If {@code @PermuteNew} is used inside a method annotated with
 * {@code @PermuteMethod}, the {@code className} expression is evaluated with the outer
 * loop variable ({@code i}) in scope but NOT the inner loop variable ({@code j} or
 * equivalent). Use {@code @PermuteReturn} instead for methods that need the inner
 * variable in the constructor type expression.
 *
 * <p>
 * <b>Maven plugin only.</b> Silently ignored in APT mode.
 */
@Target(ElementType.TYPE_USE)
@Retention(RetentionPolicy.SOURCE)
public @interface PermuteNew {
    /** JEXL expression evaluating to the constructor's target type name. */
    String className();
}
