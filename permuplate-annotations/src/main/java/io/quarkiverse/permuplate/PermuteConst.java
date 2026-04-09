package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Replaces the initializer of a field or local variable declaration with the
 * evaluated result of a JEXL expression, in the context of the current permutation.
 *
 * <p>
 * Place on a field or local variable that holds a numeric or string constant that
 * must match the current permutation index. The existing initializer value is used
 * only to make the template compile — it is replaced in every generated class.
 *
 * <p>
 * Integer expressions produce an integer literal; all others produce a string literal.
 *
 * <p>
 * May be combined with {@code @PermuteDeclr} on the same declaration: {@code @PermuteDeclr}
 * updates the type and name; {@code @PermuteConst} updates the initializer value.
 * The two annotations are independent and may appear in either order.
 *
 * <p>
 * Example — interface field (no rename):
 *
 * <pre>{@code
 * @PermuteConst("${i}")
 * int ARITY = 2;
 *
 * default int getArity() {
 *     return ARITY;
 * }
 * // Generated: int ARITY = 3;  (for i=3)
 * }</pre>
 *
 * <p>
 * Example — combined with {@code @PermuteDeclr} (rename + value update):
 *
 * <pre>{@code
 * @PermuteDeclr(type = "int", name = "ARITY_${i}")
 * @PermuteConst("${i}")
 * int ARITY_2 = 2;
 *
 * public int getArity() {
 *     return ARITY_2;
 * }
 * // Generated: int ARITY_3 = 3;  and  return ARITY_3;  (for i=3)
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ ElementType.FIELD, ElementType.LOCAL_VARIABLE })
public @interface PermuteConst {
    /**
     * JEXL expression evaluated in the current permutation context.
     * E.g. {@code "${i}"}, {@code "${i * 2}"}, {@code "'prefix' + ${i}"}.
     */
    String value();
}
