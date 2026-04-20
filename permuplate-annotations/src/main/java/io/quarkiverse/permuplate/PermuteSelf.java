package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as returning the current generated class with all its type parameters.
 *
 * <p>
 * Eliminates verbose {@code @PermuteReturn} on fluent builder methods that return
 * {@code this}. The return type is automatically set to {@code ClassName<T1, T2, ...>}
 * where {@code ClassName} is the generated class name and {@code T1, T2, ...} are its
 * current type parameters (already expanded by {@code @PermuteTypeParam}).
 *
 * <p>
 * Example:
 *
 * <pre>{@code
 * @PermuteSelf
 * public Object index() {
 *     return this;
 * }
 * // Generated Join3First: public Join3First<END, DS, A, B, C> index() { return this; }
 * }</pre>
 *
 * <p>
 * Applied in the pipeline after {@link PermuteTypeParam} expansion, so type parameters
 * are already in their final expanded form when {@code @PermuteSelf} fires.
 *
 * <p>
 * <b>Interaction with {@code @PermuteMethod}:</b> When placed on a method that also
 * carries {@code @PermuteMethod}, {@code @PermuteSelf} fires before cloning. All clones
 * inherit the outer class's return type. Only safe when the inner loop variable does not
 * affect which class should be returned.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface PermuteSelf {
}
