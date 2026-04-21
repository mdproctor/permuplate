package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Shorthand for extending the previous class in a generated family with a shrinking
 * alpha type-argument list.
 *
 * <p>
 * Equivalent to:
 *
 * <pre>{@code
 * &#64;PermuteExtends(className = "${familyBase}${i-1}",
 *                 typeArgs  = "typeArgList(1, i-1, 'alpha')")
 * }</pre>
 *
 * where {@code familyBase} is inferred from the template's {@code @Permute.className}
 * by taking the substring before the first {@code ${}.
 *
 * <p>
 * If {@code @PermuteExtends} is also present, that annotation takes precedence
 * and {@code @PermuteExtendsChain} is silently ignored.
 *
 * <p>
 * Suppresses the implicit extends expansion (same as explicit {@code @PermuteExtends}).
 *
 * <p>
 * Example — replace the verbose form:
 *
 * <pre>
 * {@code
 * &#64;Permute(varName="i", from="2", to="6", className="Tuple${i}", ...)
 * @PermuteExtends(className="Tuple${i-1}", typeArgs="typeArgList(1, i-1, 'alpha')")
 * public static class Tuple1<A> extends BaseTuple { ... }
 * }
 * </pre>
 *
 * With the shorthand:
 * <pre>{@code
 * &#64;Permute(varName="i", from="2", to="6", className="Tuple${i}", ...)
 * @PermuteExtendsChain
 * public static class Tuple1<A> extends BaseTuple { ... }
 * }
 * </pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface PermuteExtendsChain {
}
