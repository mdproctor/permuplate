package io.quarkiverse.permuplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Expands a sentinel type parameter into a sequence of type parameters, or renames a
 * method type parameter and propagates the new name into the method's parameter types.
 *
 * <h2>Placement 1: {@code ElementType.TYPE_PARAMETER} — class/interface type parameter</h2>
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
 * on a class type parameter only for phantom types (type parameters with no corresponding
 * {@link PermuteParam}).
 *
 * <p>
 * Example — explicit phantom type:
 *
 * <pre>{@code
 * @Permute(varName = "i", from = "2", to = "5", className = "Step${i}")
 * public class Step1<@PermuteTypeParam(varName = "j", from = "1", to = "${i}", name = "T${j}") T1> {
 * }
 * // → Step2<T1, T2>, Step3<T1, T2, T3>, ...
 * }</pre>
 *
 * <h2>Placement 2: {@code ElementType.METHOD} — method type parameter rename and propagation</h2>
 *
 * <p>
 * Place on a method (not on a type parameter) when you want to rename the method's own
 * type parameter declaration and propagate the new name throughout that method's parameter
 * types. This is the standalone-method variant, intended for ordinary methods inside a
 * {@code @Permute} class that are NOT annotated with {@link PermuteMethod}.
 *
 * <p>
 * The annotation's {@code name} is evaluated once (typically {@code from == to} so only
 * one value is produced). The sentinel type parameter (the single existing method type
 * parameter, e.g. {@code <B>}) is renamed to the evaluated name (e.g. {@code T2}), and
 * every occurrence of the old name inside the method's parameter type list is replaced
 * with the new name. This lets a method's type parameter track the outer {@code @Permute}
 * loop variable — e.g. {@code from = "${i}", to = "${i}"} pins the rename to {@code Ti}.
 *
 * <p>
 * Key differences from {@code TYPE_PARAMETER} placement:
 * <ul>
 * <li>Only a single rename (not an expansion) — use {@code from == to}.</li>
 * <li>Rename propagates into parameter types, NOT into the class type parameter list.</li>
 * <li>R3 prefix check is NOT applied — the sentinel (e.g. {@code B}) is an arbitrary
 * placeholder and need not match the generated name (e.g. {@code T2}).</li>
 * <li>Methods annotated with {@link PermuteMethod} are skipped — their type params are
 * handled during {@code @PermuteMethod} processing with the inner (i,j) context.</li>
 * </ul>
 *
 * <p>
 * Example — method type parameter tracks outer loop:
 *
 * <pre>
 * {
 *     &#64;code
 *     &#64;Permute(varName = "i", from = "2", to = "4", className = "Gatherer${i}")
 *     public class Gatherer1 {
 *         @PermuteTypeParam(varName = "m", from = "${i}", to = "${i}", name = "T${m}")
 *         public <B> void collect(java.util.List<B> items) {
 *         }
 *     }
 *     // Gatherer2: public <T2> void collect(java.util.List<T2> items) {}
 *     // Gatherer3: public <T3> void collect(java.util.List<T3> items) {}
 * }
 * </pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ ElementType.TYPE_PARAMETER, ElementType.METHOD })
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
