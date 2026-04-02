package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Explicit control over extends/implements clause expansion for a generated class.
 *
 * <p>
 * In the common case (same {@code T${j}} naming, type args matching the class's
 * declared type params in order), extends/implements expansion is automatic — no
 * annotation required.
 *
 * <p>
 * Use {@code @PermuteExtends} only when implicit inference does not apply:
 * non-standard naming, a subset of type args in the extends clause, or when
 * targeting an implements interface rather than the extends clause.
 *
 * <p>
 * Example:
 *
 * <pre>{@code
 * &#64;PermuteExtends(className="Join${i}Second",
 *                 typeArgVarName="k", typeArgFrom="1", typeArgTo="${i}", typeArgName="T${k}")
 * public class Join1First<T1> extends Join1Second<T1> { ... }
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface PermuteExtends {
    /** Template expression for the extends/implements class name. */
    String className();

    /** Variable name for the type argument expansion loop. */
    String typeArgVarName() default "";

    /** Lower bound of the type argument loop. Default {@code "1"}. */
    String typeArgFrom() default "1";

    /** Upper bound of the type argument loop. */
    String typeArgTo() default "";

    /** Type argument name template per loop value. */
    String typeArgName() default "";

    /** Full type argument list JEXL expression (alternative to the loop mechanism). */
    String typeArgs() default "";

    /**
     * 0 (default) targets the {@code extends} clause.
     * 1+ targets the nth {@code implements} interface (0-indexed among implements).
     */
    int interfaceIndex() default 0;
}
