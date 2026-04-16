package io.quarkiverse.permuplate;

import java.lang.annotation.*;

/**
 * Declares a dependency on another generated class family. Maven plugin only —
 * the source template generates before this template, and its generated classes
 * are available for type parameter inference.
 *
 * <p>
 * Example — TimedCallable${i} derives from Callable${i}:
 *
 * <pre>{@code
 * @Permute(varName = "i", from = "2", to = "6", className = "TimedCallable${i}", inline = true)
 * @PermuteSource("Callable${i}")
 * public class TimedCallable2 implements Callable2<A, B, R> {
 *     // A, B, R inferred from Callable2 — no @PermuteTypeParam needed
 * }
 * }</pre>
 */
@Repeatable(PermuteSources.class)
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface PermuteSource {
    /**
     * JEXL-evaluated name of the source generated class per permutation.
     * E.g. {@code "Callable${i}"} — resolved to "Callable3" when i=3.
     */
    String value();
}
