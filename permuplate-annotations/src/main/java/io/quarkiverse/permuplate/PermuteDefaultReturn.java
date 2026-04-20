package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Sets a default return type for all {@code Object}-returning methods in this class
 * that do not have an explicit {@code @PermuteReturn} annotation.
 *
 * <p>
 * Eliminates repetitive {@code @PermuteReturn} when most methods in a generated
 * class share the same return type (common in fluent builder patterns).
 * Individual methods override this default with explicit {@code @PermuteReturn}.
 *
 * <p>
 * Example:
 *
 * <pre>
 * {@code
 * &#64;PermuteDefaultReturn(className = "Builder${i}",
 *                       typeArgs  = "'<A, B>'")
 * &#64;Permute(varName="i", from="2", to="4", className="Builder${i}")
 * public class Builder2<A, B> {
 *     public Object step1() { return this; }  // returns Builder2<A,B>, Builder3<A,B,C>...
 *     public Object step2() { return this; }  // same
 *     // Override for a terminal method:
 *     &#64;PermuteReturn(className="Result", typeArgs="", alwaysEmit=true)
 *     public Object build() { ... }
 * }
 * }
 * </pre>
 *
 * <p>
 * The default is applied AFTER all explicit {@code @PermuteReturn} annotations are
 * processed, so explicit annotations always take precedence. When {@code alwaysEmit}
 * is {@code false} and the evaluated {@code className} is not in the generated set,
 * methods receiving the default are silently omitted (same boundary omission as
 * explicit {@code @PermuteReturn}).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface PermuteDefaultReturn {
    /** JEXL template for the return class name (e.g. {@code "Builder${i}"}). */
    String className();

    /**
     * Full type argument list as a JEXL expression (e.g. {@code "'<A, B>'"}),
     * or empty string for no type arguments. Same semantics as
     * {@link PermuteReturn#typeArgs()}.
     */
    String typeArgs() default "";

    /**
     * When {@code true} (default), methods receiving the default return type are
     * always generated regardless of boundary omission. Set to {@code false} to
     * apply normal boundary omission.
     */
    boolean alwaysEmit() default true;
}
