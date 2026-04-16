package io.quarkiverse.permuplate;

import java.lang.annotation.*;

/**
 * Adds an exception type to a method's {@code throws} clause per permutation.
 * {@code value} is a JEXL-evaluated exception class name.
 * {@code when} is an optional JEXL boolean — empty string means always add.
 *
 * <p>
 * Example:
 *
 * <pre>{@code
 * &#64;PermuteThrows(when="${i > 4}", value="TooManyArgsException")
 * public void join(...) throws SomeException { ... }
 * }</pre>
 */
@Repeatable(PermuteThrowsList.class)
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface PermuteThrows {
    /** JEXL-evaluated exception class name to add to the throws clause. */
    String value();

    /** JEXL boolean condition. Empty string means always add. */
    String when() default "";
}
