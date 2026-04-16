package io.quarkiverse.permuplate;

import java.lang.annotation.*;

/**
 * Synthesises delegating method bodies from the source interface/class
 * declared by {@link PermuteSource}. Place on a field whose type is
 * the source generated class.
 *
 * <p>
 * All methods from the source that are not explicitly declared in
 * this template are generated as delegating calls.
 *
 * <p>
 * Example:
 *
 * <pre>
 * {
 *     &#64;code
 *     &#64;PermuteSource("Callable${i}")
 *     public class SynchronizedCallable2 implements Callable2<A, B, R> {
 *         @PermuteDelegate(modifier = "synchronized")
 *         private final Callable2<A, B, R> delegate;
 *     }
 * }
 * </pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.FIELD)
public @interface PermuteDelegate {
    /**
     * Optional Java modifier to add to synthesised methods.
     * E.g. {@code "synchronized"}.
     */
    String modifier() default "";
}
