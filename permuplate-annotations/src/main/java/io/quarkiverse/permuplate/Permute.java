package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Drives the outer permutation loop on a class or interface (top-level or nested
 * static), or on a method.
 *
 * <p>
 * <b>Type permutation</b> ({@code @Permute} on a class or interface) — for each
 * combination of the permutation variables, clones the type declaration, applies all
 * transformations, and writes a new top-level source file. Nested types are promoted
 * to top-level. Produces one file per combination.
 *
 * <p>
 * Class example:
 *
 * <pre>{@code
 * &#64;Permute(varName = "i", from = 3, to = 10, className = "Join${i}")
 * public class Join2 { ... }
 * }</pre>
 *
 * Generates Join3 through Join10.
 *
 * <p>
 * Interface example:
 *
 * <pre>{@code
 * @Permute(varName = "i", from = 2, to = 10, className = "Callable${i}")
 * public interface Callable1 {
 *     void call(@PermuteParam(varName = "j", from = "1", to = "${i}", type = "Object", name = "o${j}") Object o1);
 * }
 * }</pre>
 *
 * Generates Callable2 through Callable10.
 *
 * <p>
 * <b>Multiple permutation variables</b> — {@code extraVars} adds additional integer
 * loop variables, generating one class per combination (cross-product). The primary
 * variable ({@code varName}) is the outermost loop; {@code extraVars} are inner loops
 * in declaration order.
 *
 * <pre>{@code
 * &#64;Permute(varName = "i", from = 2, to = 4,
 *          className = "BiCallable${i}x${k}",
 *          extraVars = { @PermuteVar(varName = "k", from = 2, to = 4) })
 * public interface BiCallable1x1 { ... }
 * }</pre>
 *
 * Generates {@code BiCallable2x2} through {@code BiCallable4x4} (9 interfaces).
 *
 * <p>
 * <b>On a method</b> — generates a single new class containing one overload of the
 * method per combination of all variables. The {@code className} attribute names the
 * generated class (it should not contain the permutation variables since all overloads
 * go into the same class).
 *
 * <pre>
 * {@code
 * public class MyUtils {
 *     &#64;Permute(varName = "i", from = 2, to = 4, className = "MultiJoin")
 *     public static void join(
 *         @PermuteParam(varName = "j", from = "1", to = "${i}", type = "Object", name = "o${j}") Object o1) { ... }
 * }
 * }
 * </pre>
 *
 * <p>
 * <b>className prefix rule (type permutation only):</b> the leading literal part of
 * {@code className} (everything before the first {@code ${...}}) must be a prefix of
 * the template class's simple name. {@code className = "Join${i}"} on {@code class Join2}
 * is valid (leading literal {@code "Join"} matches); {@code className = "Bar${i}"} on
 * {@code class Join2} is a compile error. The rule is skipped when {@code className}
 * starts with a {@code ${...}} variable expression, and does not apply when
 * {@code @Permute} is placed on a method.
 *
 * <p>
 * <b>String variables:</b> {@code strings} entries are {@code "key=value"} pairs
 * providing fixed named string constants in all {@code ${...}} expressions. Keys must
 * not conflict with {@code varName} or any {@code extraVars} variable name.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ ElementType.TYPE, ElementType.METHOD })
public @interface Permute {
    /** The primary integer loop variable name (e.g. {@code "i"}). */
    String varName();

    /** Inclusive lower bound for the primary variable. */
    int from();

    /** Inclusive upper bound for the primary variable. Must be &gt;= {@code from}. */
    int to();

    /**
     * Output type/class name template. For type permutation, evaluated per combination
     * (e.g. {@code "Join${i}"}). For method permutation, a fixed class name
     * (e.g. {@code "MultiJoin"}) containing all overloads.
     */
    String className();

    /**
     * Named string constants available in all {@code ${...}} expressions alongside
     * {@code varName}. Each entry must be in {@code "key=value"} format; the
     * separator is the first {@code =} so values may contain further {@code =} signs.
     * Keys must not match {@code varName} or any {@code extraVars} variable name.
     */
    String[] strings() default {};

    /**
     * Additional integer loop variables for cross-product generation. Each entry adds
     * one axis; one output type is generated per combination of all axes. The primary
     * variable ({@code varName}) is the outermost loop; {@code extraVars} are inner
     * loops in declaration order.
     *
     * <p>
     * Variable names must not conflict with {@code varName}, each other, or any
     * {@code strings} key.
     *
     * @see PermuteVar
     */
    PermuteVar[] extraVars() default {};
}
