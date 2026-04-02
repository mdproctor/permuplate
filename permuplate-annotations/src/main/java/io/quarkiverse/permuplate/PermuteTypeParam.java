package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Expands a sentinel class type parameter into a sequence of type parameters.
 *
 * <p>
 * Place on a type parameter of a class or interface annotated with {@code @Permute}.
 * The sentinel type parameter is replaced by {@code (to - from + 1)} generated type
 * parameters, each named by evaluating {@code name} with the inner loop variable.
 *
 * <p>
 * <b>Bounds propagation:</b> the sentinel's declared bound (e.g.
 * {@code T1 extends Comparable<T1>}) is copied to each generated parameter with the
 * sentinel name substituted ({@code T2 extends Comparable<T2>}, etc.).
 *
 * <p>
 * <b>Not needed in the common case</b> — when {@link PermuteParam} expands a method
 * parameter whose type is {@code T${j}} and {@code T1} is a class type parameter, the
 * class type parameters are expanded automatically to match. Use {@code @PermuteTypeParam}
 * only for phantom types (type parameters with no corresponding {@link PermuteParam}).
 *
 * <p>
 * Example — explicit phantom type:
 *
 * <pre>{@code
 * @Permute(varName = "i", from = 2, to = 5, className = "Step${i}")
 * public class Step1<@PermuteTypeParam(varName = "j", from = "1", to = "${i}", name = "T${j}") T1> {
 * }
 * // → Step2<T1, T2>, Step3<T1, T2, T3>, ...
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE_PARAMETER)
public @interface PermuteTypeParam {

    /** Inner loop variable name (e.g. {@code "j"}). */
    String varName();

    /** Inner lower bound — literal or expression (e.g. {@code "1"}). */
    String from();

    /**
     * Inner upper bound — expression evaluated against the outer context
     * (e.g. {@code "${i}"}).
     */
    String to();

    /** Generated type parameter name template (e.g. {@code "T${j}"}). */
    String name();
}
