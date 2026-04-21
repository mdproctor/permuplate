package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Auto-generates a sealed marker interface for the generated class family and adds
 * the matching {@code implements} clause to each generated class.
 *
 * <p>
 * The interface is added as a member of the enclosing type (for nested templates)
 * or as a top-level type in the compilation unit (for top-level templates). Each
 * generated class receives {@code implements InterfaceName<TypeParams>} automatically.
 *
 * <p>
 * The generated classes must already be declared {@code non-sealed} or {@code final}
 * in the template — add the modifier to the template class declaration if needed.
 *
 * <p>
 * {@code typeParams} is used verbatim in both the interface declaration
 * ({@code sealed interface Name<typeParams>}) and the implements clause
 * ({@code implements Name<typeParams>}) on each generated class.
 *
 * <p>
 * Example — replaces this in the outer class:
 *
 * <pre>{@code
 * public sealed interface JoinBuilderSecond<END, DS>
 *         permits Join1Second, Join2Second, ..., Join6Second {}
 * }</pre>
 *
 * with:
 *
 * <pre>
 * {@code
 * &#64;PermuteSealedFamily(interfaceName = "JoinBuilderSecond", typeParams = "END, DS")
 * &#64;Permute(varName="i", from="1", to="6", className="Join${i}Second", ...)
 * public static non-sealed class Join0Second<END, DS, A> ... { }
 * }
 * </pre>
 *
 * <p>
 * <b>Maven plugin only.</b> Silently ignored in APT mode.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface PermuteSealedFamily {
    /** Simple name of the sealed interface to generate (e.g. {@code "JoinBuilderSecond"}). */
    String interfaceName();

    /**
     * Type parameter declaration for the interface AND type arguments for the
     * implements clause (e.g. {@code "END, DS"}). Used verbatim in both positions.
     * Leave empty for a non-generic sealed interface.
     */
    String typeParams() default "";
}
